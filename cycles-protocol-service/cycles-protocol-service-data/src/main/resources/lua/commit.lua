-- Cycles Protocol v0.1.22 - Commit Lua Script
-- Atomically commit actual spend with overdraft support
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local actual_amount = tonumber(ARGV[2])
local actual_unit = ARGV[3]
local overage_policy = ARGV[4] or "REJECT"
local idempotency_key = ARGV[5]

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
local stored_idempotency_key = redis.call('HGET', reservation_key, 'committed_idempotency_key')

-- Check if already committed (idempotent)
if state == "COMMITTED" then
    if stored_idempotency_key == idempotency_key then
        local charged = tonumber(redis.call('HGET', reservation_key, 'charged_amount'))
        local debt_incurred = tonumber(redis.call('HGET', reservation_key, 'debt_incurred') or 0)
        return cjson.encode({
            reservation_id = reservation_id,
            state = "COMMITTED",
            charged = charged,
            debt_incurred = debt_incurred
        })
    else
        return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
    end
end

-- Check if expired or released
if state ~= "RESERVED" then
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

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

if now > current_expires_at then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

-- Parse affected scopes
local affected_scopes = cjson.decode(affected_scopes_json)

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
        -- v0.1.22: Overdraft logic
        for _, scope in ipairs(affected_scopes) do
            local budget_key = "budget:" .. scope .. ":" .. actual_unit
            local remaining = tonumber(redis.call('HGET', budget_key, 'remaining') or 0)
            local current_debt = tonumber(redis.call('HGET', budget_key, 'debt') or 0)
            local overdraft_limit = tonumber(redis.call('HGET', budget_key, 'overdraft_limit') or 0)
            
            if remaining >= delta then
                -- Sufficient remaining - charge normally
                redis.call('HINCRBY', budget_key, 'remaining', -delta)
                redis.call('HINCRBY', budget_key, 'spent', delta)
            else
                -- Need overdraft
                local deficit = delta - remaining
                
                -- Check if overdraft allowed
                if current_debt + deficit > overdraft_limit then
                    return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope, 
                        current_debt = current_debt, deficit = deficit, overdraft_limit = overdraft_limit})
                end
                
                -- Create debt
                redis.call('HSET', budget_key, 'remaining', 0)
                redis.call('HINCRBY', budget_key, 'spent', delta)
                redis.call('HINCRBY', budget_key, 'debt', deficit)
                total_debt_incurred = total_debt_incurred + deficit
                
                -- Check if now over-limit
                local new_debt = current_debt + deficit
                if new_debt > overdraft_limit then
                    redis.call('HSET', budget_key, 'is_over_limit', 'true')
                end
            end
        end
    end
end

-- Release reservation from all scopes
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. actual_unit
    redis.call('HINCRBY', budget_key, 'reserved', -estimate_amount)

    -- If actual < estimate, return unused to remaining and spent is handled in a way that only used funds are added
    if delta < 0 then
        redis.call('HINCRBY', budget_key, 'remaining', -delta)
        redis.call('HINCRBY', budget_key, 'spent', -delta)
    else
        redis.call('HINCRBY', budget_key, 'spent', estimate_amount)
    end
end

-- Update reservation
local nowCommit = tonumber(redis.call('TIME')[1]) * 1000
redis.call('HMSET', reservation_key,
    'state', 'COMMITTED',
    'charged_amount', charged_amount,
    'debt_incurred', total_debt_incurred,
    'committed_at', nowCommit,
    'committed_idempotency_key', idempotency_key
)

return cjson.encode({
    reservation_id = reservation_id,
    state = "COMMITTED",
    charged = charged_amount,
    debt_incurred = total_debt_incurred
})
