-- Standalone Redis only; all business writers must obey the per-voucher journal fence.
-- KEYS: journal, recovery-index, stock, purchased-users, outbox. ARGV: expected orderId.
-- 1 = rolled back, 2 = rolled forward, 3 = stale index removed, 0 = different operation, -1 = needs inspection.
local journal, index, stockKey, userKey, outbox = KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5]
local function typ(key) return redis.call('TYPE', key).ok end
local function failed(value) return type(value) == 'table' and value.err ~= nil end
local indexType = typ(index)
if indexType ~= 'none' and indexType ~= 'set' then return -1 end
if typ(journal) == 'none' then
    redis.call('SREM', index, journal)
    return 3
end
if typ(journal) ~= 'hash' then return -1 end
local id = redis.call('HGET', journal, 'orderId')
if id ~= ARGV[1] then return 0 end -- Another worker already finished; never touch a newer operation.
if redis.call('HGET', journal, 'schema') ~= '1'
    or redis.call('HGET', journal, 'stockKey') ~= stockKey
    or redis.call('HGET', journal, 'userKey') ~= userKey
    or redis.call('HGET', journal, 'outboxKey') ~= outbox
    or journal ~= stockKey .. ':reservation' or index ~= outbox .. ':reservation:pending' then return -1 end
local user = redis.call('HGET', journal, 'userId')
local voucher = redis.call('HGET', journal, 'voucherId')
local before = redis.call('HGET', journal, 'stockBefore')
if not user or not voucher or not id or not before
    or not string.match(user, '^[1-9][0-9]*$') or not string.match(voucher, '^[1-9][0-9]*$')
    or not string.match(id, '^[1-9][0-9]*$') or not string.match(before, '^[1-9][0-9]*$') then return -1 end
local n = tonumber(before)
if not n or n > 9007199254740991 then return -1 end
local after = string.format('%.0f', n - 1)
if typ(stockKey) ~= 'string' then return -1 end
local current = redis.call('GET', stockKey)
local state = redis.call('HGET', journal, 'state')
local userType = typ(userKey)
if userType ~= 'none' and userType ~= 'set' then return -1 end

if state == 'PREPARED' then
    -- A partial previous compensation can already have restored the exact before value.
    if current ~= before and current ~= after then return -1 end
    if failed(redis.pcall('SET', stockKey, before)) then return -1 end
    if failed(redis.pcall('SREM', userKey, user)) then return -1 end
elseif state == 'READY' then
    -- An event may already have reached Kafka/MySQL. NEVER refund after READY.
    if current ~= after or redis.call('SISMEMBER', userKey, user) ~= 1 then return -1 end
    local outboxType = typ(outbox)
    if outboxType ~= 'none' and outboxType ~= 'stream' then return -1 end
    local published = redis.pcall('XADD', outbox, '*', 'id', id, 'userId', user, 'voucherId', voucher)
    if failed(published) then return -1 end
    -- Replaying after an earlier XADD can duplicate an event: stable orderId makes DB replay safe.
else
    return -1
end
if failed(redis.pcall('DEL', journal)) then return -1 end
redis.pcall('SREM', index, journal)
if state == 'PREPARED' then return 1 else return 2 end
