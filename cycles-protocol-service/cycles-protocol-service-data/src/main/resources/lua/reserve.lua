-- Cycles Protocol v0.1.24 - Reserve Lua Script
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
local overage_policy = ARGV[11] or "ALLOW_IF_AVAILABLE"
local metadata_json   = ARGV[12] or ""
local payload_hash    = ARGV[13] or ""
local max_extensions  = tonumber(ARGV[14]) or 10

if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":reserve:" .. idempotency_key
    local existing_res_id = redis.call('GET', idem_key)

    if existing_res_id then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('GET', idem_key .. ':hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cjson.encode({
            reservation_id = existing_res_id,
            idempotency_key = idempotency_key,
        })
    end
end
-- Parse affected scopes (fixed args end at ARGV[14]; scopes start at ARGV[15])
local affected_scopes = {}
for i = 15, #ARGV do
    table.insert(affected_scopes, ARGV[i])
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local expires_at = now + ttl_ms

-- Check all scopes first (fail fast). Skip scopes without budgets.
-- Use HMGET to fetch all needed fields in a single call per scope.
local budgeted_scopes = {}
local pre_budget_state = {}  -- pre-mutation state for transition detection in events
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    local vals = redis.call('HMGET', budget_key, 'status', 'remaining', 'debt', 'is_over_limit', 'overdraft_limit')

    -- Skip scopes without a budget (all fields nil means key doesn't exist)
    if vals[1] ~= false or vals[2] ~= false or vals[3] ~= false or vals[4] ~= false then
        table.insert(budgeted_scopes, scope)

        local budget_status = vals[1] or 'ACTIVE'
        local remaining = tonumber(vals[2] or 0)
        local debt = tonumber(vals[3] or 0)
        local is_over_limit = vals[4]
        pre_budget_state[scope] = {remaining = remaining, is_over_limit = (is_over_limit == "true")}

        if budget_status == 'FROZEN' then
            return cjson.encode({error = "BUDGET_FROZEN", scope = scope})
        end
        if budget_status == 'CLOSED' then
            return cjson.encode({error = "BUDGET_CLOSED", scope = scope})
        end
        if is_over_limit == "true" then
            return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope, message = "Scope is over-limit, no new reservations allowed"})
        end
        local overdraft_limit = tonumber(vals[5] or 0)
        if debt > 0 and overdraft_limit == 0 then
            return cjson.encode({error = "DEBT_OUTSTANDING", scope = scope, debt = debt})
        end
        if remaining < estimate_amount then
            return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = estimate_amount})
        end
    end
end

-- At least one scope must have a budget
if #budgeted_scopes == 0 then
    return cjson.encode({error = "BUDGET_NOT_FOUND", scope = affected_scopes[#affected_scopes]})
end

-- All checks passed - reserve across budgeted scopes only
for _, scope in ipairs(budgeted_scopes) do
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
    'overage_policy', overage_policy,
    'metadata_json', metadata_json,
    'budgeted_scopes', cjson.encode(budgeted_scopes),
    'max_extensions', max_extensions,
    'extension_count', 0
)

-- Add to reservation index
redis.call('ZADD', 'reservation:ttl', expires_at, reservation_id)

-- After successful reservation, store idempotency mapping
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":reserve:" .. idempotency_key
    -- Minimum 24h TTL to avoid premature idempotency key recycling on short-lived reservations
    local idem_ttl = math.max(ttl_ms + grace_ms, 86400000)
    redis.call('PSETEX', idem_key, idem_ttl, reservation_id)
    -- Store payload hash for idempotency mismatch detection (spec MUST)
    if payload_hash ~= "" then
        redis.call('PSETEX', idem_key .. ':hash', idem_ttl, payload_hash)
    end
end

-- Collect balance snapshots for all budgeted scopes (avoids post-operation Java round-trips)
local balances = {}
for _, scope in ipairs(budgeted_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    local b = redis.call('HMGET', budget_key, 'remaining', 'reserved', 'spent', 'allocated', 'debt', 'overdraft_limit', 'is_over_limit')
    local pre = pre_budget_state[scope] or {}
    table.insert(balances, {
        scope = scope,
        remaining = tonumber(b[1] or 0),
        reserved = tonumber(b[2] or 0),
        spent = tonumber(b[3] or 0),
        allocated = tonumber(b[4] or 0),
        debt = tonumber(b[5] or 0),
        overdraft_limit = tonumber(b[6] or 0),
        is_over_limit = (b[7] == "true"),
        pre_remaining = pre.remaining or 0,
        pre_is_over_limit = pre.is_over_limit or false
    })
end

return cjson.encode({
    reservation_id = reservation_id,
    state = "ACTIVE",
    expires_at = expires_at,
    affected_scopes = affected_scopes,
    balances = balances
})
