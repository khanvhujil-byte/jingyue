-- ARGV is streamId, orderId pairs.  A record is deleted only after the
-- broker callback has been durably handed to this script.
for i=1,#ARGV,2 do
  local streamId=ARGV[i]
  local orderId=ARGV[i+1]
  redis.call('HSET',KEYS[1],'pending:'..orderId,'','repair:'..orderId,'')
  redis.call('XDEL',KEYS[2],streamId)
end
return #ARGV / 2
