-- Cycles Protocol v0.1.24 - Commit Lua Script
-- Atomically commit actual spend with overdraft support
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local actual_amount = tonumber(ARGV[2])
local actual_unit = ARGV[3]
local idempotency_key = ARGV[4]
local payload_hash    = ARGV[5] or ""
local metrics_json    = ARGV[6] or ""
local metadata_json   = ARGV[7] or ""

local reservation_key = "reservation:res_" .. reservation_id

-- Fetch all reservation data in one round-trip (also serves as existence check)
local rdata = redis.call('HMGET', reservation_key,
    'state', 'estimate_amount', 'estimate_unit', 'affected_scopes',
    'budgeted_scopes', 'committed_idempotency_key', 'overage_policy',
    'expires_at', 'grace_ms')

local state = rdata[1]
if not state then
    return cjson.encode({error = "NOT_FOUND"})
end

local estimate_amount = tonumber(rdata[2])
local estimate_unit = rdata[3]
local affected_scopes_json = rdata[4]
-- Use budgeted_scopes (scopes that actually had budgets at reserve time) for mutations;
-- fall back to affected_scopes for backward compatibility with pre-existing reservations.
local budgeted_scopes_json = rdata[5]
local stored_idempotency_key = rdata[6]
local overage_policy = rdata[7] or "ALLOW_IF_AVAILABLE"

-- Check if already committed (idempotent replay or finalized)
if state == "COMMITTED" then
    if idempotency_key ~= "" and idempotency_key ~= nil
       and stored_idempotency_key == idempotency_key then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('HGET', reservation_key, 'committed_payload_hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        local idem_vals = redis.call('HMGET', reservation_key,
            'charged_amount', 'debt_incurred', 'estimate_amount', 'estimate_unit', 'affected_scopes')
        -- Spec MUST: replay returns original successful response payload including balances.
        local replay_balances = {}
        if idem_vals[5] then
            local scopes = cjson.decode(idem_vals[5])
            for _, scope in ipairs(scopes) do
                local budget_key = "budget:" .. scope .. ":" .. idem_vals[4]
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
        end
        return cjson.encode({
            reservation_id = reservation_id,
            state = "COMMITTED",
            charged = tonumber(idem_vals[1] or 0),
            debt_incurred = tonumber(idem_vals[2] or 0),
            estimate_amount = tonumber(idem_vals[3] or 0),
            estimate_unit = idem_vals[4],
            affected_scopes_json = idem_vals[5],
            balances = replay_balances
        })
    else
        -- Different key on already-committed reservation = finalized, not idempotency mismatch
        return cjson.encode({error = "RESERVATION_FINALIZED", state = "COMMITTED"})
    end
end

-- Check if expired, committed, or released
if state == "EXPIRED" then
    return cjson.encode({error = "RESERVATION_EXPIRED", state = state})
elseif state ~= "ACTIVE" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = state})
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
local delta = actual_amount - estimate_amount
local charged_amount = actual_amount
local total_debt_incurred = 0

-- Handle overage
if delta > 0 then
    if overage_policy == "REJECT" then
        return cjson.encode({error = "BUDGET_EXCEEDED", message = "Actual exceeds estimate and policy is REJECT"})
    elseif overage_policy == "ALLOW_IF_AVAILABLE" then
        -- Two-phase: determine capped delta across all scopes, then mutate.
        -- Cap delta to the minimum available remaining across all scopes (floor 0).
        -- This ensures commits always succeed — the action already happened.
        local capped_delta = delta
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
            capped_delta = math.min(capped_delta, math.max(remaining, 0))
        end

        -- Charge the capped delta from remaining
        if capped_delta > 0 then
            for _, scope in ipairs(affected_scopes) do
                local budget_key = "budget:" .. scope .. ":" .. actual_unit
                redis.call('HINCRBY', budget_key, 'remaining', -capped_delta)
                redis.call('HINCRBY', budget_key, 'spent', capped_delta)
            end
        end

        -- Adjust charged_amount: estimate + whatever overage we could cover
        charged_amount = estimate_amount + capped_delta

        -- Mark over-limit if we couldn't cover the full delta — blocks future reservations
        if capped_delta < delta then
            for _, scope in ipairs(affected_scopes) do
                local budget_key = "budget:" .. scope .. ":" .. actual_unit
                redis.call('HSET', budget_key, 'is_over_limit', 'true')
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
            local vals = redis.call('HMGET', budget_key, 'remaining', 'debt', 'overdraft_limit')
            local remaining = tonumber(vals[1] or 0)
            local current_debt = tonumber(vals[2] or 0)
            local overdraft_limit = tonumber(vals[3] or 0)
            scope_budget_cache[scope] = {remaining = remaining, debt = current_debt, overdraft_limit = overdraft_limit}

            if overdraft_limit == 0 and remaining < delta then
                -- Spec: behaves as ALLOW_IF_AVAILABLE — cap delta to available (floor 0)
                capped_delta = math.min(capped_delta, math.max(remaining, 0))
            end
        end

        -- Phase 2: check non-zero scopes against capped_delta (fail fast, no mutations)
        for _, scope in ipairs(affected_scopes) do
            local cached = scope_budget_cache[scope]
            if cached.overdraft_limit > 0 and cached.remaining < capped_delta then
                local funded = math.max(cached.remaining, 0)
                local deficit = capped_delta - funded
                if cached.debt + deficit > cached.overdraft_limit then
                    return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope,
                        current_debt = cached.debt, deficit = deficit, overdraft_limit = cached.overdraft_limit})
                end
            end
        end
        -- Apply mutations using cached values and capped_delta as effective overage.
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local cached = scope_budget_cache[scope]

            if cached.remaining >= capped_delta then
                -- Sufficient remaining - charge normally
                redis.call('HINCRBY', budget_key, 'remaining', -capped_delta)
                redis.call('HINCRBY', budget_key, 'spent', capped_delta)
            else
                -- Create debt for the shortfall
                -- Spec NORMATIVE: remaining = allocated - spent - reserved - debt (can go negative)
                -- spent tracks only the funded portion; debt tracks the unfunded portion.
                -- When remaining is already negative (prior overdraft), the funded portion is 0,
                -- not negative — otherwise spent would decrease and debt would over-count.
                local funded = math.max(cached.remaining, 0)
                local deficit = capped_delta - funded
                redis.call('HINCRBY', budget_key, 'remaining', -capped_delta)
                redis.call('HINCRBY', budget_key, 'spent', funded)
                redis.call('HINCRBY', budget_key, 'debt', deficit)
                total_debt_incurred = total_debt_incurred + deficit

                -- Set is_over_limit once cumulative debt reaches the overdraft ceiling
                local new_debt = cached.debt + deficit
                if cached.overdraft_limit > 0 and new_debt > cached.overdraft_limit then
                    redis.call('HSET', budget_key, 'is_over_limit', 'true')
                end
            end
        end
        -- Adjust charged_amount: estimate + effective overage
        charged_amount = estimate_amount + capped_delta
        -- Mark over-limit on zero-limit scopes if full delta couldn't be covered
        if capped_delta < delta then
            for _, scope in ipairs(affected_scopes) do
                if scope_budget_cache[scope].overdraft_limit == 0 then
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
    redis.call('HINCRBY', budget_key, 'reserved', -estimate_amount)

    if delta < 0 then
        -- Return unused portion of the reservation to remaining
        redis.call('HINCRBY', budget_key, 'remaining', -delta)
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
    state = "COMMITTED",
    charged = charged_amount,
    debt_incurred = total_debt_incurred,
    estimate_amount = estimate_amount,
    estimate_unit = estimate_unit,
    affected_scopes_json = affected_scopes_json,
    balances = balances
})
