-- reserve_stock.lua
--
-- Atomically:
--   1. Rejects duplicate purchase attempts by the same user for the same item.
--   2. Verifies sufficient stock remains.
--   3. Decrements stock and records the user in the reservation tracking set.
--
-- KEYS[1] = inventory:item:{itemId}            (String, integer stock count)
-- KEYS[2] = inventory:item:{itemId}:users       (Set, member = userId)
--
-- ARGV[1] = userId
-- ARGV[2] = quantity requested
--
-- Return codes:
--    1  -> success, stock reserved
--   -1  -> insufficient stock
--   -2  -> duplicate purchase (user already holds a reservation for this item)
--   -3  -> item does not exist / stock key missing

local stockKey = KEYS[1]
local usersKey = KEYS[2]
local userId = ARGV[1]
local quantity = tonumber(ARGV[2])

if redis.call("EXISTS", stockKey) == 0 then
    return -3
end

if redis.call("SISMEMBER", usersKey, userId) == 1 then
    return -2
end

local currentStock = tonumber(redis.call("GET", stockKey))

if currentStock == nil or currentStock < quantity then
    return -1
end

redis.call("DECRBY", stockKey, quantity)
redis.call("SADD", usersKey, userId)

return 1
