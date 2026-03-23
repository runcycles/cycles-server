-- Cycles Protocol v0.1.23 - Release Lua Script
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local idempotency_key = ARGV[2]
local payload_hash    = ARGV[3] or ""

local reservation_key = "reservation:res_" .. reservation_id

-- Check reservation exists
if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

local state = redis.call('HGET', reservation_key, 'state')
local stored_idempotency_key = redis.call('HGET', reservation_key, 'released_idempotency_key')

-- Check if already released (idempotent replay or finalized)
if state == "RELEASED" then
    if idempotency_key ~= "" and idempotency_key ~= nil
       and stored_idempotency_key == idempotency_key then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('HGET', reservation_key, 'released_payload_hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cjson.encode({reservation_id = reservation_id, state = "RELEASED"})
    else
        -- Different key on already-released reservation = finalized, not idempotency mismatch
        return cjson.encode({error = "RESERVATION_FINALIZED", state = "RELEASED"})
    end
end

-- Check if can be released
if state == "EXPIRED" then
    return cjson.encode({error = "RESERVATION_EXPIRED", state = state})
elseif state == "COMMITTED" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = "COMMITTED"})
end

-- Spec: release disallowed beyond expires_at_ms + grace_period_ms
local current_expires_at = tonumber(redis.call('HGET', reservation_key, 'expires_at') or 0)
local grace_ms = tonumber(redis.call('HGET', reservation_key, 'grace_ms') or 0)
local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if now_ms > current_expires_at + grace_ms then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

-- Get reservation data using HMGET for efficiency
local rvals = redis.call('HMGET', reservation_key, 'estimate_amount', 'estimate_unit', 'affected_scopes', 'budgeted_scopes')
local estimate_amount = tonumber(rvals[1])
local estimate_unit = rvals[2]
local affected_scopes_json = rvals[3]
-- Use budgeted_scopes for budget mutations (only scopes with actual budgets)
local budgeted_scopes_json = rvals[4]
local affected_scopes = cjson.decode(budgeted_scopes_json or affected_scopes_json)

-- Release from all scopes
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    redis.call('HINCRBY', budget_key, 'reserved', -estimate_amount)
    redis.call('HINCRBY', budget_key, 'remaining', estimate_amount)
end

-- Update reservation (reuse TIME from earlier — no need to call again)
redis.call('HMSET', reservation_key,
    'state', 'RELEASED',
    'released_at', now_ms,
    'released_idempotency_key', idempotency_key,
    'released_payload_hash', payload_hash
)

-- Remove from TTL sorted set — reservation is finalized, no expiry sweep needed.
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Collect balance snapshots for all budgeted scopes (avoids post-operation Java round-trips)
local balances = {}
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    local b = redis.call('HMGET', budget_key, 'remaining', 'reserved', 'spent', 'allocated', 'debt', 'overdraft_limit', 'is_over_limit')
    table.insert(balances, {
        scope = scope,
        remaining = tonumber(b[1] or 0),
        reserved = tonumber(b[2] or 0),
        spent = tonumber(b[3] or 0),
        allocated = tonumber(b[4] or 0),
        debt = tonumber(b[5] or 0),
        overdraft_limit = tonumber(b[6] or 0),
        is_over_limit = (b[7] == "true")
    })
end

return cjson.encode({
    reservation_id = reservation_id,
    state = "RELEASED",
    estimate_amount = estimate_amount,
    estimate_unit = estimate_unit,
    affected_scopes_json = affected_scopes_json,
    balances = balances
})
