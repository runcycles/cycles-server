-- Shared mutating ledger helpers for Redis Lua scripts.
--
-- Loaded after int64-helpers.lua so ledger predicates use exact signed-decimal
-- comparisons while keeping the reusable arithmetic prelude side-effect-free.
local function mark_uncovered_scopes(scopes, budget_state, required_amount,
                                     unit, zero_limit_only)
    for _, scope in ipairs(scopes) do
        local state = budget_state[scope]
        local eligible = not zero_limit_only
            or compare_int(state.overdraft_limit or "0", "0") == 0
        if eligible and compare_int(state.remaining, required_amount) < 0 then
            local budget_key = "budget:" .. scope .. ":" .. unit
            redis.call('HSET', budget_key, 'is_over_limit', 'true')
        end
    end
end
