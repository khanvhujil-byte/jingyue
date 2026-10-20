package com.hmdp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.consumer.SeckillVoucherBatchConsumer;
import com.hmdp.service.order.VoucherOrderBatchWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class SeckillVoucherBatchConsumerTest {
    @Test void acknowledgesOnlyAfterWriterReturns() throws Exception {
        VoucherOrderBatchWriter writer=mock(VoucherOrderBatchWriter.class);
        Acknowledgment ack=mock(Acknowledgment.class);
        SeckillVoucherBatchConsumer consumer=new SeckillVoucherBatchConsumer(writer,new ObjectMapper());
        consumer.processBatch(Collections.singletonList(new ConsumerRecord<>("orders",0,1,"1","{\"id\":1,\"userId\":1,\"voucherId\":10}")),ack);
        InOrder order=inOrder(writer,ack); order.verify(writer).createBatch(anyList()); order.verify(ack).acknowledge();
    }
    @Test void failedTransactionOrMalformedEventNeverAcknowledges() {
        VoucherOrderBatchWriter writer=mock(VoucherOrderBatchWriter.class);
        Acknowledgment ack=mock(Acknowledgment.class);
        SeckillVoucherBatchConsumer consumer=new SeckillVoucherBatchConsumer(writer,new ObjectMapper());
        doThrow(new IllegalStateException("database down")).when(writer).createBatch(anyList());
        assertThrows(Exception.class,()->consumer.processBatch(Collections.singletonList(new ConsumerRecord<>("orders",0,1,"1","{\"id\":1,\"userId\":1,\"voucherId\":10}")),ack));
        assertThrows(Exception.class,()->consumer.processBatch(Collections.singletonList(new ConsumerRecord<>("orders",0,2,"1","invalid")),ack));
        verifyNoInteractions(ack);
    }
}
