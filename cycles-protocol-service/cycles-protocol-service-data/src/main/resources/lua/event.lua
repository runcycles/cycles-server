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
local overage_policy = ARGV[9] or "REJECT"

-- Check idempotency
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    local existing_event_id = redis.call('GET', idem_key)
    if existing_event_id then
        return cjson.encode({
            event_id = existing_event_id,
            idempotency_key = idempotency_key,
            status = "APPLIED"
        })
    end
end

-- Parse affected scopes.
-- Fixed args: ARGV[1]=event_id, [2]=subject_json, [3]=action_json,
--             [4]=amount, [5]=unit, [6]=idempotency_key, [7]=scope_path,
--             [8]=tenant, [9]=overage_policy.
-- Affected scopes are the variadic tail starting at ARGV[10].
local affected_scopes = {}
for i = 10, #ARGV do
    table.insert(affected_scopes, ARGV[i])
end

-- Check all scopes first (fail fast, no mutations)
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
        if overage_policy == "REJECT" or overage_policy == "ALLOW_IF_AVAILABLE" then
            return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = amount})
        elseif overage_policy == "ALLOW_WITH_OVERDRAFT" then
            local deficit = amount - remaining
            local current_debt = tonumber(redis.call('HGET', budget_key, 'debt') or 0)
            local overdraft_limit = tonumber(redis.call('HGET', budget_key, 'overdraft_limit') or 0)
            if current_debt + deficit > overdraft_limit then
                return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope,
                    current_debt = current_debt, deficit = deficit, overdraft_limit = overdraft_limit})
            end
        end
    end
end

-- All checks passed - debit across all scopes
local t_now = redis.call('TIME')
local now = tonumber(t_now[1]) * 1000 + math.floor(tonumber(t_now[2]) / 1000)
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit
    local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)

    if overage_policy == "ALLOW_WITH_OVERDRAFT" and remaining < amount then
        local deficit = amount - remaining
        local current_debt = tonumber(redis.call('HGET', budget_key, 'debt') or 0)
        local overdraft_limit = tonumber(redis.call('HGET', budget_key, 'overdraft_limit') or 0)
        redis.call('HSET', budget_key, 'remaining', 0)
        redis.call('HINCRBY', budget_key, 'spent', amount)
        redis.call('HINCRBY', budget_key, 'debt', deficit)
        local new_debt = current_debt + deficit
        if overdraft_limit > 0 and new_debt >= overdraft_limit then
            redis.call('HSET', budget_key, 'is_over_limit', 'true')
        end
    else
        redis.call('HINCRBY', budget_key, 'remaining', -amount)
        redis.call('HINCRBY', budget_key, 'spent', amount)
    end
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
    status = "APPLIED"
})
