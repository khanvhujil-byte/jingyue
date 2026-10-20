local event=redis.call('HGET',KEYS[1],'pending:'..ARGV[1])
if not event or event=='' then return 0 end
local data=cjson.decode(event)
local queued=redis.call('HGET',KEYS[1],'queued:'..ARGV[1]) or ''
if queued~='' and #redis.call('XRANGE',KEYS[2],queued,queued)>0 then
  redis.call('HSET',KEYS[1],'repair:'..ARGV[1],'')
  if redis.call('HGET',KEYS[1],'fence:'..data.skuId)==ARGV[1] then redis.call('HSET',KEYS[1],'fence:'..data.skuId,'') end
  return 0
end
local id=redis.call('XADD',KEYS[2],'*','event',event)
redis.call('HSET',KEYS[1],'queued:'..ARGV[1],id,'repair:'..ARGV[1],'')
if redis.call('HGET',KEYS[1],'fence:'..data.skuId)==ARGV[1] then redis.call('HSET',KEYS[1],'fence:'..data.skuId,'') end
return 1
