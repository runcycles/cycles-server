-- Cycles Protocol v0.1.24 - Event Lua Script
-- Atomically records a direct debit event (no reservation required)

local event_id = ARGV[1]
local subject_json = ARGV[2]
local action_json = ARGV[3]
local amount = normalize_int(ARGV[4])
local unit = ARGV[5]
local idempotency_key = ARGV[6]
local scope_path = ARGV[7]
local tenant = ARGV[8]
local overage_policy  = ARGV[9] or "ALLOW_IF_AVAILABLE"
local metrics_json    = ARGV[10] or ""
local client_time_ms  = ARGV[11] or ""
local payload_hash    = ARGV[12] or ""
local metadata_json   = ARGV[13] or ""
local units_csv       = ARGV[14] or ""

if not amount or compare_int(amount, "0") < 0 then
    return cjson.encode({error = "INVALID_REQUEST", message = "actual amount must be a non-negative int64"})
end

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
        local stored_response = redis.call('GET', idem_key .. ':response')
        if stored_response then
            return stored_response
        end
        -- Spec MUST: replay returns original successful response payload.
        local event_key = "event:evt_" .. existing_event_id
        local snapshot = redis.call('HGET', event_key, 'event_response_json')
        if snapshot then
            local remaining_ttl = redis.call('PTTL', idem_key)
            if remaining_ttl > 0 then
                redis.call('PSETEX', idem_key .. ':response', remaining_ttl, snapshot)
            end
            return snapshot
        end
        -- Pre-snapshot rows cannot be reconstructed from current balances
        -- without violating the byte-identical replay requirement.
        return cjson.encode({error = "INTERNAL_ERROR",
            message = "Original event idempotency response is unavailable; retry with the same idempotency_key"})
    end
end

-- Governance CASCADE SEMANTICS Rule 2 (cycles-governance-admin-v0.1.25):
-- POST /v1/events is a persisting budget debit, so once the owning tenant's
-- CLOSED flip is durable the event MUST be rejected with 409 TENANT_CLOSED,
-- regardless of per-scope budget status and even before the close cascade
-- reaches this tenant's budgets or revokes API keys (Mode B invariant (a)).
-- Runtime spec revision v0.1.25.14 (pending) adds /v1/events to the
-- closed-tenant binding. Copied verbatim from reserve.lua's guard: checked
-- INSIDE the script — like the BUDGET_FROZEN/BUDGET_CLOSED per-scope guards
-- below — so the guard is atomic with the budget debit (Redis runs scripts
-- serially; a request observed after the flip can never partially succeed).
-- The tenant record is written by the admin plane to the shared Redis
-- ("tenant:<id>" JSON, status field); ABSENCE of the record (runtime-only
-- deployment, no governance plane) means no restriction, but a PRESENT
-- record that cannot be decoded into an object with a valid TenantStatus
-- (ACTIVE|SUSPENDED|CLOSED) FAILS CLOSED (INTERNAL_ERROR, no mutation) —
-- matching the admin plane's TenantRepository, which propagates parse
-- failures instead of treating a corrupt governance record as an open
-- tenant. Sits after the idempotency-replay block above (Mode B invariant
-- (b): a pre-close replay still returns its stored response) and before the
-- scope/mutation phase, so TENANT_CLOSED precedes the per-scope BUDGET_*
-- checks for a fresh event.
if tenant ~= nil and tenant ~= "" then
    local tenant_json = redis.call('GET', 'tenant:' .. tenant)
    if tenant_json then
        local ok_tenant, tenant_rec = pcall(cjson.decode, tenant_json)
        if not ok_tenant or type(tenant_rec) ~= 'table' or type(tenant_rec['status']) ~= 'string' then
            return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed tenant record: tenant:" .. tenant})
        end
        if tenant_rec['status'] == 'CLOSED' then
            return cjson.encode({error = "TENANT_CLOSED", tenant = tenant})
        end
        if tenant_rec['status'] ~= 'ACTIVE' and tenant_rec['status'] ~= 'SUSPENDED' then
            -- The governance TenantStatus enum is a closed set (ACTIVE|
            -- SUSPENDED|CLOSED) and the cascade revision explicitly
            -- introduces no new status values as a wire-compat guarantee,
            -- so an unknown status (e.g. "CLOZED", lowercase "closed")
            -- cannot be a legitimate future value under the current
            -- contract - it is corruption. Fail closed like the other
            -- malformed shapes.
            return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed tenant record: tenant:" .. tenant})
        end
    end
end

if overage_policy ~= "REJECT"
   and overage_policy ~= "ALLOW_IF_AVAILABLE"
   and overage_policy ~= "ALLOW_WITH_OVERDRAFT" then
    return cjson.encode({error = "INVALID_REQUEST", message = "Invalid overage_policy: " .. tostring(overage_policy)})
end

-- Parse affected scopes.
-- Fixed args: ARGV[1]=event_id .. [13]=metadata_json, [14]=units_csv.
-- Affected scopes are the variadic tail starting at ARGV[15].
local affected_scopes = {}
for i = 15, #ARGV do
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

        -- Spec NORMATIVE: event actual.unit must be supported for the target scope.
        -- This branch is a defensive data-integrity check: it fires only when
        -- budget:<scope>:<unit> exists AND the stored `unit` field inside the hash
        -- disagrees with the key suffix — an internal inconsistency that shouldn't
        -- happen under normal operation. We keep it as a safety net and emit the
        -- same {scope, requested_unit, expected_units} shape as the cross-unit
        -- probe below so handleScriptError (Java) extracts details uniformly.
        local budget_unit = bvals[2]
        if budget_unit and budget_unit ~= unit then
            return cjson.encode({error = "UNIT_MISMATCH", scope = scope,
                requested_unit = unit, expected_units = {budget_unit}})
        end

        local remaining = normalize_int(bvals[3] or "0")
        local overdraft_limit = normalize_int(bvals[4] or "0")
        local current_debt = normalize_int(bvals[5] or "0")
        if not remaining or not overdraft_limit or not current_debt then
            return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed budget amount: " .. budget_key})
        end
        -- Cache for reuse in capping/mutation phases (pre-state for transition detection)
        scope_budget_cache[scope] = {
            remaining = remaining, overdraft_limit = overdraft_limit, debt = current_debt,
            pre_remaining = remaining, pre_is_over_limit = (bvals[6] == "true")
        }

        if compare_int(remaining, amount) < 0 then
            if overage_policy == "REJECT" then
                return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = amount})
            end
            -- ALLOW_WITH_OVERDRAFT overdraft limit check is deferred to after
            -- capping phase (zero-limit scopes may reduce effective_amount, so
            -- checking against full `amount` here would be too strict).
        end
    end
end

-- At least one scope must have a budget.
-- Distinguish "wrong unit" from "truly missing": probe known units for each affected scope.
-- If any scope has a budget at a different unit, return UNIT_MISMATCH with the stored unit(s)
-- so the client can self-correct. Otherwise return BUDGET_NOT_FOUND.
if #budgeted_scopes == 0 then
    if units_csv ~= "" then
        for _, scope in ipairs(affected_scopes) do
            local expected_units = {}
            for unit_alt in string.gmatch(units_csv, "[^,]+") do
                if unit_alt ~= unit then
                    if redis.call('EXISTS', "budget:" .. scope .. ":" .. unit_alt) == 1 then
                        table.insert(expected_units, unit_alt)
                    end
                end
            end
            if #expected_units > 0 then
                return cjson.encode({
                    error = "UNIT_MISMATCH",
                    scope = scope,
                    requested_unit = unit,
                    expected_units = expected_units
                })
            end
        end
    end
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
        capped = min_int(capped, max_int(cached.remaining, "0"))
    end
    effective_amount = capped
    -- Mark only scopes that individually could not cover the full amount.
    if compare_int(effective_amount, amount) < 0 then
        for _, scope in ipairs(budgeted_scopes) do
            if compare_int(scope_budget_cache[scope].remaining, amount) < 0 then
                local budget_key = "budget:" .. scope .. ":" .. unit
                redis.call('HSET', budget_key, 'is_over_limit', 'true')
            end
        end
    end
elseif overage_policy == "ALLOW_WITH_OVERDRAFT" then
    -- Spec: "If overdraft_limit is absent or 0, behaves as ALLOW_IF_AVAILABLE."
    -- Phase 1: cap effective_amount from zero-limit scopes (floor 0).
    -- Uses cached values from validation phase
    for _, scope in ipairs(budgeted_scopes) do
        local cached = scope_budget_cache[scope]
        if compare_int(cached.overdraft_limit, "0") == 0
           and compare_int(cached.remaining, effective_amount) < 0 then
            effective_amount = min_int(effective_amount, max_int(cached.remaining, "0"))
        end
    end
    -- Phase 2: check non-zero scopes against effective_amount (fail fast)
    -- Uses cached values from validation phase
    for _, scope in ipairs(budgeted_scopes) do
        local cached = scope_budget_cache[scope]
        if compare_int(cached.overdraft_limit, "0") > 0
           and compare_int(cached.remaining, effective_amount) < 0 then
            local funded = max_int(cached.remaining, "0")
            local deficit = subtract_int(effective_amount, funded)
            if compare_int(add_int(cached.debt, deficit), cached.overdraft_limit) > 0 then
                return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope,
                    current_debt = cached.debt, deficit = deficit, overdraft_limit = cached.overdraft_limit})
            end
        end
    end
    -- Mark over-limit on zero-limit scopes if full amount couldn't be covered
    if compare_int(effective_amount, amount) < 0 then
        for _, scope in ipairs(budgeted_scopes) do
            if compare_int(scope_budget_cache[scope].overdraft_limit, "0") == 0
               and compare_int(scope_budget_cache[scope].remaining, amount) < 0 then
                local budget_key = "budget:" .. scope .. ":" .. unit
                redis.call('HSET', budget_key, 'is_over_limit', 'true')
            end
        end
    end
end

for _, scope in ipairs(budgeted_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. unit
    local cached = scope_budget_cache[scope]

    if overage_policy == "ALLOW_WITH_OVERDRAFT"
       and compare_int(cached.remaining, effective_amount) < 0 then
        -- Spec NORMATIVE: remaining = allocated - spent - reserved - debt (can go negative)
        -- spent tracks only the funded portion; debt tracks the unfunded portion.
        -- When remaining is already negative (prior overdraft), the funded portion is 0,
        -- not negative — otherwise spent would decrease and debt would over-count.
        local funded = max_int(cached.remaining, "0")
        local deficit = subtract_int(effective_amount, funded)
        redis.call('HINCRBY', budget_key, 'remaining', negate_int(effective_amount))
        redis.call('HINCRBY', budget_key, 'spent', funded)
        redis.call('HINCRBY', budget_key, 'debt', deficit)
        scope_debt_incurred[scope] = deficit
        local new_debt = add_int(cached.debt, deficit)
        if compare_int(cached.overdraft_limit, "0") > 0
           and compare_int(new_debt, cached.overdraft_limit) > 0 then
            redis.call('HSET', budget_key, 'is_over_limit', 'true')
        end
    else
        redis.call('HINCRBY', budget_key, 'remaining', negate_int(effective_amount))
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
        remaining = normalize_int(b[1] or "0"),
        reserved = normalize_int(b[2] or "0"),
        spent = normalize_int(b[3] or "0"),
        allocated = normalize_int(b[4] or "0"),
        debt = normalize_int(b[5] or "0"),
        overdraft_limit = normalize_int(b[6] or "0"),
        is_over_limit = (b[7] == "true"),
        debt_incurred = scope_debt_incurred[scope] or "0",
        pre_remaining = cached.pre_remaining or "0",
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
-- Spec: charged is present when capping occurred (ALLOW_IF_AVAILABLE, or
-- ALLOW_WITH_OVERDRAFT with overdraft_limit=0 which behaves as ALLOW_IF_AVAILABLE).
local result = {
    event_id = event_id,
    status = "APPLIED",
    balances = balances
}
if compare_int(effective_amount, amount) < 0 then
    result.charged = effective_amount
end
local result_json = cjson.encode(result)

-- Store idempotency mapping and original response payload (expire after 7 days).
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":event:" .. idempotency_key
    -- Durable source for exact replay when the fast response key is lost.
    -- The event hash outlives the seven-day idempotency mapping (30 days).
    redis.call('HSET', event_key, 'event_response_json', result_json)
    redis.call('PSETEX', idem_key, 604800000, event_id)
    redis.call('PSETEX', idem_key .. ':response', 604800000, result_json)
    -- Store payload hash for idempotency mismatch detection (spec MUST)
    if payload_hash ~= "" then
        redis.call('PSETEX', idem_key .. ':hash', 604800000, payload_hash)
    end
end

return result_json
