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

local function quarantine(reason, message)
    -- Persistent row corruption cannot be repaired by retrying every sweep.
    -- Remove the poison candidate while leaving state and budgets untouched
    -- for operator reconciliation. Persist a durable marker because the WARN
    -- log alone is not enough to discover or classify held reservations later.
    redis.call('HSET', key,
        'quarantined_at', now,
        'quarantine_reason', reason)
    redis.call('ZREM', 'reservation:ttl', reservation_id)
    return cjson.encode({status = "ERROR", error = "INTERNAL_ERROR",
        message = message, quarantine_reason = reason,
        tenant = redis.call('HGET', key, 'tenant')})
end

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

if not expires_at or not grace_ms or grace_ms < 0 then
    return quarantine("INVALID_EXPIRY", "Reservation has invalid expiry data")
end

-- Still within grace window — leave ACTIVE for commit/release.
if now <= expires_at + grace_ms then
    return cjson.encode({status = "SKIP", reason = "in_grace_period"})
end

-- Past grace period: release reserved budget back to all scopes.
-- Fetch remaining fields in one round-trip
local detail = redis.call('HMGET', key, 'estimate_amount', 'estimate_unit', 'affected_scopes', 'budgeted_scopes')
local estimate_amount      = normalize_int(detail[1])
local estimate_unit        = detail[2]
local affected_scopes_json = detail[3]
-- Use budgeted_scopes for budget mutations (only scopes with actual budgets)
local budgeted_scopes_json = detail[4]

if not estimate_amount or compare_int(estimate_amount, "0") < 0
   or not estimate_unit or estimate_unit == "" then
    return quarantine("INVALID_ESTIMATE", "Reservation has invalid estimate data")
end

local scopes_json = budgeted_scopes_json or affected_scopes_json
if not scopes_json then
    return quarantine("MISSING_SCOPES", "Reservation is missing scope data")
end
local scopes_ok, affected_scopes = pcall(cjson.decode, scopes_json)
if not scopes_ok or type(affected_scopes) ~= "table" or #affected_scopes == 0 then
    return quarantine("MALFORMED_SCOPES", "Reservation has malformed scope data")
end
for _, scope in ipairs(affected_scopes) do
    if type(scope) ~= "string" or scope == "" then
        return quarantine("MALFORMED_SCOPES", "Reservation has malformed scope data")
    end
end
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    redis.call('HINCRBY', budget_key, 'reserved',  negate_int(estimate_amount))
    redis.call('HINCRBY', budget_key, 'remaining',  estimate_amount)
end

-- Reuse `now` from earlier TIME call (Redis is single-threaded during script execution)
redis.call('HMSET', key, 'state', 'EXPIRED', 'expired_at', now)
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Set 30-day TTL on expired reservation hash (audit trail, then auto-cleanup)
redis.call('PEXPIRE', key, 2592000000)

return cjson.encode({status = "EXPIRED"})
