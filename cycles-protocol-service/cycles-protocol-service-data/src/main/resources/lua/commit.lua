-- Cycles Protocol v0.1.23 - Commit Lua Script
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

-- Check reservation exists
if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

-- Get reservation data
local state = redis.call('HGET', reservation_key, 'state')
local estimate_amount = tonumber(redis.call('HGET', reservation_key, 'estimate_amount'))
local estimate_unit = redis.call('HGET', reservation_key, 'estimate_unit')
local affected_scopes_json = redis.call('HGET', reservation_key, 'affected_scopes')
-- Use budgeted_scopes (scopes that actually had budgets at reserve time) for mutations;
-- fall back to affected_scopes for backward compatibility with pre-existing reservations.
local budgeted_scopes_json = redis.call('HGET', reservation_key, 'budgeted_scopes')
local stored_idempotency_key = redis.call('HGET', reservation_key, 'committed_idempotency_key')
local overage_policy = redis.call('HGET', reservation_key, 'overage_policy') or "REJECT"

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
            'charged_amount', 'debt_incurred', 'estimate_amount', 'estimate_unit')
        return cjson.encode({
            reservation_id = reservation_id,
            state = "COMMITTED",
            charged = tonumber(idem_vals[1]),
            debt_incurred = tonumber(idem_vals[2] or 0),
            estimate_amount = tonumber(idem_vals[3]),
            estimate_unit = idem_vals[4]
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
local current_expires_at = redis.call('HGET', reservation_key, 'expires_at')
if not current_expires_at then
    return cjson.encode({error = "RESERVATION_EXPIRATION_NOT_FOUND"})
end
current_expires_at = tonumber(current_expires_at)
local grace_ms = tonumber(redis.call('HGET', reservation_key, 'grace_ms') or 0)

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- Spec: commit allowed until expires_at_ms + grace_period_ms
if now > current_expires_at + grace_ms then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

-- Parse scopes: use budgeted_scopes for budget mutations (only scopes with actual budgets)
local affected_scopes = cjson.decode(budgeted_scopes_json or affected_scopes_json)

-- Calculate delta (actual - estimate)
local delta = actual_amount - estimate_amount
local charged_amount = actual_amount
local total_debt_incurred = 0

-- Handle overage
if delta > 0 then
    if overage_policy == "REJECT" then
        return cjson.encode({error = "BUDGET_EXCEEDED", message = "Actual exceeds estimate and policy is REJECT"})
    elseif overage_policy == "ALLOW_IF_AVAILABLE" then
        -- Check if delta available in all scopes
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
            if remaining < delta then
                return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope})
            end
        end
        -- Charge delta from remaining
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            redis.call('HINCRBY', budget_key, 'remaining', -delta)
            redis.call('HINCRBY', budget_key, 'spent', delta)
        end
    elseif overage_policy == "ALLOW_WITH_OVERDRAFT" then
        -- Check all scopes first (fail fast, no mutations yet) to avoid partial-state corruption.
        -- Cache values from fail-fast loop for reuse in mutation loop.
        local scope_budget_cache = {}
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local vals = redis.call('HMGET', budget_key, 'remaining', 'debt', 'overdraft_limit')
            local remaining = tonumber(vals[1] or 0)
            local current_debt = tonumber(vals[2] or 0)
            local overdraft_limit = tonumber(vals[3] or 0)
            scope_budget_cache[scope] = {remaining = remaining, debt = current_debt, overdraft_limit = overdraft_limit}

            if remaining < delta then
                local funded = math.max(remaining, 0)
                local deficit = delta - funded
                if current_debt + deficit > overdraft_limit then
                    return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope,
                        current_debt = current_debt, deficit = deficit, overdraft_limit = overdraft_limit})
                end
            end
        end
        -- All checks passed - apply mutations using cached values
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local cached = scope_budget_cache[scope]
            local remaining = cached.remaining
            local current_debt = cached.debt
            local overdraft_limit = cached.overdraft_limit

            if remaining >= delta then
                -- Sufficient remaining - charge normally
                redis.call('HINCRBY', budget_key, 'remaining', -delta)
                redis.call('HINCRBY', budget_key, 'spent', delta)
            else
                -- Create debt for the shortfall
                -- Spec NORMATIVE: remaining = allocated - spent - reserved - debt (can go negative)
                -- spent tracks only the funded portion; debt tracks the unfunded portion.
                -- When remaining is already negative (prior overdraft), the funded portion is 0,
                -- not negative — otherwise spent would decrease and debt would over-count.
                local funded = math.max(remaining, 0)
                local deficit = delta - funded
                redis.call('HINCRBY', budget_key, 'remaining', -delta)
                redis.call('HINCRBY', budget_key, 'spent', funded)
                redis.call('HINCRBY', budget_key, 'debt', deficit)
                total_debt_incurred = total_debt_incurred + deficit

                -- Set is_over_limit once cumulative debt reaches the overdraft ceiling
                local new_debt = current_debt + deficit
                if overdraft_limit > 0 and new_debt > overdraft_limit then
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
--     Overage block already ran: spent += delta (the extra portion beyond estimate).
--     This loop: spent += estimate_amount (the base portion).
--     Net spent = delta + estimate_amount = (actual - estimate) + estimate = actual_amount. ✓
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
