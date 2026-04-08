-- Cycles Protocol v0.1.24 - Event Lua Script
-- Atomically records a direct debit event (no reservation required)

local event_id = ARGV[1]
local subject_json = ARGV[2]
local action_json = ARGV[3]
local amount = tonumber(ARGV[4])
local unit = ARGV[5]
local idempotency_key = ARGV[6]
local scope_path = ARGV[7]
local tenant = ARGV[8]
local overage_policy  = ARGV[9] or "ALLOW_IF_AVAILABLE"
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
        -- Spec MUST: replay returns original successful response payload.
        -- Reconstruct charged + balances from stored event data.
        local event_key = "event:evt_" .. existing_event_id
        local evt = redis.call('HMGET', event_key,
            'charged_amount', 'amount', 'unit', 'budgeted_scopes')
        local replay = {
            event_id = existing_event_id,
            status = "APPLIED"
        }
        if evt[4] then
            local bscopes = cjson.decode(evt[4])
            local replay_balances = {}
            for _, scope in ipairs(bscopes) do
                local budget_key = "budget:" .. scope .. ":" .. evt[3]
                local b = redis.call('HMGET', budget_key, 'remaining', 'reserved', 'spent', 'allocated', 'debt', 'overdraft_limit', 'is_over_limit')
                table.insert(replay_balances, {
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
            replay.balances = replay_balances
        end
        -- charged present only when capping occurred
        local ca = tonumber(evt[1] or 0)
        local oa = tonumber(evt[2] or 0)
        if ca < oa then
            replay.charged = ca
        end
        return cjson.encode(replay)
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
-- Skip scopes without budgets — operators may only define budgets at certain levels.
-- Spec: debt/is_over_limit checks only block *reservations*, not events.
-- Events use their overage_policy to handle insufficient budget.
local budgeted_scopes = {}
local scope_budget_cache = {}
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit

    -- Fetch all needed fields in one round-trip
    local bvals = redis.call('HMGET', budget_key, 'status', 'unit', 'remaining', 'overdraft_limit', 'debt', 'is_over_limit')

    -- Check if key exists: at least one field must be non-false
    -- (budget keys may lack an explicit 'status' field, defaulting to ACTIVE)
    local key_exists = false
    for _, v in ipairs(bvals) do
        if v then key_exists = true; break end
    end

    -- Skip scopes without a budget (operator may only define budgets at certain levels)
    if key_exists then
        local budget_status = bvals[1] or 'ACTIVE'
        table.insert(budgeted_scopes, scope)

        -- Check budget status (consistent with admin FUND_LUA)
        if budget_status == 'FROZEN' then
            return cjson.encode({error = "BUDGET_FROZEN", scope = scope})
        end
        if budget_status == 'CLOSED' then
            return cjson.encode({error = "BUDGET_CLOSED", scope = scope})
        end

        -- Spec NORMATIVE: event actual.unit must be supported for the target scope
        local budget_unit = bvals[2]
        if budget_unit and budget_unit ~= unit then
            return cjson.encode({error = "UNIT_MISMATCH", scope = scope,
                expected = budget_unit, actual = unit})
        end

        local remaining = tonumber(bvals[3] or 0)
        local overdraft_limit = tonumber(bvals[4] or 0)
        local current_debt = tonumber(bvals[5] or 0)
        -- Cache for reuse in capping/mutation phases (pre-state for transition detection)
        scope_budget_cache[scope] = {
            remaining = remaining, overdraft_limit = overdraft_limit, debt = current_debt,
            pre_remaining = remaining, pre_is_over_limit = (bvals[6] == "true")
        }

        if remaining < amount then
            if overage_policy == "REJECT" then
                return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = amount})
            end
            -- ALLOW_WITH_OVERDRAFT overdraft limit check is deferred to after
            -- capping phase (zero-limit scopes may reduce effective_amount, so
            -- checking against full `amount` here would be too strict).
        end
    end
end

-- At least one scope must have a budget
if #budgeted_scopes == 0 then
    return cjson.encode({error = "BUDGET_NOT_FOUND", scope = affected_scopes[#affected_scopes]})
end

-- All checks passed - debit amount across budgeted scopes only.
-- For ALLOW_IF_AVAILABLE, cap effective_amount to available remaining.
local effective_amount = amount
local scope_debt_incurred = {}  -- per-scope debt delta for event data
local t_now = redis.call('TIME')
local now = tonumber(t_now[1]) * 1000 + math.floor(tonumber(t_now[2]) / 1000)

if overage_policy == "ALLOW_IF_AVAILABLE" then
    -- Cap to minimum available remaining across all scopes (floor 0)
    -- Uses cached values from validation phase
    local capped = amount
    for _, scope in ipairs(budgeted_scopes) do
        local cached = scope_budget_cache[scope]
        capped = math.min(capped, math.max(cached.remaining, 0))
    end
    effective_amount = capped
    -- Mark over-limit if we couldn't cover the full amount
    if effective_amount < amount then
        for _, scope in ipairs(budgeted_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. unit
            redis.call('HSET', budget_key, 'is_over_limit', 'true')
        end
    end
elseif overage_policy == "ALLOW_WITH_OVERDRAFT" then
    -- Spec: "If overdraft_limit is absent or 0, behaves as ALLOW_IF_AVAILABLE."
    -- Phase 1: cap effective_amount from zero-limit scopes (floor 0).
    -- Uses cached values from validation phase
    for _, scope in ipairs(budgeted_scopes) do
        local cached = scope_budget_cache[scope]
        if cached.overdraft_limit == 0 and cached.remaining < effective_amount then
            effective_amount = math.min(effective_amount, math.max(cached.remaining, 0))
        end
    end
    -- Phase 2: check non-zero scopes against effective_amount (fail fast)
    -- Uses cached values from validation phase
    for _, scope in ipairs(budgeted_scopes) do
        local cached = scope_budget_cache[scope]
        if cached.overdraft_limit > 0 and cached.remaining < effective_amount then
            local funded = math.max(cached.remaining, 0)
            local deficit = effective_amount - funded
            if cached.debt + deficit > cached.overdraft_limit then
                return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope,
                    current_debt = cached.debt, deficit = deficit, overdraft_limit = cached.overdraft_limit})
            end
        end
    end
    -- Mark over-limit on zero-limit scopes if full amount couldn't be covered
    if effective_amount < amount then
        for _, scope in ipairs(budgeted_scopes) do
            if scope_budget_cache[scope].overdraft_limit == 0 then
                local budget_key = "budget:" .. scope .. ":" .. unit
                redis.call('HSET', budget_key, 'is_over_limit', 'true')
            end
        end
    end
end

for _, scope in ipairs(budgeted_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit
    local cached = scope_budget_cache[scope]

    if overage_policy == "ALLOW_WITH_OVERDRAFT" and cached.remaining < effective_amount then
        -- Spec NORMATIVE: remaining = allocated - spent - reserved - debt (can go negative)
        -- spent tracks only the funded portion; debt tracks the unfunded portion.
        -- When remaining is already negative (prior overdraft), the funded portion is 0,
        -- not negative — otherwise spent would decrease and debt would over-count.
        local funded = math.max(cached.remaining, 0)
        local deficit = effective_amount - funded
        redis.call('HINCRBY', budget_key, 'remaining', -effective_amount)
        redis.call('HINCRBY', budget_key, 'spent', funded)
        redis.call('HINCRBY', budget_key, 'debt', deficit)
        scope_debt_incurred[scope] = deficit
        local new_debt = cached.debt + deficit
        if cached.overdraft_limit > 0 and new_debt > cached.overdraft_limit then
            redis.call('HSET', budget_key, 'is_over_limit', 'true')
        end
    else
        redis.call('HINCRBY', budget_key, 'remaining', -effective_amount)
        redis.call('HINCRBY', budget_key, 'spent', effective_amount)
    end
end

-- Collect balance snapshots for all budgeted scopes (avoids post-operation Java round-trips)
local balances = {}
for _, scope in ipairs(budgeted_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit
    local b = redis.call('HMGET', budget_key, 'remaining', 'reserved', 'spent', 'allocated', 'debt', 'overdraft_limit', 'is_over_limit')
    local cached = scope_budget_cache[scope] or {}
    table.insert(balances, {
        scope = scope,
        remaining = tonumber(b[1] or 0),
        reserved = tonumber(b[2] or 0),
        spent = tonumber(b[3] or 0),
        allocated = tonumber(b[4] or 0),
        debt = tonumber(b[5] or 0),
        overdraft_limit = tonumber(b[6] or 0),
        is_over_limit = (b[7] == "true"),
        debt_incurred = scope_debt_incurred[scope] or 0,
        pre_remaining = cached.pre_remaining or 0,
        pre_is_over_limit = cached.pre_is_over_limit or false
    })
end

-- Store event record
local event_key = "event:evt_" .. event_id
redis.call('HMSET', event_key,
    'event_id', event_id,
    'tenant', tenant,
    'subject_json', subject_json,
    'action_json', action_json,
    'amount', amount,
    'charged_amount', effective_amount,
    'unit', unit,
    'scope_path', scope_path,
    'affected_scopes', cjson.encode(affected_scopes),
    'budgeted_scopes', cjson.encode(budgeted_scopes),
    'created_at', now,
    'idempotency_key', idempotency_key,
    'metrics_json', metrics_json,
    'client_time_ms', client_time_ms,
    'metadata_json', metadata_json
)

-- Set 30-day TTL on event hash (audit trail, then auto-cleanup)
redis.call('PEXPIRE', event_key, 2592000000)

-- Store idempotency mapping (expire after 7 days)
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    redis.call('PSETEX', idem_key, 604800000, event_id)
    -- Store payload hash for idempotency mismatch detection (spec MUST)
    if payload_hash ~= "" then
        redis.call('PSETEX', idem_key .. ':hash', 604800000, payload_hash)
    end
end

-- Spec: charged is present when capping occurred (ALLOW_IF_AVAILABLE, or
-- ALLOW_WITH_OVERDRAFT with overdraft_limit=0 which behaves as ALLOW_IF_AVAILABLE).
local result = {
    event_id = event_id,
    status = "APPLIED",
    balances = balances
}
if effective_amount < amount then
    result.charged = effective_amount
end
return cjson.encode(result)
