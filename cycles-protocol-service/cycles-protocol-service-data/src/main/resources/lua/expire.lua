-- Cycles Protocol v0.1.24 - Expire Reservation Lua Script
-- Finalizes a due ACTIVE reservation and refunds any outstanding budget holds.
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
local detail = redis.call('HMGET', key, 'estimate_amount', 'estimate_unit', 'affected_scopes', 'budgeted_scopes', 'tenant')
local estimate_amount      = normalize_int(detail[1])
local estimate_unit        = detail[2]
local affected_scopes_json = detail[3]
-- Use budgeted_scopes for budget mutations (only scopes with actual budgets)
local budgeted_scopes_json = detail[4]

local MAX_INT64 = "9223372036854775807"
local MIN_INT64 = "-9223372036854775808"
if not estimate_amount or compare_int(estimate_amount, "0") < 0
   or compare_int(estimate_amount, MAX_INT64) > 0
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
local seen_scopes = {}
for _, scope in ipairs(affected_scopes) do
    if type(scope) ~= "string" or scope == "" or seen_scopes[scope] then
        return quarantine("MALFORMED_SCOPES", "Reservation has malformed scope data")
    end
    seen_scopes[scope] = true
end
-- Validate every budget row BEFORE the first mutation. Redis does not roll a
-- script back when a later command errors, so a HINCRBY failure on scope N
-- (wrong key type, non-integer field, int64 overflow) would leave scopes
-- 1..N-1 released while the reservation stays ACTIVE in reservation:ttl —
-- and the sweeper would re-apply that partial release on every pass.
local refund_keys = {}
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    local bvals = redis.pcall('HMGET', budget_key, 'reserved', 'remaining', 'status')
    if type(bvals) ~= "table" or bvals.err then
        return quarantine("CORRUPT_BUDGET", "Reservation budget row is not a hash")
    end
    local b_reserved, b_remaining = bvals[1], bvals[2]
    -- The tenant-close cascade already credited every hold on a CLOSED budget.
    -- Other scopes in the same reservation may still need their refund.
    local refund = bvals[3] == "CLOSED" and "0" or estimate_amount
    -- HINCRBY parses the stored bytes strictly (including leading zeros and -0).
    -- Require both counters so a refund cannot create a missing ledger.
    if not b_reserved or not b_remaining
       or normalize_int(b_reserved) ~= b_reserved or normalize_int(b_remaining) ~= b_remaining
       or compare_int(b_reserved, MAX_INT64) > 0
       or compare_int(b_reserved, refund) < 0
       or compare_int(b_remaining, MIN_INT64) < 0
       or compare_int(add_int(b_remaining, refund), MAX_INT64) > 0 then
        return quarantine("CORRUPT_BUDGET", "Reservation budget row has invalid amounts")
    end
    if refund ~= "0" then
        table.insert(refund_keys, budget_key)
    end
end
-- Determine the terminal state before mutating any ledger. A budget can close
-- independently; only the owning tenant's CLOSED status identifies a cascade.
local terminal_state = "EXPIRED"
local owner = detail[5]
if owner and owner ~= "" then
    local tenant_json = redis.call('GET', 'tenant:' .. owner)
    if tenant_json then
        local tenant = cjson.decode(tenant_json)
        if type(tenant) ~= "table" then
            return redis.error_reply("Malformed tenant record during settlement")
        end
        if tenant.status == "CLOSED" then
            terminal_state = "RELEASED"
        elseif tenant.status ~= "ACTIVE" and tenant.status ~= "SUSPENDED" then
            return redis.error_reply("Malformed tenant status during settlement")
        end
    end
end

for _, budget_key in ipairs(refund_keys) do
    redis.call('HINCRBY', budget_key, 'reserved',  negate_int(estimate_amount))
    redis.call('HINCRBY', budget_key, 'remaining',  estimate_amount)
end

-- Reuse `now` from earlier TIME call (Redis is single-threaded during script execution)
if terminal_state == "RELEASED" then
    redis.call('HSET', key, 'state', terminal_state, 'released_at', now, 'release_reason', 'tenant_closed')
else
    redis.call('HSET', key, 'state', terminal_state, 'expired_at', now)
end
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Retain the terminal reservation hash for the 30-day audit window.
redis.call('PEXPIRE', key, 2592000000)

return cjson.encode({status = terminal_state})
