-- Cycles Protocol v0.1.22 - Extend TTL Lua Script
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local additional_ttl_ms = tonumber(ARGV[2])
local idempotency_key = ARGV[3]

local reservation_key = "reservation:res_" .. reservation_id

if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

local state = redis.call('HGET', reservation_key, 'state')
if state ~= "RESERVED" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = state})
end

local current_expires_at = tonumber(redis.call('HGET', reservation_key, 'expires_at'))
local grace_ms = tonumber(redis.call('HGET', reservation_key, 'grace_ms'))
local new_expires_at = current_expires_at + additional_ttl_ms
local now = tonumber(redis.call('TIME')[1]) * 1000

redis.call('HSET', reservation_key, 'expires_at', new_expires_at)
--redis.call('PEXPIRE', reservation_key, (new_expires_at - now) + grace_ms)

redis.call('ZADD', 'reservation:ttl', new_expires_at, reservation_id)

return cjson.encode({
    reservation_id = reservation_id,
    new_expires_at = new_expires_at,
    extended_at = now
})
