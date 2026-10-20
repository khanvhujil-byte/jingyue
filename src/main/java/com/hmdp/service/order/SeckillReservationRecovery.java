package com.hmdp.service.order;

import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.RedisScriptContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Only repairs fenced Redis reservation writes; never cancels an accepted Kafka/DB order. */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.seckill.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReservationRecovery {
    private static final DefaultRedisScript<Long> RECOVER = new DefaultRedisScript<>(
            RedisScriptContent.read("seckill-recover.lua"), Long.class);
    private static final DefaultRedisScript<List> SCAN = new DefaultRedisScript<>(
            "return redis.call('SSCAN',KEYS[1],ARGV[1],'COUNT',100)", List.class);
    private final StringRedisTemplate redis;
    private String scanCursor = "0";
    @Value("${hmdp.seckill.outbox-stream:stream.seckill.outbox}")
    private String outboxStream = VoucherOrderServiceImpl.OUTBOX_STREAM;

    public SeckillReservationRecovery(StringRedisTemplate redis) { this.redis = redis; }

    @Scheduled(fixedDelayString = "${hmdp.seckill.recovery-delay-ms:1000}")
    public void recoverPending() {
        try { recoverOnce(); }
        catch (Exception error) { log.error("秒杀预扣恢复扫描失败，恢复记录保留", error); }
    }

    /** One SSCAN page per invocation; the cursor is an optimization, journals survive restart. */
    public synchronized int recoverOnce() {
        String index = outboxStream + ":reservation:pending";
        int recovered = 0;
        List page = redis.execute(SCAN, java.util.Collections.singletonList(index), scanCursor);
        if (page == null || page.size() != 2 || !(page.get(1) instanceof List))
            throw new IllegalStateException("恢复索引扫描未返回有效结果");
        scanCursor = page.get(0).toString();
        for (Object key : (List<?>) page.get(1)) {
            String journal = key.toString();
            try {
                Map<Object, Object> snapshot = redis.opsForHash().entries(journal);
                String stock = value(snapshot, "stockKey", journal.endsWith(":reservation")
                        ? journal.substring(0, journal.length() - ":reservation".length()) : journal);
                String users = value(snapshot, "userKey", "unused:seckill-recovery");
                String id = value(snapshot, "orderId", "");
                if (!snapshot.isEmpty() && (!journal.equals(stock + ":reservation")
                        || !outboxStream.equals(snapshot.get("outboxKey"))))
                    throw new IllegalStateException("恢复记录身份或事件流不匹配");
                Long result = redis.execute(RECOVER,
                        Arrays.asList(journal, index, stock, users, outboxStream), id);
                if (result == null || result < 0) {
                    log.error("秒杀预扣需人工核对，保持该券停止受理：{}", journal);
                } else if (result > 0) recovered++;
            } catch (Exception error) {
                log.error("秒杀预扣恢复失败，记录与受理屏障保留：{}", journal, error);
            }
        }
        return recovered;
    }

    private static String value(Map<Object, Object> snapshot, String key, String fallback) {
        Object value = snapshot.get(key);
        return value == null ? fallback : value.toString();
    }
}
