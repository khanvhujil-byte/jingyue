package com.hmdp.service.order;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only status: only a committed database order can produce CREATED. */
@Service
public class VoucherOrderStatusService {
    private final VoucherOrderMapper orders;
    private final StringRedisTemplate redis;
    // Bounded, user-scoped snapshots reduce repeated polling; no write or stock reconstruction.
    private final Cache<String, Map<String, Object>> snapshots = Caffeine.newBuilder()
            .maximumSize(100000).expireAfterWrite(1, TimeUnit.SECONDS).build();

    public VoucherOrderStatusService(VoucherOrderMapper orders, StringRedisTemplate redis) {
        this.orders = orders;
        this.redis = redis;
    }

    public Map<String, Object> query(Long userId, Long voucherId) {
        if (userId == null || voucherId == null || voucherId <= 0) {
            throw new IllegalArgumentException("用户或优惠券参数无效");
        }
        return snapshots.get(userId + ":" + voucherId, key -> {
            Long id = orders.findOwnedOrderId(userId, voucherId);
            Map<String, Object> result = new LinkedHashMap<>();
            if (id != null) {
                result.put("state", "CREATED");
                result.put("orderId", id.toString());
            } else {
                Boolean reserved = redis.opsForSet().isMember("seckill:order:" + voucherId, userId.toString());
                if (reserved == null) throw new IllegalStateException("暂时无法确认下单结果");
                result.put("state", reserved ? "PENDING" : "UNKNOWN");
            }
            return Collections.unmodifiableMap(result);
        });
    }
}
