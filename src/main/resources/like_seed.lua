-- Initialization runs under a distributed lock; never overwrite accepted work.
-- KEYS meta,users,outbox,pending. ARGV count,version,baseline,[userId,time]...
local function typ(k) return redis.call('TYPE',k).ok end
local function integer(s)
  if not s or (s~='0' and not string.match(s,'^[1-9][0-9]*$')) then return nil end
  local n=tonumber(s)
  if not n or n>9007199254740991 then return nil end
  return n
end
local count,version,baseline=integer(ARGV[1]),integer(ARGV[2]),integer(ARGV[3])
if not count or not version or not baseline or count>2147483647 or count<baseline
  or (#ARGV-3)%2~=0 or (#ARGV-3)/2~=count-baseline then return redis.error_reply('Invalid seed metadata') end
local seen={}
for i=4,#ARGV,2 do
  local user,time=integer(ARGV[i]),integer(ARGV[i+1])
  if not user or user<=0 or not time or seen[ARGV[i]] then return redis.error_reply('Invalid seed users') end
  seen[ARGV[i]]=true
end
if typ(KEYS[1])=='hash' then
  if typ(KEYS[4])~='string' then return redis.error_reply('Like pending marker missing') end
  return 1
end
if typ(KEYS[1])~='none' then return redis.error_reply('Invalid like metadata type') end
local pt,st,ut=typ(KEYS[4]),typ(KEYS[3]),typ(KEYS[2])
if (pt~='none' and pt~='string') or (st~='none' and st~='stream')
  or (ut~='none' and ut~='zset') then return redis.error_reply('Invalid like initialization types') end
if pt=='string' and redis.call('GET',KEYS[4])~='0' then return -1 end
-- Missing metadata with a live event must not rebuild from a lagging database.
if st=='stream' and redis.call('XLEN',KEYS[3])>0 then
  local events=redis.call('XRANGE',KEYS[3],'-','+')
  local blog=string.match(KEYS[1],':meta:(%d+)$')
  for _,event in ipairs(events) do
    for i=1,#event[2],2 do
      if event[2][i]=='blogId' and event[2][i+1]==blog then return -1 end
    end
  end
end
redis.call('DEL',KEYS[2])
for i=4,#ARGV,2 do redis.call('ZADD',KEYS[2],ARGV[i+1],ARGV[i]) end
redis.call('HSET',KEYS[1],'count',ARGV[1],'version',ARGV[2],'baseline',ARGV[3])
redis.call('SET',KEYS[4],'0')
return 1
