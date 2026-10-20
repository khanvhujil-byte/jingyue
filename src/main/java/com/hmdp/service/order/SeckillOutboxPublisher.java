package com.hmdp.service.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.Future;
import org.springframework.data.redis.connection.stream.RecordId;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.seckill.relay-enabled", havingValue = "true", matchIfMissing = true)
public class SeckillOutboxPublisher {
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    @Value("${hmdp.seckill.topic:seckill-voucher-order}")
    private String topic = "seckill-voucher-order";
    @Value("${hmdp.seckill.publish-timeout-seconds:10}")
    private long publishTimeoutSeconds = 10L;
    @Value("${hmdp.seckill.outbox-stream:stream.seckill.outbox}")
    private String outboxStream = VoucherOrderServiceImpl.OUTBOX_STREAM;
    @Value("${hmdp.seckill.relay-batch-size:1000}")
    private int batchSize = 1000;

    public SeckillOutboxPublisher(StringRedisTemplate redis, KafkaTemplate<String, String> kafka,
                                 ObjectMapper objectMapper) {
        this.redis = redis;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${hmdp.seckill.relay-delay-ms:1000}")
    public void publishPending() {
        try {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                    outboxStream, Range.unbounded(), Limit.limit().count(batchSize));
            if (records == null || records.isEmpty()) return;
            List<Future<?>> futures = new ArrayList<>();
            List<RecordId> sentIds = new ArrayList<>();
            Exception sendFailure = null;
            long sendDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(publishTimeoutSeconds);
            boolean attempted = false;
            for (MapRecord<String, Object, Object> record : records) {
                if (attempted && System.nanoTime() >= sendDeadline) break;
                attempted = true;
                try {
                    Map<Object, Object> fields = record.getValue();
                    VoucherOrder order = new VoucherOrder();
                    order.setId(Long.valueOf(fields.get("id").toString()));
                    order.setUserId(Long.valueOf(fields.get("userId").toString()));
                    order.setVoucherId(Long.valueOf(fields.get("voucherId").toString()));
                    // Queue the whole batch first so producer batching can work.
                    futures.add(kafka.send(topic, order.getId().toString(), objectMapper.writeValueAsString(order)));
                    sentIds.add(record.getId());
                } catch (Exception e) { sendFailure = e; }
            }
            List<RecordId> confirmed = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(publishTimeoutSeconds);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    confirmed.add(sentIds.get(i));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch (Exception e) { sendFailure = e; }
            }
            // Only broker-acknowledged events may disappear. Unknown outcomes remain for replay.
            if (!confirmed.isEmpty()) redis.opsForStream().delete(outboxStream, confirmed.toArray(new RecordId[0]));
            if (sendFailure != null) log.warn("部分秒杀事件未获确认，保留待办重试：{}", sendFailure.toString());
        } catch (Exception e) {
            log.error("秒杀待发送事件投递失败，事件保留等待重试", e);
        }
    }
}
