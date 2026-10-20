-- Commit has already succeeded. Validate the ENTIRE ack before changing anything.
-- All keys share the partition hash tag, so this script can also run on Redis Cluster.
local streamType = redis.call('TYPE', KEYS[1]).ok
if streamType ~= 'stream' and streamType ~= 'none' then return -1 end
if #ARGV % 3 ~= 0 then return -2 end
local counts = {}
local ids = {}
for i = 1, #ARGV, 3 do
    local index = tonumber(ARGV[i + 1])
    if not index or index < 2 or index > #KEYS then return -3 end
    local event = redis.call('XRANGE', KEYS[1], ARGV[i], ARGV[i], 'COUNT', 1)
    if #event > 0 then
        local fields = event[1][2]
        local blogId = nil
        for f = 1, #fields, 2 do
            if fields[f] == 'blogId' then blogId = fields[f + 1] end
        end
        if blogId ~= ARGV[i + 2] then return -4 end
        if not ids[ARGV[i]] then
            ids[ARGV[i]] = true
            counts[index] = (counts[index] or 0) + 1
        end
    end
end
for index, amount in pairs(counts) do
    if redis.call('TYPE', KEYS[index]).ok ~= 'string' then return -5 end
    local raw = redis.call('GET', KEYS[index])
    if not string.match(raw, '^%d+$') or (string.len(raw) > 1 and string.sub(raw, 1, 1) == '0') then return -6 end
    local pending = tonumber(raw)
    if not pending or pending < amount or pending > 9007199254740991 then return -7 end
end
local deleted = 0
for id, _ in pairs(ids) do deleted = deleted + redis.call('XDEL', KEYS[1], id) end
for index, amount in pairs(counts) do redis.call('DECRBY', KEYS[index], amount) end
return deleted
