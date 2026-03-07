-- Cycles Protocol v0.1.23 - Release Lua Script
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local idempotency_key = ARGV[2]

local reservation_key = "reservation:res_" .. reservation_id

-- Check reservation exists
if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

local state = redis.call('HGET', reservation_key, 'state')
local stored_idempotency_key = redis.call('HGET', reservation_key, 'released_idempotency_key')

-- Check if already released (idempotent replay or finalized)
if state == "RELEASED" then
    if stored_idempotency_key == idempotency_key then
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

-- Get reservation data
local estimate_amount = tonumber(redis.call('HGET', reservation_key, 'estimate_amount'))
local estimate_unit = redis.call('HGET', reservation_key, 'estimate_unit')
local affected_scopes_json = redis.call('HGET', reservation_key, 'affected_scopes')
local affected_scopes = cjson.decode(affected_scopes_json)

-- Release from all scopes
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    redis.call('HINCRBY', budget_key, 'reserved', -estimate_amount)
    redis.call('HINCRBY', budget_key, 'remaining', estimate_amount)
end

-- Update reservation
local tRelease = redis.call('TIME')
local now = tonumber(tRelease[1]) * 1000 + math.floor(tonumber(tRelease[2]) / 1000)
redis.call('HMSET', reservation_key,
    'state', 'RELEASED',
    'released_at', now,
    'released_idempotency_key', idempotency_key
)

return cjson.encode({reservation_id = reservation_id, state = "RELEASED"})
