local old=redis.call('HGET',KEYS[1],'meta:'..ARGV[1])
if old then
  if old==ARGV[2] then return 0 end
  return -1
end
redis.call('HSET',KEYS[1],'meta:'..ARGV[1],ARGV[2],'stock:'..ARGV[1],ARGV[3],'sale:'..ARGV[1],'READY','fence:'..ARGV[1],'')
return 1
