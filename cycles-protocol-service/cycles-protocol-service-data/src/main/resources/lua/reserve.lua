-- Cycles Protocol v0.1.22 - Reserve Lua Script
-- Atomically reserve budget across all affected scopes
--local cjson = require("cjson")

-- Parse inputs
local reservation_id = ARGV[1]
local subject_json = ARGV[2]
local action_json = ARGV[3]
local estimate_amount = tonumber(ARGV[4])
local estimate_unit = ARGV[5]
local ttl_ms = tonumber(ARGV[6])
local grace_ms = tonumber(ARGV[7])
local idempotency_key = ARGV[8]
local scope_path = ARGV[9]
local tenant = ARGV[10]
local overage_policy = ARGV[11] or "REJECT"

if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":reserve:" .. idempotency_key
    local existing_res_id = redis.call('GET', idem_key)

    if existing_res_id then
        -- Found existing reservation identifier - return it immediately
        --return redis.call('HGET', 'reservation:res_' .. existing_res_id, 'response_json')
        return cjson.encode({
            reservation_id = existing_res_id,
            idempotency_key = idempotency_key,
        })
    end
end
-- Parse affected scopes
local affected_scopes = {}
for i = 12, #ARGV do
    table.insert(affected_scopes, ARGV[i])
end

local now = tonumber(redis.call('TIME')[1]) * 1000
local expires_at = now + ttl_ms

-- Check all scopes first (fail fast)
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    
    -- Check if budget exists
    if redis.call('EXISTS', budget_key) == 0 then
        return cjson.encode({error = "BUDGET_NOT_FOUND", scope = scope})
    end
    
    -- Get budget state
    local allocated = tonumber(redis.call('HGET', budget_key, 'allocated') or 0)
    local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
    local debt = tonumber(redis.call('HGET', budget_key, 'debt') or 0)
    local is_over_limit = redis.call('HGET', budget_key, 'is_over_limit')
    
    -- v0.1.22: Check if scope is over-limit (debt > overdraft_limit)
    if is_over_limit == "true" then
        return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope, message = "Scope is over-limit, no new reservations allowed"})
    end
    
    -- v0.1.22: Check if scope has outstanding debt
    if debt > 0 then
        return cjson.encode({error = "DEBT_OUTSTANDING", scope = scope, debt = debt})
    end
    
    -- Check if sufficient budget
    if remaining < estimate_amount then
        return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = estimate_amount})
    end
end

-- All checks passed - reserve across all scopes
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    redis.call('HINCRBY', budget_key, 'reserved', estimate_amount)
    redis.call('HINCRBY', budget_key, 'remaining', -estimate_amount)
end

-- Create reservation
local reservation_key = "reservation:res_" .. reservation_id
redis.call('HMSET', reservation_key,
    'reservation_id', reservation_id,
    'tenant', tenant,
    'state', 'ACTIVE',
    'subject_json', subject_json,
    'action_json', action_json,
    'estimate_amount', estimate_amount,
    'estimate_unit', estimate_unit,
    'scope_path', scope_path,
    'affected_scopes', cjson.encode(affected_scopes),
    'created_at', now,
    'expires_at', expires_at,
    'grace_ms', grace_ms,
    'idempotency_key', idempotency_key,
    'overage_policy', overage_policy
)

-- Set TTL on reservation, PEXPIRE means that after ttl REDIS will remove the record itself
-- redis.call('PEXPIRE', reservation_key, ttl_ms + grace_ms)
redis.call('HSET', reservation_key, 'expires_at', expires_at)

-- Add to reservation index
redis.call('ZADD', 'reservation:ttl', expires_at, reservation_id)

-- After successful reservation, store idempotency mapping
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":reserve:" .. idempotency_key
    -- Store the entire JSON response
    redis.call('SET', idem_key, reservation_id)
    -- Setting TTL as reservation that actually expires and removes the record
    redis.call('PEXPIRE', idem_key, ttl_ms + grace_ms)
end

return cjson.encode({
    reservation_id = reservation_id,
    state = "ACTIVE",
    expires_at = expires_at,
    affected_scopes = affected_scopes
})
