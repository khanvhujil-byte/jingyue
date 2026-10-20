package com.hmdp.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.order.SeckillInventoryWarmup;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real isolated Redis verifies Lua side effects and safe initialization, never the business Redis. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeckillWarmupAndAdmissionTest {
    Process process;
    LettuceConnectionFactory connections;
    StringRedisTemplate redis;
    DefaultRedisScript<Long> reserve=new DefaultRedisScript<>();
    SeckillVoucherMapper vouchers;
    VoucherOrderMapper orders;
    SeckillInventoryWarmup warmup;
    String stream, global, user;
    @BeforeAll void start() throws Exception {
        int port; try(ServerSocket s=new ServerSocket(0)) { port=s.getLocalPort(); }
        Path dir=Files.createTempDirectory("hmdp-admission-redis-");
        process=new ProcessBuilder(System.getProperty("hmdp.test.redis-executable","redis-server"),
                "--bind","127.0.0.1","--port",Integer.toString(port),"--save","","--appendonly","no","--dir",dir.toString())
                .redirectErrorStream(true).redirectOutput(dir.resolve("redis.log").toFile()).start();
        connections=new LettuceConnectionFactory("127.0.0.1",port); connections.afterPropertiesSet();
        redis=new StringRedisTemplate(connections);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(true) {
            try { redis.opsForValue().set("test:ready","1"); break; }
            catch(RuntimeException e) { if(!process.isAlive()||System.nanoTime()>deadline) throw e; Thread.sleep(50); }
        }
        reserve.setLocation(new ClassPathResource("seckill.lua")); reserve.setResultType(Long.class);
    }
    @AfterAll void stop() { if(connections!=null) connections.destroy(); if(process!=null) process.destroy(); }
    @BeforeEach void reset() {
        redis.delete(Arrays.asList("seckill:stock:10","seckill:order:10","lock:seckill:warmup:10"));
        String prefix="test:"+UUID.randomUUID(); stream=prefix+":outbox"; global=prefix+":global"; user=prefix+":user";
        vouchers=mock(SeckillVoucherMapper.class); orders=mock(VoucherOrderMapper.class);
        when(vouchers.selectById(10L)).thenReturn(new SeckillVoucher().setVoucherId(10L).setStock(100));
        when(orders.selectCount(any(Wrapper.class))).thenReturn(0);
        warmup=new SeckillInventoryWarmup(redis,vouchers,orders);
        ReflectionTestUtils.setField(warmup,"outboxStream",stream);
    }
    Long reserve(long id,long uid,long backlog,int globalLimit,int userLimit) {
        return redis.execute(reserve,Arrays.asList("seckill:stock:10","seckill:order:10",stream,global,user),
                "10",Long.toString(uid),Long.toString(id),Long.toString(backlog),Integer.toString(globalLimit),Integer.toString(userLimit));
    }
    @Test void unusedVoucherCanWarmButExistingReservationIsNeverOverwritten() {
        assertTrue(warmup.warmIfAbsent(10L));
        assertEquals(0L,reserve(1,1,100,0,0));
        assertFalse(warmup.warmIfAbsent(10L));
        assertEquals("99",redis.opsForValue().get("seckill:stock:10"));
    }
    @Test void missingStockWithPurchasedUsersCannotBeResetFromDatabase() {
        redis.opsForSet().add("seckill:order:10","1");
        assertThrows(IllegalStateException.class,()->warmup.warmIfAbsent(10L));
        assertFalse(redis.hasKey("seckill:stock:10"));
    }
    @Test void missingStockWithPendingEventCannotBeResetFromDatabase() {
        redis.opsForStream().add(stream,Collections.singletonMap("id","1"));
        assertThrows(IllegalStateException.class,()->warmup.warmIfAbsent(10L));
        assertFalse(redis.hasKey("seckill:stock:10"));
    }
    @Test void databaseHistoricalOrdersBlockAutomaticEligibilityReconstruction() {
        when(orders.selectCount(any(Wrapper.class))).thenReturn(1);
        assertThrows(IllegalStateException.class,()->warmup.warmIfAbsent(10L));
        assertFalse(redis.hasKey("seckill:stock:10"));
    }
    @Test void backlogLimitRejectsWithoutConsumingStockOrEligibility() {
        warmup.warmIfAbsent(10L);
        assertEquals(0L,reserve(1,1,1,0,0));
        assertEquals(4L,reserve(2,2,1,0,0));
        assertEquals("99",redis.opsForValue().get("seckill:stock:10"));
        assertEquals(1L,redis.opsForStream().size(stream));
        assertFalse(redis.opsForSet().isMember("seckill:order:10","2"));
    }
    @Test void globalSlidingWindowCountsSameMillisecondRequestsSeparately() {
        warmup.warmIfAbsent(10L);
        assertEquals(0L,reserve(1,1,100,2,0));
        assertEquals(0L,reserve(2,2,100,2,0));
        assertEquals(5L,reserve(3,3,100,2,0));
        assertEquals(2L,redis.opsForZSet().size(global));
        assertEquals("98",redis.opsForValue().get("seckill:stock:10"));
        assertEquals(2L,redis.opsForStream().size(stream));
    }
    @Test void userWindowAlsoLimitsRepeatedAttemptsWithoutExtraOrders() {
        warmup.warmIfAbsent(10L);
        assertEquals(0L,reserve(1,1,100,100,2));
        assertEquals(2L,reserve(2,1,100,100,2));
        assertEquals(5L,reserve(3,1,100,100,2));
        assertEquals("99",redis.opsForValue().get("seckill:stock:10"));
        assertEquals(1L,redis.opsForStream().size(stream));
    }
    @Test void genericLimiterCountsSameMillisecondMembersAndRejectsTheNextRequest() throws Exception {
        byte[] script=StreamUtils.copyToByteArray(new ClassPathResource("limiter.lua").getInputStream());
        byte[] key=global.getBytes(StandardCharsets.UTF_8), sequence=(global+":seq").getBytes(StandardCharsets.UTF_8);
        List<Object> results=redis.executePipelined((RedisCallback<Object>) connection -> {
            for(int i=0;i<201;i++) connection.eval(script,ReturnType.INTEGER,2,key,sequence,
                    "60".getBytes(StandardCharsets.UTF_8),"200".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        assertEquals(200L,results.get(199)); assertEquals(0L,results.get(200));
        assertEquals(200L,redis.opsForZSet().size(global));
        Set<Double> scores=new HashSet<>();
        redis.opsForZSet().rangeWithScores(global,0,-1).forEach(value->scores.add(value.getScore()));
        assertTrue(scores.size()<200,"pipelined requests must exercise repeated millisecond scores");
    }
    VoucherOrderServiceImpl ingress() {
        VoucherOrderServiceImpl service=new VoucherOrderServiceImpl();
        RedisIdWorker ids=mock(RedisIdWorker.class); when(ids.nextId("order")).thenReturn(1001L,1002L);
        ReflectionTestUtils.setField(service,"redisIdWorker",ids);
        ReflectionTestUtils.setField(service,"stringRedisTemplate",redis);
        ReflectionTestUtils.setField(service,"outboxStream",stream);
        UserDTO user=new UserDTO(); user.setId(1L); UserHolder.saveUser(user);
        return service;
    }
    @Test void requestDoesNotRecreateMissingStockFromLaggingDatabase() {
        VoucherOrderServiceImpl service=ingress();
        try {
            Result result=service.seckillVoucher(10L);
            assertFalse(result.getSuccess()); assertTrue(result.getErrorMsg().contains("尚未就绪"));
            assertFalse(redis.hasKey("seckill:stock:10")); assertFalse(redis.hasKey(stream));
            verifyNoInteractions(vouchers,orders);
        } finally { UserHolder.removeUser(); }
    }
    @Test void staleLocalMarkerCannotRebuildLostStockOrPurchaseEligibility() {
        warmup.warmIfAbsent(10L);
        VoucherOrderServiceImpl service=ingress();
        try {
            assertTrue(service.seckillVoucher(10L).getSuccess());
            // Simulate all Redis business keys lost after Kafka accepted an event but before DB commit.
            redis.delete(Arrays.asList("seckill:stock:10","seckill:order:10",stream));
            clearInvocations(vouchers,orders);
            Result result=service.seckillVoucher(10L);
            assertFalse(result.getSuccess()); assertTrue(result.getErrorMsg().contains("尚未就绪"));
            assertFalse(redis.hasKey("seckill:stock:10")); assertFalse(redis.hasKey("seckill:order:10"));
            assertFalse(redis.hasKey(stream)); verifyNoInteractions(vouchers,orders);
        } finally { UserHolder.removeUser(); }
    }
}
