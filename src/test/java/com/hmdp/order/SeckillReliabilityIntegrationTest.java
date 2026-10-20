package com.hmdp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.KafkaConfig;
import com.hmdp.consumer.SeckillVoucherConsumer;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.order.SeckillOutboxPublisher;
import com.hmdp.service.order.VoucherOrderWriter;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated Redis, embedded Kafka and transactional H2: no production database/key is touched. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VoucherOrderWriterTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillReliabilityIntegrationTest {
    @Autowired VoucherOrderWriter writer;
    @Autowired DataSource dataSource;
    private Process redisServer;
    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private DefaultRedisScript<Long> script;
    private String stockKey, usersKey, stream;

    @BeforeAll void startRedis() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path dir = Files.createTempDirectory("hmdp-reliability-redis-");
        String executable = System.getProperty("hmdp.test.redis-executable", "redis-server");
        redisServer = new ProcessBuilder(executable, "--bind", "127.0.0.1", "--port", Integer.toString(port),
                "--save", "", "--appendonly", "no", "--dir", dir.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("redis.log").toFile()).start();
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { redis.opsForValue().set("test:ready", "1"); break; }
            catch (RuntimeException e) {
                if (System.nanoTime() > deadline || !redisServer.isAlive()) throw e;
                Thread.sleep(50);
            }
        }
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("seckill.lua"));
        script.setResultType(Long.class);
    }
    @AfterAll void stopRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
        if (redisServer != null) redisServer.destroy();
    }
    @BeforeEach void keys() {
        String prefix = "test:" + UUID.randomUUID();
        stockKey = prefix + ":stock"; usersKey = prefix + ":users"; stream = prefix + ":outbox";
        redis.opsForValue().set(stockKey, "100");
    }
    private Long reserve(long id, long user) {
        return redis.execute(script, Arrays.asList(stockKey, usersKey, stream), "10", Long.toString(user), Long.toString(id));
    }
    private SeckillOutboxPublisher publisher(KafkaTemplate<String, String> kafka) {
        SeckillOutboxPublisher publisher = new SeckillOutboxPublisher(redis, kafka, new ObjectMapper());
        ReflectionTestUtils.setField(publisher, "outboxStream", stream);
        return publisher;
    }

    @Test void qualificationAndEventAreSavedTogetherAndDuplicatesCreateNoEvent() {
        assertEquals(0L, reserve(1, 1));
        assertEquals(2L, reserve(2, 1));
        assertEquals("99", redis.opsForValue().get(stockKey));
        assertEquals(1L, redis.opsForSet().size(usersKey));
        assertEquals(1L, redis.opsForStream().size(stream));
    }
    @Test void invalidOutboxDoesNotConsumeEligibility() {
        redis.opsForValue().set(stream, "wrong-type");
        assertThrows(RuntimeException.class, () -> reserve(1, 1));
        assertEquals("100", redis.opsForValue().get(stockKey));
        assertFalse(redis.hasKey(usersKey));
    }
    @Test void depletedStockCreatesNoEvent() {
        redis.opsForValue().set(stockKey, "0");
        assertEquals(1L, reserve(1, 1));
        assertFalse(redis.hasKey(stream));
        assertFalse(redis.hasKey(usersKey));
    }
    @Test void nonCanonicalStockCreatesNoOrphanEvent() {
        redis.opsForValue().set(stockKey, "1e2");
        assertEquals(3L, reserve(1, 1));
        assertEquals("1e2", redis.opsForValue().get(stockKey));
        assertFalse(redis.hasKey(stream));
        assertFalse(redis.hasKey(usersKey));
    }
    @Test void brokerFailureRetainsEventAndAnotherPublisherCanRetry() {
        assertEquals(0L, reserve(1, 1));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        SettableListenableFuture<SendResult<String, String>> failed = new SettableListenableFuture<>();
        failed.setException(new IllegalStateException("broker unavailable"));
        SettableListenableFuture<SendResult<String, String>> confirmed = new SettableListenableFuture<>();
        confirmed.set(mock(SendResult.class));
        when(kafka.send(anyString(), eq("1"), anyString())).thenReturn(failed, confirmed);
        publisher(kafka).publishPending();
        assertEquals(1L, redis.opsForStream().size(stream));
        assertEquals("99", redis.opsForValue().get(stockKey));
        publisher(kafka).publishPending(); // recreated process can recover without an in-memory cursor
        assertEquals(0L, redis.opsForStream().size(stream));
        verify(kafka, times(2)).send(anyString(), eq("1"), anyString());
    }
    @Test void unknownSendOutcomeRetainsEventForReplay() {
        assertEquals(0L, reserve(1, 1));
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new SettableListenableFuture<>());
        SeckillOutboxPublisher publisher = publisher(kafka);
        ReflectionTestUtils.setField(publisher, "publishTimeoutSeconds", 0L);
        publisher.publishPending();
        assertEquals(1L, redis.opsForStream().size(stream));
    }

    @Test void realKafkaDeliveryRollsBackFailureThenRecoversAndDeduplicates() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY, stock INT)");
        jdbc.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, voucher_id BIGINT NOT NULL, "
                + "pay_type INT DEFAULT 1, status INT DEFAULT 1, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "pay_time TIMESTAMP, use_time TIMESTAMP, refund_time TIMESTAMP, update_time TIMESTAMP, "
                + "UNIQUE(user_id,voucher_id), CONSTRAINT reject_user CHECK(user_id <> 1))");
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES (10,100)");
        String topic = "seckill-test-" + UUID.randomUUID();
        EmbeddedKafkaBroker broker = new EmbeddedKafkaBroker(1, true, 1, topic);
        DefaultKafkaProducerFactory<String, String> producers = null;
        ConcurrentMessageListenerContainer<String, String> container = null;
        try {
            broker.afterPropertiesSet();
            KafkaProperties props = new KafkaProperties();
            props.setBootstrapServers(Arrays.asList(broker.getBrokersAsString().split(",")));
            props.getConsumer().setGroupId("test-" + UUID.randomUUID());
            props.getConsumer().setAutoOffsetReset("earliest");
            props.getProducer().getProperties().put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
            KafkaConfig config = new KafkaConfig();
            producers = config.producerFactory(props);
            KafkaTemplate<String, String> kafka = config.kafkaTemplate(producers);
            IVoucherOrderService service = mock(IVoucherOrderService.class);
            doAnswer(invocation -> { writer.create(invocation.getArgument(0)); return null; })
                    .when(service).createVoucherOrder(any());
            SeckillVoucherConsumer consumer = new SeckillVoucherConsumer(service, new ObjectMapper());
            CountDownLatch failed = new CountDownLatch(1), committedTwice = new CountDownLatch(2);
            container = config.seckillVoucherOrderKafkaListenerContainerFactory(props).createContainer(topic);
            container.setupMessageListener((AcknowledgingMessageListener<String, String>) (record, ack) -> {
                try {
                    consumer.processMessage(record, ack);
                    committedTwice.countDown();
                } catch (Exception e) {
                    failed.countDown();
                    throw new IllegalStateException(e);
                }
            });
            container.start();
            assertEquals(0L, reserve(9001, 1));
            SeckillOutboxPublisher publisher = publisher(kafka);
            ReflectionTestUtils.setField(publisher, "topic", topic);
            publisher.publishPending();
            assertEquals(0L, redis.opsForStream().size(stream));
            assertTrue(failed.await(30, TimeUnit.SECONDS), "consumer should observe the insertion failure");
            assertEquals(100, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
            jdbc.execute("ALTER TABLE tb_voucher_order DROP CONSTRAINT reject_user");
            // Replay as if publisher crashed after Kafka confirmed but before removing its event.
            kafka.send(topic, "9001", "{\"id\":9001,\"userId\":1,\"voucherId\":10}").get(10, TimeUnit.SECONDS);
            assertTrue(committedTwice.await(30, TimeUnit.SECONDS), "retry and replay should both be processed");
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
            assertEquals(99, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher", Integer.class));
            assertEquals("99", redis.opsForValue().get(stockKey));
            assertEquals(1L, redis.opsForSet().size(usersKey));
        } finally {
            if (container != null) container.stop();
            if (producers != null) producers.destroy();
            broker.destroy();
        }
    }
}
