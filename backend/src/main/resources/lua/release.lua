local sku,user,order,epoch=unpack(ARGV,1,4)
local meta=redis.call('HGET',KEYS[1],'meta:'..sku)
if not meta then return -1 end
meta=cjson.decode(meta)
if meta.epoch~=epoch then return -2 end
if redis.call('HGET',KEYS[1],'released:'..order)=='1' then
  if redis.call('HGET',KEYS[1],'fence:'..sku)=='release:'..order then
    redis.call('HSET',KEYS[1],'fence:'..sku,'','releaseRepair:'..order,'')
  end
  return 0
end
local fence=redis.call('HGET',KEYS[1],'fence:'..sku) or ''
if fence~='' then return -3 end
if redis.call('HGET',KEYS[1],'claim:'..sku..':'..user)~=order then return -4 end
local stock=tonumber(redis.call('HGET',KEYS[1],'stock:'..sku))
if not stock or stock<0 or stock>=tonumber(meta.totalStock) then return -5 end
-- Refund, matching claim removal and permanent dedupe evidence are ONE HSET.
redis.call('HSET',KEYS[1],'stock:'..sku,tostring(stock+1),'claim:'..sku..':'..user,'',
  'released:'..order,'1','releaseRepair:'..order,cjson.encode({skuId=sku,userId=user,orderId=order,epoch=epoch}),
  'fence:'..sku,'release:'..order)
if ARGV[5]=='after_commit' then return redis.error_reply('INJECTED_AFTER_REFUND') end
redis.call('HSET',KEYS[1],'releaseRepair:'..order,'','fence:'..sku,'')
return 1
