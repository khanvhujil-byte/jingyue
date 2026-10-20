-- Run only after broker ACK. A crash before cleanup may duplicate, never lose, an event.
redis.call('HSET',KEYS[1],'pending:'..ARGV[1],'','repair:'..ARGV[1],'')
redis.call('XDEL',KEYS[2],ARGV[2])
return 1
