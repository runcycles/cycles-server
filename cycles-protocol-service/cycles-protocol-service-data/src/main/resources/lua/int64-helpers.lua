-- Shared exact signed-decimal helpers for Redis Lua scripts.
--
-- Redis embeds Lua 5.1 numbers as IEEE-754 doubles, while Cycles protocol
-- amounts are int64. Keep amounts as normalized decimal strings until Redis
-- performs HINCRBY's checked arithmetic so values above 2^53 stay exact.
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

local function split_int(value)
    local normalized = normalize_int(value)
    local negative = string.sub(normalized, 1, 1) == "-"
    return negative, negative and string.sub(normalized, 2) or normalized
end

local function compare_abs(a, b)
    if #a ~= #b then return #a < #b and -1 or 1 end
    if a == b then return 0 end
    return a < b and -1 or 1
end

local function compare_int(a, b)
    local a_negative, a_digits = split_int(a)
    local b_negative, b_digits = split_int(b)
    if a_negative ~= b_negative then return a_negative and -1 or 1 end
    local cmp = compare_abs(a_digits, b_digits)
    return a_negative and -cmp or cmp
end

local function add_abs(a, b)
    local i, j, carry, result = #a, #b, 0, ""
    while i > 0 or j > 0 or carry > 0 do
        local av = i > 0 and tonumber(string.sub(a, i, i)) or 0
        local bv = j > 0 and tonumber(string.sub(b, j, j)) or 0
        local sum = av + bv + carry
        result = tostring(sum % 10) .. result
        carry = math.floor(sum / 10)
        i = i - 1
        j = j - 1
    end
    return result
end

local function subtract_abs(a, b)
    local i, j, borrow, result = #a, #b, 0, ""
    while i > 0 do
        local digit = tonumber(string.sub(a, i, i)) - borrow
        local bv = j > 0 and tonumber(string.sub(b, j, j)) or 0
        if digit < bv then
            digit = digit + 10
            borrow = 1
        else
            borrow = 0
        end
        result = tostring(digit - bv) .. result
        i = i - 1
        j = j - 1
    end
    return normalize_int(result)
end

local function add_int(a, b)
    local a_negative, a_digits = split_int(a)
    local b_negative, b_digits = split_int(b)
    if a_negative == b_negative then
        local sum = add_abs(a_digits, b_digits)
        return a_negative and ("-" .. sum) or sum
    end
    local cmp = compare_abs(a_digits, b_digits)
    if cmp == 0 then return "0" end
    if cmp > 0 then
        local difference = subtract_abs(a_digits, b_digits)
        return a_negative and ("-" .. difference) or difference
    end
    local difference = subtract_abs(b_digits, a_digits)
    return b_negative and ("-" .. difference) or difference
end

local function negate_int(value)
    local normalized = normalize_int(value)
    if normalized == "0" then return "0" end
    return string.sub(normalized, 1, 1) == "-"
        and string.sub(normalized, 2) or ("-" .. normalized)
end

local function subtract_int(a, b)
    return add_int(a, negate_int(b))
end

local function min_int(a, b)
    return compare_int(a, b) <= 0 and a or b
end

local function max_int(a, b)
    return compare_int(a, b) >= 0 and a or b
end
