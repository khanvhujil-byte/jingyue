-- Missing or expired tokens cannot be revived. One Redis roundtrip per request.
local user=redis.call('HGETALL',KEYS[1])
if #user>0 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end
return user
