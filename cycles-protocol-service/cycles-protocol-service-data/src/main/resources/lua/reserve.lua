-- Cycles Protocol v0.1.24 - Reserve Lua Script
-- Atomically reserve budget across all affected scopes
--local cjson = require("cjson")

-- Redis embeds Lua 5.1 numbers as IEEE-754 doubles, while protocol amounts are
-- int64. Keep ledger values as normalized decimal strings so comparisons,
-- HINCRBY arguments, and JSON snapshots remain exact above 2^53.
local function normalize_int(value)
    if value == nil or value == false then return nil end
    local s = tostring(value)
    if not string.match(s, "^%-?%d+$") then return nil end
    local negative = string.sub(s, 1, 1) == "-"
    local digits = negative and string.sub(s, 2) or s
    digits = string.gsub(digits, "^0+", "")
    if digits == "" then return "0" end
    return negative and ("-" .. digits) or digits
end

local function compare_int(a, b)
    a = normalize_int(a)
    b = normalize_int(b)
    local a_negative = string.sub(a, 1, 1) == "-"
    local b_negative = string.sub(b, 1, 1) == "-"
    if a_negative ~= b_negative then return a_negative and -1 or 1 end
    local a_digits = a_negative and string.sub(a, 2) or a
    local b_digits = b_negative and string.sub(b, 2) or b
    local cmp = 0
    if #a_digits ~= #b_digits then
        cmp = #a_digits < #b_digits and -1 or 1
    elseif a_digits ~= b_digits then
        cmp = a_digits < b_digits and -1 or 1
    end
    return a_negative and -cmp or cmp
end

local function negate_int(value)
    local normalized = normalize_int(value)
    if normalized == "0" then return "0" end
    return string.sub(normalized, 1, 1) == "-"
        and string.sub(normalized, 2) or ("-" .. normalized)
end

-- Parse inputs
local reservation_id = ARGV[1]
local subject_json = ARGV[2]
local action_json = ARGV[3]
local estimate_amount_raw = normalize_int(ARGV[4])
local estimate_unit = ARGV[5]
local ttl_ms = tonumber(ARGV[6])
local grace_ms = tonumber(ARGV[7])
local idempotency_key = ARGV[8]
local scope_path = ARGV[9]
local tenant = ARGV[10]
local overage_policy = ARGV[11] or "ALLOW_IF_AVAILABLE"
local metadata_json   = ARGV[12] or ""
local payload_hash    = ARGV[13] or ""
local max_extensions  = tonumber(ARGV[14]) or 10
local units_csv       = ARGV[15] or ""

if not estimate_amount_raw or compare_int(estimate_amount_raw, "0") < 0 then
    return cjson.encode({error = "INVALID_REQUEST", message = "estimate amount must be a non-negative int64"})
end

if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":reserve:" .. idempotency_key
    local existing_res_id = redis.call('GET', idem_key)

    if existing_res_id then
        -- Spec MUST: detect payload mismatch on idempotent replay
        if payload_hash ~= "" then
            local stored_hash = redis.call('GET', idem_key .. ':hash')
            if stored_hash and stored_hash ~= payload_hash then
                return cjson.encode({error = "IDEMPOTENCY_MISMATCH"})
            end
        end
        return cjson.encode({
            reservation_id = existing_res_id,
            idempotency_key = idempotency_key,
            response_snapshot = redis.call('HGET', 'reservation:res_' .. existing_res_id, 'reserve_response_json'),
            response_cache_ttl_ms = redis.call('PTTL', idem_key)
        })
    end
end
-- Governance CASCADE SEMANTICS Rule 2 (cycles-governance-admin-v0.1.25):
-- once the owning tenant's CLOSED flip is durable, any reservation
-- create/commit/release/extend MUST be rejected with 409 TENANT_CLOSED,
-- regardless of the child's own status and even before the close cascade
-- reaches this child or revokes API keys (Mode B invariant (a)). Checked
-- INSIDE the script — like the BUDGET_FROZEN/BUDGET_CLOSED guards below —
-- so the guard is atomic with the budget mutations: Redis executes scripts
-- serially, so a request observed after the flip can never partially
-- succeed. The tenant record is written by the admin plane to the shared
-- Redis ("tenant:<id>" JSON, status field); ABSENCE of the record
-- (runtime-only deployment, no governance plane) means no restriction,
-- but a PRESENT record that cannot be decoded into an object with a
-- valid TenantStatus (ACTIVE|SUSPENDED|CLOSED) FAILS CLOSED
-- (INTERNAL_ERROR, no mutation) — matching the
-- admin plane's TenantRepository, which propagates parse failures instead
-- of treating a corrupt governance record as an open tenant.
-- Sits after the idempotency block above: a replay re-observes a mutation
-- that succeeded BEFORE the flip, mirroring how the budget status guards
-- also sit after replay handling.
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

-- Parse affected scopes (fixed args end at ARGV[15]; scopes start at ARGV[16])
local affected_scopes = {}
for i = 16, #ARGV do
    table.insert(affected_scopes, ARGV[i])
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local expires_at = now + ttl_ms

-- Check all scopes first (fail fast). Skip scopes without budgets.
-- Use HMGET to fetch all needed fields in a single call per scope.
local budgeted_scopes = {}
local pre_budget_state = {}  -- pre-mutation state for transition detection in events
for _, scope in ipairs(affected_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    local vals = redis.call('HMGET', budget_key, 'status', 'remaining', 'debt', 'is_over_limit', 'overdraft_limit')

    -- Skip scopes without a budget (all fields nil means key doesn't exist)
    if vals[1] ~= false or vals[2] ~= false or vals[3] ~= false or vals[4] ~= false then
        table.insert(budgeted_scopes, scope)

        local budget_status = vals[1] or 'ACTIVE'
        local remaining = normalize_int(vals[2] or "0")
        local debt = normalize_int(vals[3] or "0")
        local is_over_limit = vals[4]
        pre_budget_state[scope] = {remaining = remaining, is_over_limit = (is_over_limit == "true")}

        if budget_status == 'FROZEN' then
            return cjson.encode({error = "BUDGET_FROZEN", scope = scope})
        end
        if budget_status == 'CLOSED' then
            return cjson.encode({error = "BUDGET_CLOSED", scope = scope})
        end
        if is_over_limit == "true" then
            return cjson.encode({error = "OVERDRAFT_LIMIT_EXCEEDED", scope = scope, message = "Scope is over-limit, no new reservations allowed"})
        end
        local overdraft_limit = normalize_int(vals[5] or "0")
        if compare_int(debt, "0") > 0 and compare_int(overdraft_limit, "0") == 0 then
            return cjson.encode({error = "DEBT_OUTSTANDING", scope = scope, debt = debt})
        end
        if compare_int(remaining, estimate_amount_raw) < 0 then
            return cjson.encode({error = "BUDGET_EXCEEDED", scope = scope, remaining = remaining, requested = estimate_amount_raw})
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
                if unit_alt ~= estimate_unit then
                    if redis.call('EXISTS', "budget:" .. scope .. ":" .. unit_alt) == 1 then
                        table.insert(expected_units, unit_alt)
                    end
                end
            end
            if #expected_units > 0 then
                return cjson.encode({
                    error = "UNIT_MISMATCH",
                    scope = scope,
                    requested_unit = estimate_unit,
                    expected_units = expected_units
                })
            end
        end
    end
    return cjson.encode({error = "BUDGET_NOT_FOUND", scope = affected_scopes[#affected_scopes]})
end

-- All checks passed - reserve across budgeted scopes only
for _, scope in ipairs(budgeted_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
    redis.call('HINCRBY', budget_key, 'reserved', estimate_amount_raw)
    redis.call('HINCRBY', budget_key, 'remaining', negate_int(estimate_amount_raw))
end

-- Create reservation
local reservation_key = "reservation:res_" .. reservation_id
redis.call('HMSET', reservation_key,
    'reservation_id', reservation_id,
    'tenant', tenant,
    'state', 'ACTIVE',
    'subject_json', subject_json,
    'action_json', action_json,
    'estimate_amount', estimate_amount_raw,
    'estimate_unit', estimate_unit,
    'scope_path', scope_path,
    'affected_scopes', cjson.encode(affected_scopes),
    'created_at', now,
    'expires_at', expires_at,
    'grace_ms', grace_ms,
    'idempotency_key', idempotency_key,
    'overage_policy', overage_policy,
    'metadata_json', metadata_json,
    'budgeted_scopes', cjson.encode(budgeted_scopes),
    'max_extensions', max_extensions,
    'extension_count', 0
)

-- Add to reservation index
redis.call('ZADD', 'reservation:ttl', expires_at, reservation_id)

-- After successful reservation, store idempotency mapping
if idempotency_key ~= "" and idempotency_key ~= nil then
    local idem_key = "idem:" .. tenant .. ":reserve:" .. idempotency_key
    -- Minimum 24h TTL to avoid premature idempotency key recycling on short-lived reservations
    local idem_ttl = math.max(ttl_ms + grace_ms, 86400000)
    redis.call('PSETEX', idem_key, idem_ttl, reservation_id)
    -- Store payload hash for idempotency mismatch detection (spec MUST)
    if payload_hash ~= "" then
        redis.call('PSETEX', idem_key .. ':hash', idem_ttl, payload_hash)
    end
end

-- Collect balance snapshots for all budgeted scopes (avoids post-operation Java round-trips)
local balances = {}
for _, scope in ipairs(budgeted_scopes) do
    local budget_key = "budget:" .. scope .. ":" .. estimate_unit
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
        pre_remaining = pre.remaining or "0",
        pre_is_over_limit = pre.is_over_limit or false
    })
end

local response = cjson.encode({
    reservation_id = reservation_id,
    state = "ACTIVE",
    expires_at = tostring(expires_at),
    affected_scopes = affected_scopes,
    balances = balances,
    -- Redis cjson emits numbers with only 14 significant digits. Preserve the
    -- protocol int64 exactly by snapshotting the original decimal argument.
    estimate_amount = estimate_amount_raw,
    estimate_unit = estimate_unit,
    scope_path = scope_path,
    caps_json = redis.call('HGET', 'budget:' .. scope_path .. ':' .. estimate_unit, 'caps_json')
})
-- Durable source for exact idempotent replay. Unlike the fast body cache,
-- this snapshot is committed atomically with the budget mutation.
redis.call('HSET', reservation_key, 'reserve_response_state', 'PENDING')
if idempotency_key ~= "" and idempotency_key ~= nil then
    redis.call('HSET', reservation_key, 'reserve_response_json', response)
end
return response
