-- Standalone Redis; Cluster deployment requires an explicit same-slot design.
-- KEYS: stock, purchased-users, outbox, global-rate, user-rate, recovery Hash, recovery-index Set
-- ARGV: voucherId, userId, orderId
local stockKey, orderKey, outboxKey = KEYS[1], KEYS[2], KEYS[3]
local function keyType(key)
    return redis.call('TYPE', key).ok
end
local recoveryKey = KEYS[6] or (stockKey .. ':reservation')
local recoveryIndex = KEYS[7] or (outboxKey .. ':reservation:pending')
-- An unresolved operation fences this voucher until rollback/roll-forward finishes.
if keyType(recoveryKey) ~= 'none' then return 6 end
local indexType = keyType(recoveryIndex)
if indexType ~= 'none' and indexType ~= 'set' then
    return redis.error_reply('Invalid reservation recovery index type')
end
for i=1,3 do
    if not ARGV[i] or not string.match(ARGV[i], '^[1-9][0-9]*$') then
        return redis.error_reply('Invalid reservation identity')
    end
end
-- Lua does not roll back runtime errors. Validate types before changing anything.
if keyType(stockKey) ~= 'string' then return 3 end
local rawStock = redis.call('GET', stockKey)
-- INCRBY requires a canonical decimal integer: tonumber alone also accepts scientific notation.
if rawStock ~= '0' and not string.match(rawStock, '^[1-9][0-9]*$') then return 3 end
local stock = tonumber(rawStock)
if not stock or stock > 9007199254740991 then return 3 end
local orderType, outboxType = keyType(orderKey), keyType(outboxKey)
if orderType ~= 'none' and orderType ~= 'set' then
    return redis.error_reply('Invalid purchased-users key type')
end
if outboxType ~= 'none' and outboxType ~= 'stream' then
    return redis.error_reply('Invalid outbox key type')
end
-- Optional one-second sliding windows. TIME is Redis time; globally unique orderId is the ZSET member.
local globalLimit, userLimit = tonumber(ARGV[5] or '0'), tonumber(ARGV[6] or '0')
if (globalLimit and globalLimit > 0) or (userLimit and userLimit > 0) then
    local limits, keys = { globalLimit or 0, userLimit or 0 }, { KEYS[4], KEYS[5] }
    for i=1,2 do
        if limits[i] > 0 then
            if not keys[i] then return redis.error_reply('Missing rate-limit key') end
            local kind=keyType(keys[i])
            if kind ~= 'none' and kind ~= 'zset' then return redis.error_reply('Invalid rate-limit key type') end
        end
    end
    local clock=redis.call('TIME')
    local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
    for i=1,2 do
        if limits[i] > 0 then
            redis.call('ZREMRANGEBYSCORE', keys[i], '-inf', now-1000)
            if redis.call('ZCARD', keys[i]) >= limits[i] then return 5 end
        end
    end
    for i=1,2 do
        if limits[i] > 0 then
            redis.call('ZADD', keys[i], now, ARGV[3])
            redis.call('PEXPIRE', keys[i], 2000)
        end
    end
end
if stock <= 0 then return 1 end
if redis.call('SISMEMBER', orderKey, ARGV[2]) == 1 then return 2 end
-- Optional bounded backlog: reject before stock or eligibility changes.
local maxPending = tonumber(ARGV[4] or '0')
if maxPending and maxPending > 0 and outboxType == 'stream'
        and redis.call('XLEN', outboxKey) >= maxPending then return 4 end
-- Register recovery BEFORE changing stock/eligibility. A stale index without a journal is harmless.
redis.call('SADD', recoveryIndex, recoveryKey)
redis.call('HSET', recoveryKey, 'schema', '1', 'state', 'PREPARED',
    'stockKey', stockKey, 'userKey', orderKey, 'outboxKey', outboxKey,
    'stockBefore', rawStock, 'voucherId', ARGV[1], 'userId', ARGV[2], 'orderId', ARGV[3])

local function failed(result) return type(result) == 'table' and result.err ~= nil end
local function finishJournal()
    -- Delete the fence first; if index cleanup fails, the scanner removes the stale index later.
    if failed(redis.pcall('DEL', recoveryKey)) then return false end
    return not failed(redis.pcall('SREM', recoveryIndex, recoveryKey))
end

local applied, failure = pcall(function()
    redis.call('INCRBY', stockKey, -1)
    -- fault-point:after-stock
    redis.call('SADD', orderKey, ARGV[2])
    -- fault-point:after-user
    -- READY is irreversible: all eligibility writes succeeded. Recovery must roll FORWARD.
    redis.call('HSET', recoveryKey, 'state', 'READY')
    -- fault-point:after-ready
    redis.call('XADD', outboxKey, '*', 'id', ARGV[3], 'userId', ARGV[2], 'voucherId', ARGV[1])
    -- fault-point:after-event
end)
if applied then
    finishJournal() -- Failure here must not refund an accepted/publishable order.
    return 0
end
redis.pcall('HSET', recoveryKey, 'lastError', tostring(failure))
if redis.call('HGET', recoveryKey, 'state') == 'READY' then return 6 end

-- No event can exist in PREPARED. The fence excludes other reservations for this voucher.
-- Restore the exact snapshot, NOT INCR(+1), so a retry cannot refund twice.
local restored = redis.pcall('SET', stockKey, rawStock)
if failed(restored) then return 6 end
local removed = redis.pcall('SREM', orderKey, ARGV[2])
if failed(removed) then return 6 end
if not finishJournal() then return 6 end
return 7 -- Definitively rejected and compensated; safe for the user to retry.
