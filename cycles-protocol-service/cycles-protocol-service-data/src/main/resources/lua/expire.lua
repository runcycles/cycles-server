-- Cycles Protocol v0.1.23 - Expire Reservation Lua Script
-- Atomically marks an ACTIVE reservation as EXPIRED and releases its reserved budget.
-- Only fires if the reservation is past expires_at + grace_ms.
-- Called by the background expiry job per reservation_id.

local reservation_id = ARGV[1]
-- Use Redis TIME for consistency with reserve/commit/release/extend scripts,
-- which all use Redis TIME for expires_at comparisons. Using Java-provided time
-- (ARGV[2]) could cause clock-skew issues between the app server and Redis.
local t_now = redis.call('TIME')
local now   = tonumber(t_now[1]) * 1000 + math.floor(tonumber(t_now[2]) / 1000)

local key   = "reservation:res_" .. reservation_id
local state = redis.call('HGET', key, 'state')

if not state then
    redis.call('ZREM', 'reservation:ttl', reservation_id)
    return cjson.encode({status = "NOT_FOUND"})
end

-- Already finalised — remove from TTL index and skip.
if state ~= "ACTIVE" then
    redis.call('ZREM', 'reservation:ttl', reservation_id)
    return cjson.encode({status = "SKIP", state = state})
end

local expires_at = tonumber(redis.call('HGET', key, 'expires_at'))
local grace_ms   = tonumber(redis.call('HGET', key, 'grace_ms') or 0)

-- Still within grace window — leave ACTIVE for commit/release.
if now <= expires_at + grace_ms then
    return cjson.encode({status = "SKIP", reason = "in_grace_period"})
end

-- Past grace period: release reserved budget back to all scopes.
local estimate_amount    = tonumber(redis.call('HGET', key, 'estimate_amount'))
local estimate_unit      = redis.call('HGET', key, 'estimate_unit')
local affected_scopes_json = redis.call('HGET', key, 'affected_scopes')
-- Use budgeted_scopes for budget mutations (only scopes with actual budgets)
local budgeted_scopes_json = redis.call('HGET', key, 'budgeted_scopes')

if estimate_amount and estimate_unit and affected_scopes_json then
    local ok, affected_scopes = pcall(cjson.decode, budgeted_scopes_json or affected_scopes_json)
    if not ok then affected_scopes = {} end
    for _, scope in ipairs(affected_scopes) do
        local budget_key = "budget:" .. scope .. ":" .. estimate_unit
        redis.call('HINCRBY', budget_key, 'reserved',  -estimate_amount)
        redis.call('HINCRBY', budget_key, 'remaining',  estimate_amount)
    end
end

local t          = redis.call('TIME')
local expired_at = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
redis.call('HMSET', key, 'state', 'EXPIRED', 'expired_at', expired_at)
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Set 30-day TTL on expired reservation hash (audit trail, then auto-cleanup)
redis.call('PEXPIRE', key, 2592000000)

return cjson.encode({status = "EXPIRED"})
