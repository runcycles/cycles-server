-- Cycles Protocol v0.1.23 - Extend TTL Lua Script
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local extend_by_ms = tonumber(ARGV[2])
local idempotency_key = ARGV[3]
local tenant = ARGV[4]
local payload_hash    = ARGV[5] or ""
local metadata_json   = ARGV[6] or ""

-- Idempotency: replay prior extend result if same (tenant, key) seen before
if idempotency_key ~= "" and idempotency_key ~= nil and tenant ~= "" and tenant ~= nil then
    local idem_key = "idem:" .. tenant .. ":extend:" .. reservation_id .. ":" .. idempotency_key
    local cached = redis.call('GET', idem_key)
    if cached then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('GET', idem_key .. ':hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cached
    end
end

local reservation_key = "reservation:res_" .. reservation_id

if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

local state = redis.call('HGET', reservation_key, 'state')
if state == "EXPIRED" then
    return cjson.encode({error = "RESERVATION_EXPIRED", state = state})
elseif state ~= "ACTIVE" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = state})
end

local current_expires_at = tonumber(redis.call('HGET', reservation_key, 'expires_at'))
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- Spec NORMATIVE: extend only allowed when server time <= expires_at_ms
if now > current_expires_at then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

-- Enforce max_reservation_extensions from tenant config
local extension_count = tonumber(redis.call('HGET', reservation_key, 'extension_count') or 0)
local max_extensions = tonumber(redis.call('HGET', reservation_key, 'max_extensions') or 10)
if extension_count >= max_extensions then
    return cjson.encode({error = "MAX_EXTENSIONS_EXCEEDED", message = "Maximum reservation extensions (" .. max_extensions .. ") reached"})
end

local new_expires_at = current_expires_at + extend_by_ms

redis.call('HSET', reservation_key, 'expires_at', new_expires_at)
redis.call('HINCRBY', reservation_key, 'extension_count', 1)
redis.call('ZADD', 'reservation:ttl', new_expires_at, reservation_id)

-- Collect balance snapshots (avoids post-operation Java round-trips)
local affected_scopes_json = redis.call('HGET', reservation_key, 'affected_scopes')
local budgeted_scopes_json = redis.call('HGET', reservation_key, 'budgeted_scopes')
local estimate_unit = redis.call('HGET', reservation_key, 'estimate_unit')

local scope_list_json = budgeted_scopes_json or affected_scopes_json
local balances = {}
if scope_list_json and estimate_unit then
    local ok, scopes = pcall(cjson.decode, scope_list_json)
    if not ok then scopes = {} end
    for _, scope in ipairs(scopes) do
        local budget_key = "budget:" .. scope .. ":" .. estimate_unit
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
end

local result = cjson.encode({
    reservation_id = reservation_id,
    expires_at_ms = new_expires_at,
    extended_at = now,
    estimate_unit = estimate_unit,
    balances = balances
})

-- Store extend metadata in reservation hash if provided
if metadata_json ~= "" then
    redis.call('HSET', reservation_key, 'extend_metadata_json', metadata_json)
end

-- Store idempotency result (TTL = remaining reservation lifetime after extension)
if idempotency_key ~= "" and idempotency_key ~= nil and tenant ~= "" and tenant ~= nil then
    local idem_key = "idem:" .. tenant .. ":extend:" .. reservation_id .. ":" .. idempotency_key
    local idem_ttl = new_expires_at - now
    if idem_ttl > 0 then
        redis.call('PSETEX', idem_key, idem_ttl, result)
        -- Store payload hash for idempotency mismatch detection (spec MUST)
        if payload_hash ~= "" then
            redis.call('PSETEX', idem_key .. ':hash', idem_ttl, payload_hash)
        end
    end
end

return result
