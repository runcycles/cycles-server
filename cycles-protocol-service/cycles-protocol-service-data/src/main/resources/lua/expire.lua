-- Cycles Protocol v0.1.24 - Expire Reservation Lua Script
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

-- Fetch state, expires_at, grace_ms in one round-trip for early-exit checks
local early = redis.call('HMGET', key, 'state', 'expires_at', 'grace_ms')
local state = early[1]

if not state then
    redis.call('ZREM', 'reservation:ttl', reservation_id)
    return cjson.encode({status = "NOT_FOUND"})
end

-- Already finalised — remove from TTL index and skip.
if state ~= "ACTIVE" then
    redis.call('ZREM', 'reservation:ttl', reservation_id)
    return cjson.encode({status = "SKIP", state = state})
end

local expires_at = tonumber(early[2])
local grace_ms   = tonumber(early[3] or 0)

-- Still within grace window — leave ACTIVE for commit/release.
if now <= expires_at + grace_ms then
    return cjson.encode({status = "SKIP", reason = "in_grace_period"})
end

-- Past grace period: release reserved budget back to all scopes.
-- Fetch remaining fields in one round-trip
local detail = redis.call('HMGET', key, 'estimate_amount', 'estimate_unit', 'affected_scopes', 'budgeted_scopes')
local estimate_amount      = tonumber(detail[1])
local estimate_unit        = detail[2]
local affected_scopes_json = detail[3]
-- Use budgeted_scopes for budget mutations (only scopes with actual budgets)
local budgeted_scopes_json = detail[4]

if estimate_amount and estimate_unit and (budgeted_scopes_json or affected_scopes_json) then
    local ok, affected_scopes = pcall(cjson.decode, budgeted_scopes_json or affected_scopes_json)
    if not ok then affected_scopes = {} end
    for _, scope in ipairs(affected_scopes) do
        local budget_key = "budget:" .. scope .. ":" .. estimate_unit
        redis.call('HINCRBY', budget_key, 'reserved',  -estimate_amount)
        redis.call('HINCRBY', budget_key, 'remaining',  estimate_amount)
    end
end

-- Reuse `now` from earlier TIME call (Redis is single-threaded during script execution)
redis.call('HMSET', key, 'state', 'EXPIRED', 'expired_at', now)
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Set 30-day TTL on expired reservation hash (audit trail, then auto-cleanup)
redis.call('PEXPIRE', key, 2592000000)

return cjson.encode({status = "EXPIRED"})
