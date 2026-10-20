package com.hmdp.order;

import com.hmdp.HmDianPingApplication;
import com.hmdp.consumer.SeckillVoucherConsumer;
import com.hmdp.service.order.VoucherOrderWriter;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = HmDianPingApplication.class, properties = {
        "hmdp.seckill.relay-enabled=false", "hmdp.seckill.consumer-enabled=false", "hmdp.seckill.recovery-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seckill-context",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class SeckillApplicationContextTest {
    @MockBean RedissonClient redissonClient;
    @Autowired SeckillVoucherConsumer consumer;
    @Autowired VoucherOrderWriter writer;
    @Autowired KafkaTemplate<String, String> kafka;
    @Test void completeApplicationWiresTheReliablePipeline() {
        assertNotNull(consumer); assertNotNull(writer); assertNotNull(kafka);
    }
}
