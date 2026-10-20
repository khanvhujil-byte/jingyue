if redis.call('EXISTS',KEYS[1])==0 then return {} end
local count=redis.call('HGET',KEYS[1],'count')
local baseline=redis.call('HGET',KEYS[1],'baseline')
if not tonumber(count) or not tonumber(baseline)
  or redis.call('ZCARD',KEYS[2])~=tonumber(count)-tonumber(baseline) then
  return redis.error_reply('Like read state mismatch')
end
local liked=0
if ARGV[1]~='' and redis.call('ZSCORE',KEYS[2],ARGV[1]) then liked=1 end
return {tonumber(count),liked}
