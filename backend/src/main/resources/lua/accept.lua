-- All authoritative reservation fields share one hash and one HSET command.
-- Stream is a delivery index; the hash journal remains repairable after XADD failure.
local state=KEYS[1]
local sku,user,rid,digest,order,eventId,version=unpack(ARGV,1,7)
local req='req:'..user..':'..rid
local function denied(code,reason)
  local result=cjson.encode({result='REJECTED',code=code,reason=reason,digest=digest,orderId=order,userId=user,skuId=sku,clientRequestId=rid})
  redis.call('HSET',state,req,result)
  return {'PROCESSING',result}
end
-- GC removes the transient event fields but keeps this permanent tombstone so
-- a retried request cannot create a second reservation after compaction.
local existing=redis.call('HGET',state,'done:'..user..':'..rid)
if not existing then existing=redis.call('HGET',state,req) end
if existing then
  local prior=cjson.decode(existing)
  if prior.digest~=digest then return {'REQUEST_CONFLICT',''} end
  return {'PROCESSING',existing}
end
local meta=redis.call('HGET',state,'meta:'..sku)
if not meta then return {'NOT_READY',''} end
meta=cjson.decode(meta)
if tostring(meta.configVersion)~=version then return denied('CONFIG_CHANGED','票种配置已更新，请重新选择') end
if redis.call('HGET',state,'sale:'..sku)~='ON_SALE' then return denied('NOT_ON_SALE','当前日期票暂未开放销售') end
if (redis.call('HGET',state,'fence:'..sku) or '')~='' then return {'RECOVERY_REQUIRED',''} end
local t=redis.call('TIME')
local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
-- Admission quotas share this script: replay checks happen before quota consumption.
local rates={state..':rate:global',state..':rate:sku:'..sku,state..':rate:user:'..user}
local ceilings={tonumber(ARGV[12] or '4000'),tonumber(ARGV[13] or '4000'),tonumber(ARGV[14] or '5')}
for i=1,3 do
  redis.call('ZREMRANGEBYSCORE',rates[i],'-inf',now-1000)
  if redis.call('ZCARD',rates[i])>=ceilings[i] then return {'RATE_LIMITED',''} end
end
for i=1,3 do redis.call('ZADD',rates[i],now,eventId);redis.call('PEXPIRE',rates[i],2000) end
if now<tonumber(meta.saleStart) or now>=tonumber(meta.saleEnd) or now>=tonumber(meta.validUntil) then return denied('OUTSIDE_SALE_WINDOW','当前不在售卖时间内') end
local health=redis.call('GET',KEYS[3])
if not health then return {'BACKPRESSURE',''} end
health=cjson.decode(health)
if now-tonumber(health.at)>tonumber(ARGV[8]) or tonumber(health.lag)>tonumber(ARGV[9]) then return {'BACKPRESSURE',''} end
if redis.call('XLEN',KEYS[2])>=tonumber(ARGV[10]) then return {'BACKPRESSURE',''} end
local claim=redis.call('HGET',state,'claim:'..sku..':'..user) or ''
if claim~='' then return {'ACTIVE_CLAIM',claim} end
local stock=tonumber(redis.call('HGET',state,'stock:'..sku))
if not stock or stock<0 then return {'RECOVERY_REQUIRED',''} end
if stock==0 then return denied('SOLD_OUT','该日期票已售罄') end
local event=cjson.encode({schemaVersion=1,eventId=eventId,orderId=order,userId=user,skuId=sku,
  clientRequestId=rid,digest=digest,acceptedAt=tostring(now),configVersion=version,epoch=meta.epoch})
-- This one mutation commits stock, claim, original request, event and recovery evidence.
-- No rollback of earlier Redis commands is assumed.
redis.call('HSET',state,'stock:'..sku,tostring(stock-1),'claim:'..sku..':'..user,order,
  req,event,'event:'..order,event,'pending:'..order,event,'repair:'..order,event,'fence:'..sku,order)
if ARGV[11]=='after_commit' then return redis.error_reply('INJECTED_AFTER_RESERVATION') end
local queued=redis.pcall('XADD',KEYS[2],'*','event',event)
if type(queued)=='table' and queued.err then return {'PROCESSING',event} end
redis.call('HSET',state,'queued:'..order,queued,'repair:'..order,'','fence:'..sku,'')
return {'PROCESSING',event}
