-- Cycles Protocol v0.1.23 - Extend TTL Lua Script
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local extend_by_ms = tonumber(ARGV[2])
local idempotency_key = ARGV[3]
local tenant = ARGV[4]

-- Idempotency: replay prior extend result if same (tenant, key) seen before
if idempotency_key ~= "" and idempotency_key ~= nil and tenant ~= "" and tenant ~= nil then
    local idem_key = "idem:" .. tenant .. ":extend:" .. idempotency_key
    local cached = redis.call('GET', idem_key)
    if cached then
        return cached
    end
end

local reservation_key = "reservation:res_" .. reservation_id

if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

local state = redis.call('HGET', reservation_key, 'state')
if state ~= "ACTIVE" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = state})
end

local current_expires_at = tonumber(redis.call('HGET', reservation_key, 'expires_at'))
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

-- Spec NORMATIVE: extend only allowed when server time <= expires_at_ms
if now > current_expires_at then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

local new_expires_at = current_expires_at + extend_by_ms

redis.call('HSET', reservation_key, 'expires_at', new_expires_at)
redis.call('ZADD', 'reservation:ttl', new_expires_at, reservation_id)

local result = cjson.encode({
    reservation_id = reservation_id,
    expires_at_ms = new_expires_at,
    extended_at = now
})

-- Store idempotency result (TTL = the extension duration)
if idempotency_key ~= "" and idempotency_key ~= nil and tenant ~= "" and tenant ~= nil then
    local idem_key = "idem:" .. tenant .. ":extend:" .. idempotency_key
    redis.call('SET', idem_key, result)
    redis.call('PEXPIRE', idem_key, extend_by_ms)
end

return result
