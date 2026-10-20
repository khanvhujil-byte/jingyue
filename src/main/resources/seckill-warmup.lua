-- Initialization only. Never overwrite reserved stock or restore eligibility from a count.
if redis.call('EXISTS', KEYS[4] or (KEYS[1] .. ':reservation')) == 1 then return -3 end
if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
if redis.call('EXISTS', KEYS[2]) == 1 then return -1 end
if redis.call('EXISTS', KEYS[3]) == 1 and redis.call('XLEN', KEYS[3]) > 0 then return -2 end
redis.call('SET', KEYS[1], ARGV[1])
return 1
