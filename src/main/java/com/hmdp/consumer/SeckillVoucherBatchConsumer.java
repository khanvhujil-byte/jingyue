package com.hmdp.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.order.VoucherOrderBatchWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name="hmdp.seckill.batch-enabled", havingValue="true")
public class SeckillVoucherBatchConsumer {
    private final VoucherOrderBatchWriter writer;
    private final ObjectMapper json;
    public SeckillVoucherBatchConsumer(VoucherOrderBatchWriter writer, ObjectMapper json) {
        this.writer = writer; this.json = json;
    }
    @KafkaListener(containerFactory="seckillVoucherBatchKafkaListenerContainerFactory",
            topics="${hmdp.seckill.topic:seckill-voucher-order}",
            autoStartup="${hmdp.seckill.consumer-enabled:true}",
            groupId="${spring.kafka.consumer.group-id:hmdp-seckill}")
    public void processBatch(List<ConsumerRecord<String,String>> records, Acknowledgment ack) throws Exception {
        List<VoucherOrder> orders = new ArrayList<>(records.size());
        for (ConsumerRecord<String,String> record : records) orders.add(json.readValue(record.value(), VoucherOrder.class));
        writer.createBatch(orders); // Transaction proxy has committed before this returns.
        ack.acknowledge();
    }
}
