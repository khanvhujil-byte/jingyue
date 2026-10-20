package com.hmdp.like;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Redis Lua execution, isolated ephemeral server; no business keys are used. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikeProjectionAckRedisTest {
    private Process server;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private DefaultRedisScript<Long> ack;
    private String stream, pending100, pending116;

    @BeforeAll void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path dir = Files.createTempDirectory("hmdp-like-ack-");
        server = new ProcessBuilder(System.getProperty("hmdp.test.redis-executable", "redis-server"),
                "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                "--dir", dir.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("redis.log").toFile()).start();
        factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { redis.opsForValue().set("test:ready", "1"); break; }
            catch (RuntimeException error) {
                if (!server.isAlive() || System.nanoTime() > deadline) throw error;
                Thread.sleep(25);
            }
        }
        ack = new DefaultRedisScript<>();
        ack.setLocation(new ClassPathResource("like_project_ack.lua")); ack.setResultType(Long.class);
    }
    @AfterAll void stop() {
        if (factory != null) factory.destroy();
        if (server != null) server.destroy();
    }
    @BeforeEach void names() {
        String prefix = "test:" + UUID.randomUUID() + ":like:{p4}:";
        stream = prefix + "outbox"; pending100 = prefix + "pending:100"; pending116 = prefix + "pending:116";
    }
    private RecordId append(String blog) { return redis.opsForStream().add(stream, Collections.singletonMap("blogId", blog)); }
    private Long acknowledge(String... args) { return redis.execute(ack, Arrays.asList(stream, pending100, pending116), (Object[]) args); }

    @Test void deletesCommittedEventsAndDecrementsExactPerBlogPendingOnlyOnce() {
        RecordId one = append("100"), two = append("100"), three = append("116");
        redis.opsForValue().set(pending100, "2"); redis.opsForValue().set(pending116, "1");
        String[] args = {one.getValue(), "2", "100", two.getValue(), "2", "100", three.getValue(), "3", "116"};
        assertEquals(3L, acknowledge(args));
        assertEquals(0L, redis.opsForStream().size(stream));
        assertEquals("0", redis.opsForValue().get(pending100)); assertEquals("0", redis.opsForValue().get(pending116));
        assertEquals(0L, acknowledge(args));
        assertEquals("0", redis.opsForValue().get(pending100));
    }

    @Test void oneInvalidPendingMarkerLeavesWholeBatchUntouched() {
        RecordId one = append("100"), two = append("116");
        redis.opsForValue().set(pending100, "1"); redis.opsForValue().set(pending116, "invalid");
        assertEquals(-6L, acknowledge(one.getValue(), "2", "100", two.getValue(), "3", "116"));
        assertEquals(2L, redis.opsForStream().size(stream));
        assertEquals("1", redis.opsForValue().get(pending100));
    }

    @Test void missingOrInsufficientMarkerRetainsEvent() {
        RecordId one = append("100");
        assertEquals(-5L, acknowledge(one.getValue(), "2", "100"));
        redis.opsForValue().set(pending100, "0");
        assertEquals(-7L, acknowledge(one.getValue(), "2", "100"));
        assertEquals(1L, redis.opsForStream().size(stream));
    }

    @Test void mismatchedBlogCannotDecrementAnotherBlogsMarker() {
        RecordId one = append("100");
        redis.opsForValue().set(pending100, "1"); redis.opsForValue().set(pending116, "1");
        assertEquals(-4L, acknowledge(one.getValue(), "3", "116"));
        assertEquals(1L, redis.opsForStream().size(stream));
        assertEquals("1", redis.opsForValue().get(pending116));
    }

    @Test void duplicateRecordWithinOneAckDoesNotUnderflowMarker() {
        RecordId one = append("100"); redis.opsForValue().set(pending100, "1");
        assertEquals(1L, acknowledge(one.getValue(), "2", "100", one.getValue(), "2", "100"));
        assertEquals("0", redis.opsForValue().get(pending100));
    }
}
