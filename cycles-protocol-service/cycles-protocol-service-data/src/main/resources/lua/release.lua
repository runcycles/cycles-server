-- Cycles Protocol v0.1.24 - Release Lua Script
--local cjson = require("cjson")

local reservation_id = ARGV[1]
local idempotency_key = ARGV[2]
local payload_hash    = ARGV[3] or ""
local audit_json      = ARGV[4] or ""
local audit_log_id    = ARGV[5] or ""
local audit_tenant    = ARGV[6] or ""
local audit_ttl       = tonumber(ARGV[7] or 0)
local audit_score     = tonumber(ARGV[8] or 0)

local reservation_key = "reservation:res_" .. reservation_id

-- Check reservation exists
if redis.call('EXISTS', reservation_key) == 0 then
    return cjson.encode({error = "NOT_FOUND"})
end

local state = redis.call('HGET', reservation_key, 'state')
local stored_idempotency_key = redis.call('HGET', reservation_key, 'released_idempotency_key')

-- Idempotent replay: same (state, key) as the original release. Replay
-- handling stays FIRST — ahead of the closed-tenant guard (Rule 2(b):
-- a replay re-observes a release that succeeded before any CLOSED flip)
-- and ahead of the reservation-state errors. A DIFFERENT key on an
-- already-released reservation is NOT a replay: it falls through to the
-- closed-tenant guard, then to the state checks below (same
-- RESERVATION_FINALIZED response as before when the tenant is open).
if state == "RELEASED" and idempotency_key ~= "" and idempotency_key ~= nil
   and stored_idempotency_key == idempotency_key then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('HGET', reservation_key, 'released_payload_hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cjson.encode({
            reservation_id = reservation_id,
            replay = true,
            response_snapshot = redis.call('HGET', reservation_key, 'release_response_json')
        })
end

-- Governance CASCADE SEMANTICS Rule 2: reject release when the owning tenant
-- (from the reservation hash — authoritative owner) is CLOSED, regardless of
-- the reservation's own state ("regardless of that child's own current
-- status" — spec PR runcycles/cycles-protocol#125 ERROR SEMANTICS: the
-- closed-tenant rejection takes precedence over reservation-state errors),
-- even before the close cascade reaches this reservation or revokes API
-- keys (Mode B invariant (a)). In-script like the reserve.lua budget status
-- guards, so the guard is atomic with the budget mutations. Sits after the
-- idempotent-replay block above (a replay re-observes a release that
-- succeeded BEFORE the flip) and before every reservation-state error.
-- Absent tenant record (runtime-only deployment) = no restriction; a
-- PRESENT record that cannot be decoded into an object with a valid
-- TenantStatus (ACTIVE|SUSPENDED|CLOSED) FAILS CLOSED (INTERNAL_ERROR,
-- no mutation) — matching the admin
-- plane's TenantRepository, which propagates parse failures instead of
-- treating a corrupt governance record as an open tenant.
local owner_tenant = redis.call('HGET', reservation_key, 'tenant')
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

-- Check if can be released (a different-key attempt on an already-RELEASED
-- reservation lands in the state ~= "ACTIVE" branch: finalized, not
-- idempotency mismatch — same wire response as before the Rule 2 reorder)
if state == "EXPIRED" then
    return cjson.encode({error = "RESERVATION_EXPIRED", state = state})
elseif state ~= "ACTIVE" then
    return cjson.encode({error = "RESERVATION_FINALIZED", state = state})
end

-- Spec: release disallowed beyond expires_at_ms + grace_period_ms
local current_expires_at = tonumber(redis.call('HGET', reservation_key, 'expires_at') or 0)
local grace_ms = tonumber(redis.call('HGET', reservation_key, 'grace_ms') or 0)
local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
if now_ms > current_expires_at + grace_ms then
    return cjson.encode({error = "RESERVATION_EXPIRED"})
end

-- Get reservation data using HMGET for efficiency
local rvals = redis.call('HMGET', reservation_key, 'estimate_amount', 'estimate_unit', 'affected_scopes', 'budgeted_scopes')
local estimate_amount = normalize_int(rvals[1])
local estimate_unit = rvals[2]
local affected_scopes_json = rvals[3]
-- Use budgeted_scopes for budget mutations (only scopes with actual budgets)
local budgeted_scopes_json = rvals[4]

-- Guard against corrupted reservation data
if not estimate_amount or not estimate_unit then
    return cjson.encode({error = "INTERNAL_ERROR", message = "Reservation missing estimate data"})
end
if not (budgeted_scopes_json or affected_scopes_json) then
    return cjson.encode({error = "INTERNAL_ERROR", message = "Reservation missing scope data"})
end
local ok, affected_scopes = pcall(cjson.decode, budgeted_scopes_json or affected_scopes_json)
if not ok then
    return cjson.encode({error = "INTERNAL_ERROR", message = "Malformed scope JSON in reservation"})
end

-- Release from all scopes
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    redis.call('HINCRBY', budget_key, 'reserved', negate_int(estimate_amount))
    redis.call('HINCRBY', budget_key, 'remaining', estimate_amount)
end

-- Update reservation (reuse TIME from earlier — no need to call again)
redis.call('HMSET', reservation_key,
    'state', 'RELEASED',
    'released_at', now_ms,
    'released_idempotency_key', idempotency_key,
    'released_payload_hash', payload_hash
)

-- Admin-on-behalf-of releases have a normative audit requirement. Persist the
-- audit row and both governance-readable indexes inside the same Lua execution
-- as the release so a successful fresh release cannot omit its audit record.
if audit_json ~= "" and audit_log_id ~= "" and audit_tenant ~= "" then
    local audit_key = "audit:log:" .. audit_log_id
    if audit_ttl and audit_ttl > 0 then
        redis.call('SET', audit_key, audit_json, 'EX', audit_ttl)
    else
        redis.call('SET', audit_key, audit_json)
    end
    redis.call('ZADD', "audit:logs:" .. audit_tenant, audit_score, audit_log_id)
    redis.call('ZADD', "audit:logs:_all", audit_score, audit_log_id)
end

-- Remove from TTL sorted set — reservation is finalized, no expiry sweep needed.
redis.call('ZREM', 'reservation:ttl', reservation_id)

-- Set 30-day TTL on terminal reservation hash (audit trail, then auto-cleanup)
redis.call('PEXPIRE', reservation_key, 2592000000)

-- Collect balance snapshots for all budgeted scopes (avoids post-operation Java round-trips)
local balances = {}
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    local b = redis.call('HMGET', budget_key, 'remaining', 'reserved', 'spent', 'allocated', 'debt', 'overdraft_limit', 'is_over_limit')
    table.insert(balances, {
        scope = scope,
        remaining = normalize_int(b[1] or "0"),
        reserved = normalize_int(b[2] or "0"),
        spent = normalize_int(b[3] or "0"),
        allocated = normalize_int(b[4] or "0"),
        debt = normalize_int(b[5] or "0"),
        overdraft_limit = normalize_int(b[6] or "0"),
        is_over_limit = (b[7] == "true")
    })
end

local response = cjson.encode({
    reservation_id = reservation_id,
    state = "RELEASED",
    estimate_amount = estimate_amount,
    estimate_unit = estimate_unit,
    affected_scopes_json = affected_scopes_json,
    balances = balances
})
-- Store the immutable post-mutation snapshot in the reservation hash before
-- returning. It shares the terminal hash TTL and survives body-cache misses.
redis.call('HSET', reservation_key, 'release_response_state', 'PENDING')
if idempotency_key ~= "" and idempotency_key ~= nil then
    redis.call('HSET', reservation_key, 'release_response_json', response)
end
return response
