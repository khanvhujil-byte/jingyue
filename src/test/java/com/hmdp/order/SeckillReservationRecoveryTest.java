package com.hmdp.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.order.SeckillOutboxPublisher;
import com.hmdp.service.order.SeckillReservationRecovery;
import com.hmdp.service.order.VoucherOrderWriter;
import com.hmdp.utils.RedisScriptContent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Faults are injected into test copies of real Lua; production accepts no fault-injection argument. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=VoucherOrderWriterTest.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillReservationRecoveryTest {
    @Autowired DataSource source;
    @Autowired VoucherOrderWriter writer;
    Process process;
    LettuceConnectionFactory connections;
    StringRedisTemplate redis;
    String stock, users, outbox, journal, index;
    JdbcTemplate jdbc;
    final String production = RedisScriptContent.read("seckill.lua");
    final DefaultRedisScript<Long> recover = new DefaultRedisScript<>(RedisScriptContent.read("seckill-recover.lua"), Long.class);

    @BeforeAll void start() throws Exception {
        int port; try (ServerSocket socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        Path directory=Files.createTempDirectory("hmdp-compensation-redis-");
        process=new ProcessBuilder(System.getProperty("hmdp.test.redis-executable", "redis-server"),
                "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                "--dir", directory.toString()).redirectErrorStream(true).redirectOutput(directory.resolve("redis.log").toFile()).start();
        connections=new LettuceConnectionFactory("127.0.0.1", port); connections.afterPropertiesSet();
        redis=new StringRedisTemplate(connections);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { redis.opsForValue().set("test:ready", "1"); break; }
            catch (RuntimeException error) { if (!process.isAlive() || System.nanoTime()>deadline) throw error; Thread.sleep(50); }
        }
    }
    @AfterAll void stop() { if (connections!=null) connections.destroy(); if (process!=null) process.destroy(); }
    @BeforeEach void setup() {
        String prefix="test:"+UUID.randomUUID();
        stock=prefix+":stock"; users=prefix+":users"; outbox=prefix+":outbox";
        journal=stock+":reservation"; index=outbox+":reservation:pending";
        redis.opsForValue().set(stock, "100");
        jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS tb_voucher_order");
        jdbc.execute("DROP TABLE IF EXISTS tb_seckill_voucher");
        jdbc.execute("CREATE TABLE tb_seckill_voucher(voucher_id BIGINT PRIMARY KEY,stock INT)");
        jdbc.execute("CREATE TABLE tb_voucher_order(id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,voucher_id BIGINT NOT NULL,"
                +"pay_type INT DEFAULT 1,status INT DEFAULT 1,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
                +"pay_time TIMESTAMP,use_time TIMESTAMP,refund_time TIMESTAMP,update_time TIMESTAMP,UNIQUE(user_id,voucher_id))");
        jdbc.update("INSERT INTO tb_seckill_voucher VALUES(10,100)");
    }
    Long reserve(String lua, long id, long user) {
        return redis.execute(new DefaultRedisScript<>(lua, Long.class), Arrays.asList(stock,users,outbox),
                "10", Long.toString(user), Long.toString(id));
    }
    Long repair(String expected) { return redis.execute(recover, Arrays.asList(journal,index,stock,users,outbox), expected); }
    String failAt(String point) {
        String marker="-- fault-point:"+point;
        assertTrue(production.contains(marker));
        return production.replace(marker, "error('injected "+point+"')");
    }
    String deferRollback(String lua) {
        String original="local restored = redis.pcall('SET', stockKey, rawStock)";
        assertTrue(lua.contains(original));
        return lua.replace(original, "local restored = {err='injected restore unavailable'}");
    }
    void assertRestored() {
        assertEquals("100",redis.opsForValue().get(stock));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(users,"1")));
        assertEquals(0L,redis.opsForStream().size(outbox));
        assertFalse(redis.hasKey(journal));
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(index,journal)));
        assertEquals(100,jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class));
    }
    SeckillReservationRecovery worker() {
        SeckillReservationRecovery recovery=new SeckillReservationRecovery(redis);
        ReflectionTestUtils.setField(recovery,"outboxStream",outbox);
        return recovery;
    }
    SeckillOutboxPublisher publisher(KafkaTemplate<String,String> kafka) {
        SeckillOutboxPublisher publisher=new SeckillOutboxPublisher(redis,kafka,new ObjectMapper());
        ReflectionTestUtils.setField(publisher,"outboxStream",outbox);
        return publisher;
    }

    @Test void ordinaryReservationStillProducesOneEventAndDeduplicates() {
        assertEquals(0L,reserve(production,1001,1));
        assertEquals(2L,reserve(production,1002,1));
        assertEquals("99",redis.opsForValue().get(stock));
        assertEquals(1L,redis.opsForStream().size(outbox));
        assertFalse(redis.hasKey(journal)); assertFalse(redis.hasKey(index));
    }
    @Test void errorAfterStockImmediatelyRestoresExactStockAndCreatesNoEvent() {
        assertEquals(7L,reserve(failAt("after-stock"),1001,1)); assertRestored();
        assertEquals(0L,reserve(production,1002,1)); assertEquals("99",redis.opsForValue().get(stock));
    }
    @Test void errorAfterUserRestoresStockAndEligibilityWithoutRemovingOtherUsers() {
        redis.opsForSet().add(users,"9");
        assertEquals(7L,reserve(failAt("after-user"),1001,1)); assertRestored();
        assertTrue(redis.opsForSet().isMember(users,"9"));
    }
    @Test void failedRollbackLeavesJournalAndFencesAllNewReservationsUntilNewWorkerRepairs() {
        assertEquals(6L,reserve(deferRollback(failAt("after-user")),1001,1));
        assertEquals("PREPARED",redis.opsForHash().get(journal,"state"));
        assertEquals(6L,reserve(production,1002,2)); assertEquals("99",redis.opsForValue().get(stock));
        assertEquals(1,worker().recoverOnce()); assertRestored();
        assertEquals(0,worker().recoverOnce()); assertEquals("100",redis.opsForValue().get(stock));
    }
    @Test void repeatedAndConcurrentRecoveryCannotRefundTwice() throws Exception {
        assertEquals(6L,reserve(deferRollback(failAt("after-stock")),1001,1));
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> results=new ArrayList<>();
            for(int i=0;i<24;i++) results.add(pool.submit(()->repair("1001")));
            int rollbacks=0;
            for(Future<Long> result:results) if(result.get(10,TimeUnit.SECONDS)==1L) rollbacks++;
            assertEquals(1,rollbacks); assertRestored();
        } finally { pool.shutdownNow(); }
    }
    @Test void partialCompensationCanRepeatAfterStockWasAlreadyRestored() {
        assertEquals(6L,reserve(deferRollback(failAt("after-user")),1001,1));
        redis.opsForValue().set(stock,"100"); // Previous compensation restored stock but not user relation.
        assertEquals(1L,repair("1001")); assertRestored();
    }
    @Test void preparedEventIsNeverPublishedBeforeRollback() {
        assertEquals(6L,reserve(deferRollback(failAt("after-user")),1001,1));
        KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        publisher(kafka).publishPending(); verifyNoInteractions(kafka);
        assertEquals(1L,repair("1001")); assertRestored();
    }
    @Test void readyFailureRollsForwardAndNeverRefundsStock() {
        assertEquals(6L,reserve(failAt("after-ready"),1001,1));
        assertEquals("READY",redis.opsForHash().get(journal,"state"));
        assertEquals(0L,redis.opsForStream().size(outbox));
        assertEquals(2L,repair("1001"));
        assertEquals("99",redis.opsForValue().get(stock));
        assertTrue(redis.opsForSet().isMember(users,"1"));
        assertEquals(1L,redis.opsForStream().size(outbox)); assertFalse(redis.hasKey(journal));
    }
    @Test void failureAfterEventMayDuplicateDeliveryButDatabaseStillDeductsOnlyOnce() throws Exception {
        assertEquals(6L,reserve(failAt("after-event"),1001,1));
        assertEquals(2L,repair("1001"));
        assertEquals(2L,redis.opsForStream().size(outbox));
        for(MapRecord<String,Object,Object> event:redis.opsForStream().range(outbox,Range.unbounded())) {
            Map<Object,Object> value=event.getValue();
            writer.create(new VoucherOrder().setId(Long.valueOf(value.get("id").toString()))
                    .setUserId(Long.valueOf(value.get("userId").toString())).setVoucherId(10L));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class));
        assertEquals(99,jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher",Integer.class));
        assertEquals("99",redis.opsForValue().get(stock));
    }
    @Test void alreadyPublishedAndCommittedOrderCanOnlyBeReplayedNotRefunded() {
        assertEquals(6L,reserve(failAt("after-event"),1001,1));
        KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
        SettableListenableFuture<SendResult<String,String>> confirmed=new SettableListenableFuture<>(); confirmed.set(mock(SendResult.class));
        when(kafka.send(anyString(),eq("1001"),anyString())).thenAnswer(call->{
            writer.create(new VoucherOrder().setId(1001L).setUserId(1L).setVoucherId(10L)); return confirmed;
        });
        publisher(kafka).publishPending(); assertEquals(0L,redis.opsForStream().size(outbox));
        assertEquals(2L,repair("1001")); publisher(kafka).publishPending();
        assertEquals(99,jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher",Integer.class));
        assertEquals("99",redis.opsForValue().get(stock));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order",Integer.class));
        verify(kafka,times(2)).send(anyString(),eq("1001"),anyString());
    }
    @Test void oldRecoveryAttemptCannotModifyANewerReservationJournal() {
        assertEquals(6L,reserve(deferRollback(failAt("after-user")),1001,1));
        assertEquals(1L,repair("1001"));
        assertEquals(6L,reserve(deferRollback(failAt("after-user")),1002,2));
        assertEquals(0L,repair("1001"));
        assertEquals("1002",redis.opsForHash().get(journal,"orderId"));
        assertEquals("99",redis.opsForValue().get(stock));
    }
    @Test void unexpectedStockIsNotOverwrittenAndVoucherStaysFenced() {
        assertEquals(6L,reserve(deferRollback(failAt("after-stock")),1001,1));
        redis.opsForValue().set(stock,"80");
        assertEquals(-1L,repair("1001")); assertEquals("80",redis.opsForValue().get(stock));
        assertTrue(redis.hasKey(journal)); assertEquals(6L,reserve(production,1002,2));
    }
    @Test void wrongOutboxTypeRetainsReadyJournalAndRecoversAfterRepair() {
        assertEquals(6L,reserve(failAt("after-ready"),1001,1));
        redis.opsForValue().set(outbox,"wrong-type");
        assertEquals(-1L,repair("1001")); assertEquals("99",redis.opsForValue().get(stock));
        redis.delete(outbox); assertEquals(2L,repair("1001"));
        assertEquals(1L,redis.opsForStream().size(outbox));
    }
    @Test void invalidInputAndWrongRecoveryIndexTypeFailBeforeStockChanges() {
        redis.opsForValue().set(index,"wrong-type");
        assertThrows(RuntimeException.class,()->reserve(production,1001,1));
        assertEquals("100",redis.opsForValue().get(stock)); assertFalse(redis.hasKey(journal));
    }
    @Test void staleIndexWithoutJournalIsCleanedWithoutTouchingStock() {
        redis.opsForSet().add(index,journal);
        assertEquals(1,worker().recoverOnce()); assertRestored();
    }
    @Test void scanProgressesAcrossPagesInsteadOfAlwaysStartingAtTheSameKeys() {
        for(int i=0;i<350;i++) redis.opsForSet().add(index,stock+":"+i+":reservation");
        SeckillReservationRecovery recovery=worker();
        for(int i=0;i<20 && Boolean.TRUE.equals(redis.hasKey(index));i++) recovery.recoverOnce();
        assertFalse(redis.hasKey(index)); assertEquals("100",redis.opsForValue().get(stock));
    }
    @Test void warmupCannotBypassRecoveryFenceEvenWhenStockIsMissing() {
        assertEquals(6L,reserve(deferRollback(failAt("after-stock")),1001,1));
        redis.delete(stock);
        Long result=redis.execute(new DefaultRedisScript<>(RedisScriptContent.read("seckill-warmup.lua"),Long.class),
                Arrays.asList(stock,users,outbox,journal),"100");
        assertEquals(-3L,result); assertFalse(redis.hasKey(stock));
        assertEquals(-1L,repair("1001")); assertTrue(redis.hasKey(journal));
    }
}
