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
local overage_policy  = ARGV[9] or "REJECT"
local metrics_json    = ARGV[10] or ""
local client_time_ms  = ARGV[11] or ""
local payload_hash    = ARGV[12] or ""
local metadata_json   = ARGV[13] or ""

-- Check idempotency
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    local existing_event_id = redis.call('GET', idem_key)
    if existing_event_id then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('GET', idem_key .. ':hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cjson.encode({
            event_id = existing_event_id,
            idempotency_key = idempotency_key,
            status = "APPLIED"
        })
    end
end

-- Parse affected scopes.
-- Fixed args: ARGV[1]=event_id .. [13]=metadata_json.
-- Affected scopes are the variadic tail starting at ARGV[14].
local affected_scopes = {}
for i = 14, #ARGV do
    table.insert(affected_scopes, ARGV[i])
end

-- Check all scopes first (fail fast, no mutations).
-- Spec: debt/is_over_limit checks only block *reservations*, not events.
-- Events use their overage_policy to handle insufficient budget.
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit

    if redis.call('EXISTS', budget_key) == 0 then
        return cjson.encode({error = "BUDGET_NOT_FOUND", scope = scope})
    end

    -- Spec NORMATIVE: event actual.unit must be supported for the target scope
    local budget_unit = redis.call('HGET', budget_key, 'unit')
    if budget_unit and budget_unit ~= unit then
        return cjson.encode({error = "UNIT_MISMATCH", scope = scope,
            expected = budget_unit, actual = unit})
    end

    local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
    if remaining < amount then
        if overage_policy == "REJECT" then
            return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = amount})
        elseif overage_policy == "ALLOW_IF_AVAILABLE" then
            -- Partial debit: will charge min(amount, remaining) in the mutation phase.
            -- No error here; the effective_amount variable below handles capping.
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

-- Determine effective charge amount.
-- ALLOW_IF_AVAILABLE caps the charge at the minimum remaining across all scopes.
local effective_amount = amount
if overage_policy == "ALLOW_IF_AVAILABLE" then
    for _, scope in ipairs(affected_scopes) do
        local budget_key = "budget:" .. scope .. ":" .. unit
        local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
        if remaining < effective_amount then
            effective_amount = remaining
        end
    end
    if effective_amount < 0 then effective_amount = 0 end
end

-- All checks passed - debit across all scopes
local t_now = redis.call('TIME')
local now = tonumber(t_now[1]) * 1000 + math.floor(tonumber(t_now[2]) / 1000)
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit
    local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)

    if overage_policy == "ALLOW_WITH_OVERDRAFT" and remaining < effective_amount then
        local deficit = effective_amount - remaining
        local current_debt = tonumber(redis.call('HGET', budget_key, 'debt') or 0)
        local overdraft_limit = tonumber(redis.call('HGET', budget_key, 'overdraft_limit') or 0)
        redis.call('HSET', budget_key, 'remaining', 0)
        redis.call('HINCRBY', budget_key, 'spent', effective_amount)
        redis.call('HINCRBY', budget_key, 'debt', deficit)
        local new_debt = current_debt + deficit
        if overdraft_limit > 0 and new_debt > overdraft_limit then
            redis.call('HSET', budget_key, 'is_over_limit', 'true')
        end
    else
        redis.call('HINCRBY', budget_key, 'remaining', -effective_amount)
        redis.call('HINCRBY', budget_key, 'spent', effective_amount)
    end
end

-- Store event record
local event_key = "event:evt_" .. event_id
redis.call('HMSET', event_key,
    'event_id', event_id,
    'tenant', tenant,
    'subject_json', subject_json,
    'action_json', action_json,
    'amount', effective_amount,
    'requested_amount', amount,
    'unit', unit,
    'scope_path', scope_path,
    'affected_scopes', cjson.encode(affected_scopes),
    'created_at', now,
    'idempotency_key', idempotency_key,
    'metrics_json', metrics_json,
    'client_time_ms', client_time_ms,
    'metadata_json', metadata_json
)

-- Store idempotency mapping (expire after 7 days)
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    redis.call('SET', idem_key, event_id)
    redis.call('PEXPIRE', idem_key, 604800000)
    -- Store payload hash for idempotency mismatch detection (spec MUST)
    if payload_hash ~= "" then
        redis.call('SET', idem_key .. ':hash', payload_hash)
        redis.call('PEXPIRE', idem_key .. ':hash', 604800000)
    end
end

return cjson.encode({
    event_id = event_id,
    status = "APPLIED",
    amount_charged = effective_amount
})
