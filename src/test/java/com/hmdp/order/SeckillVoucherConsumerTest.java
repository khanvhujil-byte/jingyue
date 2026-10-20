package com.hmdp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.consumer.SeckillVoucherConsumer;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SeckillVoucherConsumerTest {
    @Test void acknowledgesOnlyAfterDatabaseCallSucceeds() throws Exception {
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SeckillVoucherConsumer consumer = new SeckillVoucherConsumer(service, new ObjectMapper());
        consumer.processMessage(record("{\"id\":1,\"userId\":2,\"voucherId\":10}"), ack);
        InOrder order = inOrder(service, ack);
        order.verify(service).createVoucherOrder(any(VoucherOrder.class));
        order.verify(ack).acknowledge();
    }
    @Test void databaseFailurePropagatesWithoutAcknowledgment() {
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        doThrow(new IllegalStateException("database unavailable")).when(service).createVoucherOrder(any());
        Acknowledgment ack = mock(Acknowledgment.class);
        SeckillVoucherConsumer consumer = new SeckillVoucherConsumer(service, new ObjectMapper());
        assertThrows(IllegalStateException.class, () -> consumer.processMessage(
                record("{\"id\":1,\"userId\":2,\"voucherId\":10}"), ack));
        verifyNoInteractions(ack);
    }
    @Test void malformedMessageCannotBeAcknowledged() {
        IVoucherOrderService service = mock(IVoucherOrderService.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SeckillVoucherConsumer consumer = new SeckillVoucherConsumer(service, new ObjectMapper());
        assertThrows(Exception.class, () -> consumer.processMessage(record("not-json"), ack));
        verifyNoInteractions(service, ack);
    }
    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>("seckill-voucher-order", 0, 0, "1", json);
    }
}
