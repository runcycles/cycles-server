-- Cycles Protocol v0.1.25 - Commit Lua Script
-- Atomically commit actual spend with overdraft support
--local cjson = require("cjson")

-- debt_incurred is a commit-level aggregate used by the int64 webhook event
-- schema. A commit can touch multiple hierarchical budgets, so the sum of
-- otherwise valid per-scope int64 deficits can exceed signed int64 even though
-- every ledger mutation remains in range. Saturate only this observability
-- aggregate; the per-scope debt and balance fields below remain exact.
local MAX_INT64 = "9223372036854775807"

local function add_debt_aggregate(total, deficit)
    return min_int(add_int(total, deficit), MAX_INT64)
end

local reservation_id = ARGV[1]
local actual_amount = normalize_int(ARGV[2])
local actual_unit = ARGV[3]
local idempotency_key = ARGV[4]
local payload_hash    = ARGV[5] or ""
local metrics_json    = ARGV[6] or ""
local metadata_json   = ARGV[7] or ""

if not actual_amount or compare_int(actual_amount, "0") < 0 then
    return cjson.encode({error = "INVALID_REQUEST", message = "actual amount must be a non-negative int64"})
end

local reservation_key = "reservation:res_" .. reservation_id

-- Fetch all reservation data in one round-trip (also serves as existence check)
local rdata = redis.call('HMGET', reservation_key,
    'state', 'estimate_amount', 'estimate_unit', 'affected_scopes',
    'budgeted_scopes', 'committed_idempotency_key', 'overage_policy',
    'expires_at', 'grace_ms', 'scope_path', 'tenant')

local state = rdata[1]
if not state then
    return cjson.encode({error = "NOT_FOUND"})
end

local estimate_amount = normalize_int(rdata[2])
local estimate_unit = rdata[3]
local affected_scopes_json = rdata[4]
-- Use budgeted_scopes (scopes that actually had budgets at reserve time) for mutations;
-- fall back to affected_scopes for backward compatibility with pre-existing reservations.
local budgeted_scopes_json = rdata[5]
local stored_idempotency_key = rdata[6]
local overage_policy = rdata[7] or "ALLOW_IF_AVAILABLE"
local scope_path = rdata[10] or ""

-- Idempotent replay: same (state, key) as the original commit. Replay
-- handling stays FIRST — ahead of the closed-tenant guard (Rule 2(b):
-- a replay re-observes a commit that succeeded before any CLOSED flip)
-- and ahead of the reservation-state errors. A DIFFERENT key on an
-- already-committed reservation is NOT a replay: it falls through to the
-- closed-tenant guard, then to the state checks below (same
-- RESERVATION_FINALIZED response as before when the tenant is open).
if state == "COMMITTED" and idempotency_key ~= "" and idempotency_key ~= nil
   and stored_idempotency_key == idempotency_key then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('HGET', reservation_key, 'committed_payload_hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cjson.encode({
            reservation_id = reservation_id,
            replay = true,
            response_snapshot = redis.call('HGET', reservation_key, 'commit_response_json')
        })
end

-- Governance CASCADE SEMANTICS Rule 2: reject commit when the owning tenant
-- (from the reservation hash — authoritative owner) is CLOSED, regardless of
-- the reservation's own state ("regardless of that child's own current
-- status" — spec PR runcycles/cycles-protocol#125 ERROR SEMANTICS: the
-- closed-tenant rejection takes precedence over reservation-state errors),
-- even before the close cascade reaches this reservation or revokes API
-- keys (Mode B invariant (a)). In-script like the reserve.lua budget status
-- guards, so the guard is atomic with the budget mutations. Sits after the
-- idempotent-replay block above (a replay re-observes a commit that
-- succeeded BEFORE the flip) and before every reservation-state error.
-- Absent tenant record (runtime-only deployment) = no restriction; a
-- PRESENT record that cannot be decoded into an object with a valid
-- TenantStatus (ACTIVE|SUSPENDED|CLOSED) FAILS CLOSED (INTERNAL_ERROR,
-- no mutation) — matching the admin
-- plane's TenantRepository, which propagates parse failures instead of
-- treating a corrupt governance record as an open tenant.
local owner_tenant = rdata[11]
if owner_tenant and owner_tenant ~= "" then
    local tenant_json = redis.call('GET', 'tenant:' .. owner_tenant)
    if tenant_json then
        local ok_tenant, tenant_rec = pcall(cjson.decode, tenant_json)
        if not ok_tenant or type(tenant_rec) ~= 'table' or type(tenant_rec['status']) ~= 'string' then
            return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed tenant record: tenant:" .. owner_tenant})
        end
        if tenant_rec['status'] == 'CLOSED' then
            return cjson.encode({error = "TENANT_CLOSED", tenant = owner_tenant})
        end
        if tenant_rec['status'] ~= 'ACTIVE' and tenant_rec['status'] ~= 'SUSPENDED' then
            -- The governance TenantStatus enum is a closed set (ACTIVE|
            -- SUSPENDED|CLOSED) and the cascade revision explicitly
            -- introduces no new status values as a wire-compat guarantee,
            -- so an unknown status (e.g. "CLOZED", lowercase "closed")
            -- cannot be a legitimate future value under the current
            -- contract - it is corruption. Fail closed like the other
            -- malformed shapes.
            return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed tenant record: tenant:" .. owner_tenant})
        end
    end
end

-- Check if expired, committed, or released
if state == "EXPIRED" then
    return cjson.encode({error = "RESERVATION_EXPIRED", state = state})
elseif state ~= "ACTIVE" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = state})
end

if not estimate_amount or not estimate_unit then
    return cjson.encode({error = "INTERNAL_ERROR", message = "Reservation missing estimate data"})
end

if overage_policy ~= "REJECT"
   and overage_policy ~= "ALLOW_IF_AVAILABLE"
   and overage_policy ~= "ALLOW_WITH_OVERDRAFT" then
    return cjson.encode({error = "INVALID_REQUEST", message = "Invalid overage_policy: " .. tostring(overage_policy)})
end

-- Check unit matches
if actual_unit ~= estimate_unit then
    return cjson.encode({error = "UNIT_MISMATCH"})
end

--expiration verify logic--
-- Use expires_at and grace_ms already fetched in the initial HMGET
local current_expires_at = rdata[8]
if not current_expires_at then
    return cjson.encode({error = "RESERVATION_EXPIRATION_NOT_FOUND"})
end
current_expires_at = tonumber(current_expires_at)
local grace_ms = tonumber(rdata[9] or 0)

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- Spec: commit allowed until expires_at_ms + grace_period_ms
if now > current_expires_at + grace_ms then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

-- Parse scopes: use budgeted_scopes for budget mutations (only scopes with actual budgets)
if not (budgeted_scopes_json or affected_scopes_json) then
    return cjson.encode({error = "INTERNAL_ERROR", message = "Reservation missing scope data"})
end
local ok, affected_scopes = pcall(cjson.decode, budgeted_scopes_json or affected_scopes_json)
if not ok then
    return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed scope JSON in reservation"})
end

-- Calculate delta (actual - estimate)
local delta = subtract_int(actual_amount, estimate_amount)
local charged_amount = actual_amount
local total_debt_incurred = "0"
local scope_debt_incurred = {}  -- per-scope debt delta for event data
-- Pre-mutation state for transition detection. Populated from existing reads
-- in overage paths (no extra HMGET). For delta <= 0, remaining can only stay
-- same or increase, so transitions to exhausted/over-limit are impossible.
local pre_budget_state = {}

-- Handle overage
if compare_int(delta, "0") > 0 then
    if overage_policy == "REJECT" then
        return cjson.encode({error = "BUDGET_EXCEEDED", message = "Actual exceeds estimate and policy is REJECT"})
    elseif overage_policy == "ALLOW_IF_AVAILABLE" then
        -- Two-phase: determine capped delta across all scopes, then mutate.
        -- Cap delta to the minimum available remaining across all scopes (floor 0).
        -- This ensures commits always succeed — the action already happened.
        local capped_delta = delta
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local cvals = redis.call('HMGET', budget_key, 'remaining', 'is_over_limit')
            local remaining = normalize_int(cvals[1] or "0")
            pre_budget_state[scope] = {remaining = remaining, is_over_limit = (cvals[2] == "true")}
            capped_delta = min_int(capped_delta, max_int(remaining, "0"))
        end

        -- Charge the capped delta from remaining
        if compare_int(capped_delta, "0") > 0 then
            for _, scope in ipairs(affected_scopes) do
                local budget_key = "budget:" .. scope .. ":" .. actual_unit
                redis.call('HINCRBY', budget_key, 'remaining', negate_int(capped_delta))
                redis.call('HINCRBY', budget_key, 'spent', capped_delta)
            end
        end

        -- Adjust charged_amount: estimate + whatever overage we could cover
        charged_amount = add_int(estimate_amount, capped_delta)

        -- Mark only scopes that individually could not cover the full delta.
        -- The minimum scope caps the operation, but healthier descendants must
        -- remain eligible for reservations after their limiting ancestor recovers.
        if compare_int(capped_delta, delta) < 0 then
            for _, scope in ipairs(affected_scopes) do
                if compare_int(pre_budget_state[scope].remaining, delta) < 0 then
                    local budget_key = "budget:" .. scope .. ":" .. actual_unit
                    redis.call('HSET', budget_key, 'is_over_limit', 'true')
                end
            end
        end
    elseif overage_policy == "ALLOW_WITH_OVERDRAFT" then
        -- Spec: "If overdraft_limit is absent or 0, behaves as ALLOW_IF_AVAILABLE."
        -- Three-phase: (1) cache + cap from zero-limit scopes, (2) check non-zero
        -- scopes against capped_delta, (3) mutate.
        local scope_budget_cache = {}
        local capped_delta = delta

        -- Phase 1: cache all scopes, cap delta from zero-limit scopes
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local vals = redis.call('HMGET', budget_key, 'remaining', 'debt', 'overdraft_limit', 'is_over_limit')
            local remaining = normalize_int(vals[1] or "0")
            local current_debt = normalize_int(vals[2] or "0")
            local overdraft_limit = normalize_int(vals[3] or "0")
            scope_budget_cache[scope] = {remaining = remaining, debt = current_debt, overdraft_limit = overdraft_limit}
            pre_budget_state[scope] = {remaining = remaining, is_over_limit = (vals[4] == "true")}

            if compare_int(overdraft_limit, "0") == 0 and compare_int(remaining, delta) < 0 then
                -- Spec: behaves as ALLOW_IF_AVAILABLE — cap delta to available (floor 0)
                capped_delta = min_int(capped_delta, max_int(remaining, "0"))
            end
        end

        -- Phase 2: check non-zero scopes against capped_delta (fail fast, no mutations)
        for _, scope in ipairs(affected_scopes) do
            local cached = scope_budget_cache[scope]
            if compare_int(cached.overdraft_limit, "0") > 0
               and compare_int(cached.remaining, capped_delta) < 0 then
                local funded = max_int(cached.remaining, "0")
                local deficit = subtract_int(capped_delta, funded)
                if compare_int(add_int(cached.debt, deficit), cached.overdraft_limit) > 0 then
                    return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope,
                        current_debt = cached.debt, deficit = deficit, overdraft_limit = cached.overdraft_limit})
                end
            end
        end
        -- Apply mutations using cached values and capped_delta as effective overage.
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local cached = scope_budget_cache[scope]

            if compare_int(cached.remaining, capped_delta) >= 0 then
                -- Sufficient remaining - charge normally
                redis.call('HINCRBY', budget_key, 'remaining', negate_int(capped_delta))
                redis.call('HINCRBY', budget_key, 'spent', capped_delta)
            else
                -- Create debt for the shortfall
                -- Spec NORMATIVE: remaining = allocated - spent - reserved - debt (can go negative)
                -- spent tracks only the funded portion; debt tracks the unfunded portion.
                -- When remaining is already negative (prior overdraft), the funded portion is 0,
                -- not negative — otherwise spent would decrease and debt would over-count.
                local funded = max_int(cached.remaining, "0")
                local deficit = subtract_int(capped_delta, funded)
                redis.call('HINCRBY', budget_key, 'remaining', negate_int(capped_delta))
                redis.call('HINCRBY', budget_key, 'spent', funded)
                redis.call('HINCRBY', budget_key, 'debt', deficit)
                total_debt_incurred = add_debt_aggregate(total_debt_incurred, deficit)
                scope_debt_incurred[scope] = deficit

                -- Set is_over_limit once cumulative debt reaches the overdraft ceiling
                local new_debt = add_int(cached.debt, deficit)
                if compare_int(cached.overdraft_limit, "0") > 0
                   and compare_int(new_debt, cached.overdraft_limit) > 0 then
                    redis.call('HSET', budget_key, 'is_over_limit', 'true')
                end
            end
        end
        -- Adjust charged_amount: estimate + effective overage
        charged_amount = add_int(estimate_amount, capped_delta)
        -- Mark over-limit on zero-limit scopes if full delta couldn't be covered
        if compare_int(capped_delta, delta) < 0 then
            for _, scope in ipairs(affected_scopes) do
                if compare_int(scope_budget_cache[scope].overdraft_limit, "0") == 0
                   and compare_int(scope_budget_cache[scope].remaining, delta) < 0 then
                    local budget_key = "budget:" .. scope .. ":" .. actual_unit
                    redis.call('HSET', budget_key, 'is_over_limit', 'true')
                end
            end
        end
    end
end

-- Release reservation from all scopes and record base spend.
--
-- Spent accounting uses a two-part accumulation so that the total always equals actual_amount:
--
--   delta < 0  (actual < estimate):
--     overage block is skipped entirely.
--     This loop: remaining += |delta|, spent += actual_amount.
--     Net spent = actual_amount. ✓
--
--   delta == 0 (exact match):
--     overage block is skipped (condition is delta > 0).
--     This loop: spent += estimate_amount == actual_amount.
--     Net spent = actual_amount. ✓
--
--   delta > 0  (actual > estimate):
--     Overage block already ran: spent += overage_charged (delta or capped_delta).
--     This loop: spent += estimate_amount (the base portion).
--     Net spent = overage_charged + estimate_amount = charged_amount. ✓
--     For ALLOW_IF_AVAILABLE with capped delta: charged_amount = estimate + capped_delta ≤ actual.
--     For full delta (ALLOW_WITH_OVERDRAFT or uncapped): charged_amount = actual_amount.
--
-- Do NOT consolidate this into a single "spent += actual_amount" here — the overage
-- block has already modified `spent` for the delta > 0 cases.
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. actual_unit
    redis.call('HINCRBY', budget_key, 'reserved', negate_int(estimate_amount))

    if compare_int(delta, "0") < 0 then
        -- Return unused portion of the reservation to remaining
        redis.call('HINCRBY', budget_key, 'remaining', negate_int(delta))
        redis.call('HINCRBY', budget_key, 'spent', actual_amount)
    else
        -- Overage block (if any) already charged `delta` to spent above;
        -- charge the base estimate_amount here. Total = delta + estimate = actual.
        redis.call('HINCRBY', budget_key, 'spent', estimate_amount)
    end
end

-- Update reservation (reuse TIME from earlier — no need to call again)
redis.call('HMSET', reservation_key,
    'state', 'COMMITTED',
    'charged_amount', charged_amount,
    'debt_incurred', total_debt_incurred,
    'committed_at', now,
    'committed_idempotency_key', idempotency_key,
    'committed_payload_hash', payload_hash,
    'committed_metrics_json', metrics_json,
    'committed_metadata_json', metadata_json
)

-- Remove from TTL sorted set — reservation is finalized, no expiry sweep needed.
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Set 30-day TTL on terminal reservation hash (audit trail, then auto-cleanup)
redis.call('PEXPIRE', reservation_key, 2592000000)

-- Collect balance snapshots for all budgeted scopes (avoids post-operation Java round-trips)
local balances = {}
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. actual_unit
    local b = redis.call('HMGET', budget_key, 'remaining', 'reserved', 'spent', 'allocated', 'debt', 'overdraft_limit', 'is_over_limit')
    local pre = pre_budget_state[scope] or {}
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
        pre_remaining = pre.remaining or "0",
        pre_is_over_limit = pre.is_over_limit or false
    })
end

local response = cjson.encode({
    reservation_id = reservation_id,
    state = "COMMITTED",
    charged = charged_amount,
    debt_incurred = total_debt_incurred,
    estimate_amount = estimate_amount,
    estimate_unit = estimate_unit,
    affected_scopes_json = affected_scopes_json,
    overage_policy = overage_policy,
    scope_path = scope_path,
    balances = balances
})
-- Store the immutable post-mutation snapshot in the reservation hash before
-- returning. It shares the terminal hash TTL and survives body-cache misses.
redis.call('HSET', reservation_key, 'commit_response_state', 'PENDING')
if idempotency_key ~= "" and idempotency_key ~= nil then
    redis.call('HSET', reservation_key, 'commit_response_json', response)
end
return response
