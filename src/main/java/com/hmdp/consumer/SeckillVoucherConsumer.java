package com.hmdp.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="hmdp.seckill.batch-enabled", havingValue="false", matchIfMissing=true)
public class SeckillVoucherConsumer {
    private final IVoucherOrderService voucherOrderService;
    private final ObjectMapper objectMapper;

    public SeckillVoucherConsumer(IVoucherOrderService voucherOrderService, ObjectMapper objectMapper) {
        this.voucherOrderService = voucherOrderService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(containerFactory = "seckillVoucherOrderKafkaListenerContainerFactory",
            topics = "${hmdp.seckill.topic:seckill-voucher-order}",
            autoStartup = "${hmdp.seckill.consumer-enabled:true}",
            groupId = "${spring.kafka.consumer.group-id:hmdp-seckill}")
    public void processMessage(ConsumerRecord<String, String> record, Acknowledgment ack)
            throws JsonProcessingException {
        VoucherOrder order = objectMapper.readValue(record.value(), VoucherOrder.class);
        // The separate writer's transaction commits before this call returns.
        // Exceptions propagate: failed processing must not acknowledge the message.
        voucherOrderService.createVoucherOrder(order);
        ack.acknowledge();
    }
}
