local t=redis.call('TIME')
local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-1000)
if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end
redis.call('ZADD',KEYS[1],now,ARGV[2])
redis.call('PEXPIRE',KEYS[1],2000)
return 1
