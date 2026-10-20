-- KEYS[1]: window ZSET, KEYS[2]: sequence String. Share a hash slot in Redis Cluster.
-- ARGV: window seconds, request limit. Redis supplies time, avoiding app clock differences.
local key, sequenceKey=KEYS[1], KEYS[2] or (KEYS[1]..':seq')
local window, limit=tonumber(ARGV[1]), tonumber(ARGV[2])
if not window or window<=0 or not limit or limit<=0 or window%1~=0 or limit%1~=0 then
    return redis.error_reply('Invalid rate-limit configuration')
end
local kind=redis.call('TYPE',key).ok
local sequenceKind=redis.call('TYPE',sequenceKey).ok
if (kind~='none' and kind~='zset') or (sequenceKind~='none' and sequenceKind~='string') then
    return redis.error_reply('Invalid rate-limit key type')
end
local clock=redis.call('TIME')
local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
local duration=window*1000
redis.call('ZREMRANGEBYSCORE',key,'-inf',now-duration)
local current=redis.call('ZCARD',key)
if current>=limit then return 0 end
-- INCR ensures same-millisecond requests never replace each other as randomseed(now) did.
local sequence=redis.call('INCR',sequenceKey)
redis.call('ZADD',key,now,tostring(now)..':'..tostring(sequence))
redis.call('PEXPIRE',key,duration)
redis.call('PEXPIRE',sequenceKey,duration)
return current+1
