-- Called only for a durable CREATED/REJECTED SQL gate at least 60 seconds old.
-- Keep stock, metadata, active claims and permanent refund dedupe evidence.
local done={}
for i=1,#ARGV,3 do
  local order,user,rid=ARGV[i],ARGV[i+1],ARGV[i+2]
  local pending=redis.call('HGET',KEYS[1],'pending:'..order) or ''
  local repair=redis.call('HGET',KEYS[1],'repair:'..order) or ''
  local refund=redis.call('HGET',KEYS[1],'releaseRepair:'..order) or ''
  if pending=='' and repair=='' and refund=='' then
    local req='req:'..user..':'..rid
    local raw=redis.call('HGET',KEYS[1],req)
    if raw then
      local event=cjson.decode(raw)
      local fence=redis.call('HGET',KEYS[1],'fence:'..event.skuId) or ''
      if event.orderId==order and fence~=order and fence~='release:'..order then
        redis.call('HSET',KEYS[1],'done:'..user..':'..rid,raw)
        redis.call('HDEL',KEYS[1],req,'event:'..order,'pending:'..order,'repair:'..order,'queued:'..order)
        table.insert(done,order)
      end
    else
      redis.call('HDEL',KEYS[1],'event:'..order,'pending:'..order,'repair:'..order,'queued:'..order)
      table.insert(done,order)
    end
  end
end
return done
