package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.order.VoucherOrderWriter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    public static final String OUTBOX_STREAM = "stream.seckill.outbox";
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> STOCK_READY_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('TYPE',KEYS[1]).ok~='string' then return 0 end "
            + "local s=redis.call('GET',KEYS[1]); local n=tonumber(s); "
            + "if (s=='0' or string.match(s,'^[1-9][0-9]*$')) and n and n<=9007199254740991 then return 1 end return 0",
            Long.class);

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setScriptText(com.hmdp.utils.RedisScriptContent.read("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private VoucherOrderWriter orderWriter;
    private final Cache<Long, Boolean> inventoryReady = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(10, TimeUnit.MINUTES).build();
    @Value("${hmdp.seckill.outbox-stream:stream.seckill.outbox}")
    private String outboxStream = OUTBOX_STREAM;
    @Value("${hmdp.seckill.max-pending-events:100000}")
    private long maxPendingEvents = 100000L;
    @Value("${hmdp.seckill.global-limit-per-second:10000}")
    private int globalLimit = 10000;
    @Value("${hmdp.seckill.user-limit-per-second:20}")
    private int userLimit = 20;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        if (!ensureInventoryReady(voucherId)) return Result.fail("秒杀库存尚未就绪，请等待库存核对完成");
        Long result = reserve(voucherId, userId, orderId);
        if (result != null && result == 3L) {
            // Redis loss cannot be repaired from a database lagging behind Kafka orders.
            inventoryReady.invalidate(voucherId);
            if (!ensureInventoryReady(voucherId)) return Result.fail("秒杀库存尚未就绪，请等待库存核对完成");
            result = reserve(voucherId, userId, orderId);
        }
        if (result == null) throw new IllegalStateException("Redis 未返回秒杀受理结果");
        if (result == 1L) return Result.fail("库存不足");
        if (result == 2L) return Result.fail("重复下单");
        if (result == 4L) return Result.fail("下单人数较多，请稍后重试");
        if (result == 5L) return Result.fail("操作过于频繁，请稍后重试");
        if (result == 6L) throw new IllegalStateException("秒杀受理状态正在恢复，请稍后查询结果");
        if (result == 7L) return Result.fail("秒杀受理失败，已撤销预扣，请重试");
        if (result != 0L) return Result.fail("秒杀库存尚未初始化");
        // Accepted, not yet a database order. Kafka availability no longer blocks the HTTP request.
        return Result.ok(orderId);
    }

    private boolean ensureInventoryReady(Long voucherId) {
        // Cache only validation. Requests NEVER recreate stock or purchase eligibility from MySQL.
        Boolean ready = inventoryReady.get(voucherId, id ->
                Long.valueOf(1L).equals(stringRedisTemplate.execute(STOCK_READY_SCRIPT,
                        java.util.Collections.singletonList("seckill:stock:" + id))));
        if (!Boolean.TRUE.equals(ready)) inventoryReady.invalidate(voucherId);
        return Boolean.TRUE.equals(ready);
    }

    private Long reserve(Long voucherId, Long userId, long orderId) {
        return stringRedisTemplate.execute(SECKILL_SCRIPT,
                Arrays.asList("seckill:stock:" + voucherId, "seckill:order:" + voucherId, outboxStream,
                        "seckill:limit:global", "seckill:limit:user:" + userId,
                        "seckill:stock:" + voucherId + ":reservation", outboxStream + ":reservation:pending"),
                voucherId.toString(), userId.toString(), Long.toString(orderId), Long.toString(maxPendingEvents),
                Integer.toString(globalLimit), Integer.toString(userLimit));
    }

    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        orderWriter.create(voucherOrder);
    }
}
