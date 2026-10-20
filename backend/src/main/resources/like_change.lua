-- Same-slot atomic acceptance: state, counter, version and recoverable event.
-- KEYS meta,users,outbox,pending,partition-rate,user-rate
-- ARGV blogId,userId,target(1/0/toggle),maxPending,partitionLimit,userLimit,requestId
local function typ(k) return redis.call('TYPE',k).ok end
local function integer(s)
  if not s or (s ~= '0' and not string.match(s,'^[1-9][0-9]*$')) then return nil end
  local n=tonumber(s)
  if not n or n>9007199254740991 then return nil end
  return n
end
if typ(KEYS[1])=='none' then return {-2} end
if typ(KEYS[1])~='hash' then return redis.error_reply('Invalid like metadata type') end
local count=integer(redis.call('HGET',KEYS[1],'count'))
local version=integer(redis.call('HGET',KEYS[1],'version'))
local baseline=integer(redis.call('HGET',KEYS[1],'baseline'))
local pending=typ(KEYS[4])=='string' and integer(redis.call('GET',KEYS[4])) or nil
if not count or not version or not baseline or not pending or version>=9007199254740991
  or count<baseline or count>2147483647 then return redis.error_reply('Invalid like metadata values') end
local ut,st=typ(KEYS[2]),typ(KEYS[3])
if (ut~='none' and ut~='zset') or (st~='none' and st~='stream') then
  return redis.error_reply('Invalid like state or outbox type')
end
if redis.call('ZCARD',KEYS[2])~=count-baseline then return redis.error_reply('Like state count mismatch') end
local limits={tonumber(ARGV[5]),tonumber(ARGV[6])}
for i=5,6 do
  local t=typ(KEYS[i])
  if (t~='none' and t~='zset') or not limits[i-4] or limits[i-4]<1 then
    return redis.error_reply('Invalid rate configuration')
  end
end
local clock=redis.call('TIME')
local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
for i=5,6 do
  redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now-1000)
  if redis.call('ZCARD',KEYS[i])>=limits[i-4] then return {-3} end
end
for i=5,6 do
  redis.call('ZADD',KEYS[i],now,ARGV[7]); redis.call('PEXPIRE',KEYS[i],2000)
end
local existing=redis.call('ZSCORE',KEYS[2],ARGV[2]) and 1 or 0
local target=ARGV[3]=='toggle' and 1-existing or tonumber(ARGV[3])
if target~=0 and target~=1 then return redis.error_reply('Invalid desired state') end
if existing==target then return {0,target,count,version} end
local maxPending=tonumber(ARGV[4])
if not maxPending or maxPending<1 then return redis.error_reply('Invalid backlog limit') end
if redis.call('XLEN',KEYS[3])>=maxPending then return {-4} end
local nextCount=count+target-existing
if nextCount<baseline or nextCount>2147483647 then return redis.error_reply('Like count out of range') end
version=version+1
-- All potentially wrong key types and integer values were checked before mutation.
redis.call('XADD',KEYS[3],'*','blogId',ARGV[1],'userId',ARGV[2],
  'liked',tostring(target),'version',string.format('%.0f',version),'time',string.format('%.0f',now))
redis.call('INCR',KEYS[4])
if target==1 then redis.call('ZADD',KEYS[2],now,ARGV[2]) else redis.call('ZREM',KEYS[2],ARGV[2]) end
redis.call('HSET',KEYS[1],'count',nextCount,'version',string.format('%.0f',version))
return {1,target,nextCount,version}
