package com.hmdp.service.order;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/**
 * Explicit initialization for a newly created voucher BEFORE opening traffic; not stock repair.
 * An operator must also establish that Kafka has no accepted but uncommitted order for this voucher.
 * An empty Redis outbox and zero database orders alone CANNOT establish that condition.
 * HTTP order ingress must never call this initializer when Redis stock disappears.
 */
@Service
public class SeckillInventoryWarmup {
    private final StringRedisTemplate redis;
    private final SeckillVoucherMapper vouchers;
    private final VoucherOrderMapper orders;
    private static final DefaultRedisScript<Long> WARM = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> UNLOCK = new DefaultRedisScript<>(
            "if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    static { WARM.setScriptText(com.hmdp.utils.RedisScriptContent.read("seckill-warmup.lua")); WARM.setResultType(Long.class); }
    @Value("${hmdp.seckill.outbox-stream:stream.seckill.outbox}")
    private String outboxStream = "stream.seckill.outbox";
    public SeckillInventoryWarmup(StringRedisTemplate redis, SeckillVoucherMapper vouchers, VoucherOrderMapper orders) {
        this.redis = redis; this.vouchers = vouchers; this.orders = orders;
    }
    public boolean warmIfAbsent(Long voucherId) {
        if (voucherId == null) throw new IllegalArgumentException("缺少优惠券编号");
        String stockKey = "seckill:stock:" + voucherId;
        if (Boolean.TRUE.equals(redis.hasKey(stockKey))) return false;
        String lock = "lock:seckill:warmup:" + voucherId, token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, Duration.ofSeconds(30))))
            throw new IllegalStateException("库存正在预热，请稍后重试");
        try {
            if (Boolean.TRUE.equals(redis.hasKey(stockKey))) return false;
            SeckillVoucher voucher = vouchers.selectById(voucherId);
            if (voucher == null || voucher.getStock() == null || voucher.getStock() < 0)
                throw new IllegalStateException("秒杀库存数据无效");
            if (orders.selectCount(new QueryWrapper<VoucherOrder>().eq("voucher_id", voucherId)) > 0)
                throw new IllegalStateException("已有历史订单，禁止用数据库库存自动重建秒杀资格");
            Long result = redis.execute(WARM, Arrays.asList(stockKey,"seckill:order:"+voucherId,outboxStream,
                            stockKey+":reservation"),
                    voucher.getStock().toString());
            if (result == null || result < 0)
                throw new IllegalStateException("存在已受理订单或待发送事件，禁止覆盖库存，请先对账");
            return result == 1;
        } finally { redis.execute(UNLOCK, Collections.singletonList(lock), token); }
    }
}
