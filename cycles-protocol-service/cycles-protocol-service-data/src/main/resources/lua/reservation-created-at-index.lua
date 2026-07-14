-- Completeness metadata for the optional per-tenant created_at_ms reservation index.
--
-- ARGV[1] operation: validate | finalize | invalidate | remove | page
-- ARGV[2] tenant
-- ARGV[3..] reservation ids for remove

local operation = ARGV[1]
local tenant = ARGV[2]
if not tenant or tenant == '' then
    return -1
end

local index_key = 'reservation:idx:' .. tenant .. ':created_at_ms'
local meta_key = 'reservation:idxmeta:' .. tenant .. ':created_at_ms'

if operation == 'validate' then
    local index_type = redis.call('TYPE', index_key).ok
    local meta_type = redis.call('TYPE', meta_key).ok
    if index_type ~= 'none' and index_type ~= 'zset' then
        redis.call('DEL', index_key)
        if meta_type ~= 'none' then
            redis.call('DEL', meta_key)
        end
        return 0
    end
    if index_type == 'none' and meta_type == 'none' then
        return 2
    end
    if index_type == 'none' and meta_type == 'hash'
            and redis.call('HGET', meta_key, 'state') == 'READY'
            and tonumber(redis.call('HGET', meta_key, 'expected_count')) == 0 then
        return 1
    end
    if index_type ~= 'zset' or meta_type ~= 'hash' then
        if meta_type ~= 'none' then
            redis.call('DEL', meta_key)
        end
        return 0
    end
    if redis.call('HGET', meta_key, 'state') ~= 'READY' then
        return 0
    end
    local expected_raw = redis.call('HGET', meta_key, 'expected_count')
    local expected = tonumber(expected_raw)
    local actual = redis.call('ZCARD', index_key)
    if not expected or expected < 0 or expected ~= actual then
        redis.call('DEL', meta_key)
        return 0
    end
    return 1
end

if operation == 'finalize' then
    local index_type = redis.call('TYPE', index_key).ok
    if index_type ~= 'none' and index_type ~= 'zset' then
        redis.call('DEL', index_key)
        redis.call('DEL', meta_key)
        return -1
    end
    local meta_type = redis.call('TYPE', meta_key).ok
    if meta_type ~= 'none' and meta_type ~= 'hash' then
        redis.call('DEL', meta_key)
    end
    redis.call('HSET', meta_key,
        'state', 'READY',
        'expected_count', redis.call('ZCARD', index_key),
        'version', '1',
        'backfilled_at', ARGV[3] or '')
    return 1
end

if operation == 'invalidate' then
    return redis.call('DEL', meta_key)
end

if operation == 'remove' then
    local index_type = redis.call('TYPE', index_key).ok
    if index_type == 'none' and redis.call('TYPE', meta_key).ok == 'hash'
            and redis.call('HGET', meta_key, 'state') == 'READY'
            and tonumber(redis.call('HGET', meta_key, 'expected_count')) == 0 then
        return 0
    end
    if index_type ~= 'zset' then
        if index_type ~= 'none' then
            redis.call('DEL', index_key)
        end
        redis.call('DEL', meta_key)
        return -1
    end
    local removed = 0
    for i = 3, #ARGV do
        removed = removed + redis.call('ZREM', index_key, ARGV[i])
    end
    if redis.call('TYPE', meta_key).ok == 'hash'
            and redis.call('HGET', meta_key, 'state') == 'READY' then
        redis.call('HSET', meta_key, 'expected_count', redis.call('ZCARD', index_key))
    end
    return removed
end

-- Return a bounded page in the server's stable total order: timestamp in the
-- requested direction, then reservation_id ascending in both directions.
-- Redis reverses equal-score members under ZREVRANGEBYSCORE, so assemble each
-- score group with ascending ZRANGEBYSCORE inside this one server-side call.
--
-- ARGV[3] direction: asc | desc
-- ARGV[4] lower score bound (Redis syntax)
-- ARGV[5] upper score bound (Redis syntax)
-- ARGV[6] cursor score or empty
-- ARGV[7] cursor reservation id or empty
-- ARGV[8] bounded result size
if operation == 'page' then
    local index_type = redis.call('TYPE', index_key).ok
    if index_type == 'none' then
        return {}
    end
    if index_type ~= 'zset' then
        return redis.error_reply('reservation created-at index is not a zset')
    end
    local direction = ARGV[3]
    local lower = ARGV[4]
    local upper = ARGV[5]
    local cursor_score = ARGV[6] or ''
    local cursor_id = ARGV[7] or ''
    local page_size = tonumber(ARGV[8])
    if (direction ~= 'asc' and direction ~= 'desc')
            or not lower or not upper or not page_size
            or page_size < 1 or page_size > 256 then
        return redis.error_reply('invalid reservation created-at index page arguments')
    end

    local result = {}
    local group_size = 128
    while (#result / 2) < page_size do
        local next_score
        if direction == 'desc' then
            next_score = redis.call('ZREVRANGEBYSCORE', index_key,
                upper, lower, 'WITHSCORES', 'LIMIT', 0, 1)
        else
            next_score = redis.call('ZRANGEBYSCORE', index_key,
                lower, upper, 'WITHSCORES', 'LIMIT', 0, 1)
        end
        if #next_score == 0 then
            break
        end

        local score = next_score[2]
        local offset = 0
        local group_start_rank = nil
        local group_count = nil
        if cursor_score ~= '' and tonumber(cursor_score) == tonumber(score) then
            group_start_rank = redis.call('ZCOUNT', index_key, '-inf', '(' .. score)
            group_count = redis.call('ZCOUNT', index_key, score, score)
            local stored_cursor_score = redis.call('ZSCORE', index_key, cursor_id)
            if stored_cursor_score and tonumber(stored_cursor_score) == tonumber(score) then
                local cursor_rank = redis.call('ZRANK', index_key, cursor_id)
                offset = cursor_rank - group_start_rank + 1
            else
                -- Opaque cursors are client-controlled and may name a missing
                -- member. Find the lexicographic insertion point with a bounded
                -- binary search over direct ranks instead of using a potentially
                -- linear ZRANGEBYSCORE LIMIT offset inside Redis's single-threaded
                -- Lua runtime.
                local low = 0
                local high = group_count
                while low < high do
                    local middle = math.floor((low + high) / 2)
                    local rank = group_start_rank + middle
                    local probe = redis.call('ZRANGE', index_key, rank, rank)
                    if #probe == 0 or probe[1] > cursor_id then
                        high = middle
                    else
                        low = middle + 1
                    end
                end
                offset = low
            end
        end
        while (#result / 2) < page_size do
            local group
            if group_start_rank then
                local first_rank = group_start_rank + offset
                local last_rank = math.min(
                    first_rank + group_size - 1,
                    group_start_rank + group_count - 1)
                if first_rank > last_rank then
                    group = {}
                else
                    group = redis.call('ZRANGE', index_key,
                        first_rank, last_rank, 'WITHSCORES')
                end
            else
                group = redis.call('ZRANGEBYSCORE', index_key,
                    score, score, 'WITHSCORES', 'LIMIT', offset, group_size)
            end
            if #group == 0 then
                break
            end
            for i = 1, #group, 2 do
                local member = group[i]
                table.insert(result, member)
                table.insert(result, group[i + 1])
                if (#result / 2) >= page_size then
                    return result
                end
            end
            local members_read = #group / 2
            offset = offset + members_read
            if members_read < group_size then
                break
            end
        end

        if direction == 'desc' then
            upper = '(' .. score
        else
            lower = '(' .. score
        end
        cursor_score = ''
        cursor_id = ''
    end
    return result
end

return -1
