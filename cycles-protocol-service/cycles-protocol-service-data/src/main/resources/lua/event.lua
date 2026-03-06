-- Cycles Protocol v0.1.23 - Event Lua Script
-- Atomically records a direct debit event (no reservation required)

local event_id = ARGV[1]
local subject_json = ARGV[2]
local action_json = ARGV[3]
local amount = tonumber(ARGV[4])
local unit = ARGV[5]
local idempotency_key = ARGV[6]
local scope_path = ARGV[7]
local tenant = ARGV[8]

-- Check idempotency
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    local existing_event_id = redis.call('GET', idem_key)
    if existing_event_id then
        return cjson.encode({
            event_id = existing_event_id,
            idempotency_key = idempotency_key,
            status = "RECORDED"
        })
    end
end

-- Parse affected scopes
local affected_scopes = {}
for i = 9, #ARGV do
    table.insert(affected_scopes, ARGV[i])
end

-- Check all scopes first (fail fast)
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit

    if redis.call('EXISTS', budget_key) == 0 then
        return cjson.encode({error = "BUDGET_NOT_FOUND", scope = scope})
    end

    local is_over_limit = redis.call('HGET', budget_key, 'is_over_limit')
    if is_over_limit == "true" then
        return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope})
    end

    local debt = tonumber(redis.call('HGET', budget_key, 'debt') or 0)
    if debt > 0 then
        return cjson.encode({error = "DEBT_OUTSTANDING", scope = scope, debt = debt})
    end

    local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
    if remaining < amount then
        return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = amount})
    end
end

-- All checks passed - debit across all scopes
local now = tonumber(redis.call('TIME')[1]) * 1000
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit
    redis.call('HINCRBY', budget_key, 'remaining', -amount)
    redis.call('HINCRBY', budget_key, 'spent', amount)
end

-- Store event record
local event_key = "event:evt_" .. event_id
redis.call('HMSET', event_key,
    'event_id', event_id,
    'tenant', tenant,
    'subject_json', subject_json,
    'action_json', action_json,
    'amount', amount,
    'unit', unit,
    'scope_path', scope_path,
    'affected_scopes', cjson.encode(affected_scopes),
    'created_at', now,
    'idempotency_key', idempotency_key
)

-- Store idempotency mapping (expire after 7 days)
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    redis.call('SET', idem_key, event_id)
    redis.call('PEXPIRE', idem_key, 604800000)
end

return cjson.encode({
    event_id = event_id,
    status = "RECORDED",
    charged = amount
})
