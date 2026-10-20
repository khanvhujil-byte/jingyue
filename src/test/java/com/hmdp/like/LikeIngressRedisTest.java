package com.hmdp.like;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real Lua/Redis, isolated ephemeral process; verifies accepted work and recovery boundaries. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikeIngressRedisTest {
    private Process server;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private DefaultRedisScript<List> change, read, loginRefresh;
    private DefaultRedisScript<Long> seed;
    private String prefix;
    private List<String> keys;

    @BeforeAll void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path dir = Files.createTempDirectory("hmdp-like-ingress-");
        server = new ProcessBuilder(System.getProperty("hmdp.test.redis-executable", "redis-server"),
                "--bind", "127.0.0.1", "--port", Integer.toString(port), "--save", "", "--appendonly", "no",
                "--dir", dir.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("redis.log").toFile()).start();
        factory = new LettuceConnectionFactory("127.0.0.1", port); factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { redis.opsForValue().set("test:ready", "1"); break; }
            catch (RuntimeException error) {
                if (!server.isAlive() || System.nanoTime() > deadline) throw error;
                Thread.sleep(25);
            }
        }
        change = listScript("like_change.lua"); read = listScript("like_read.lua"); loginRefresh = listScript("login_refresh.lua");
        seed = new DefaultRedisScript<>(); seed.setLocation(new ClassPathResource("like_seed.lua")); seed.setResultType(Long.class);
    }
    @AfterAll void stop() { if (factory != null) factory.destroy(); if (server != null) server.destroy(); }
    @BeforeEach void names() {
        prefix = "test:" + UUID.randomUUID() + ":like:{p4}:";
        keys = Arrays.asList(prefix + "meta:100", prefix + "users:100", prefix + "outbox", prefix + "pending:100",
                prefix + "rate", prefix + "user-rate:8");
    }
    private DefaultRedisScript<List> listScript(String name) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(name)); script.setResultType(List.class); return script;
    }
    private Long seed(String... args) { return redis.execute(seed, keys.subList(0, 4), (Object[]) args); }
    private void empty() { assertEquals(1L, seed("6", "0", "6")); }
    private List<Long> change(long user, String target) { return change(user, target, 10000, 10000, 100000); }
    @SuppressWarnings("unchecked") private List<Long> change(long user, String target, int partitionLimit, int userLimit, int backlog) {
        List<String> ownKeys = new ArrayList<>(keys); ownKeys.set(5, prefix + "user-rate:" + user);
        return redis.execute(change, ownKeys, "100", Long.toString(user), target, Integer.toString(backlog),
                Integer.toString(partitionLimit), Integer.toString(userLimit), UUID.randomUUID().toString());
    }
    private List<Long> read(long user) { return redis.execute(read, keys.subList(0, 2), Long.toString(user)); }
    private String meta(String field) { return (String) redis.opsForHash().get(keys.get(0), field); }
    private void assertState(int total, int users, int events) {
        assertEquals(Integer.toString(total), meta("count"));
        assertEquals((long) users, redis.opsForZSet().size(keys.get(1)));
        assertEquals(Integer.toString(events), redis.opsForValue().get(keys.get(3)));
        assertEquals((long) events, redis.opsForStream().size(keys.get(2)));
    }

    @Test void concurrentAcceptedLikesAtomicallyMaintainStateCountPendingAndOutbox() throws Exception {
        empty();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        try {
            for (long user = 1; user <= 32; user++) {
                final long id = user;
                results.add(pool.submit(() -> { go.await(); return change(id, "1"); }));
            }
            go.countDown();
            for (Future<List<Long>> result : results) assertEquals(1L, result.get(10, TimeUnit.SECONDS).get(0));
        } finally { pool.shutdownNow(); }
        assertState(38, 32, 32); assertEquals("32", meta("version"));
        Set<String> versions = new HashSet<>(), users = new HashSet<>();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(keys.get(2), org.springframework.data.domain.Range.unbounded());
        assertNotNull(records);
        for (MapRecord<String, Object, Object> record : records) {
            assertEquals("100", record.getValue().get("blogId")); assertEquals("1", record.getValue().get("liked"));
            versions.add(record.getValue().get("version").toString()); users.add(record.getValue().get("userId").toString());
        }
        assertEquals(32, versions.size()); assertEquals(32, users.size());
    }

    @Test void explicitDesiredStateIsIdempotentButToggleStillToggles() {
        empty();
        assertEquals(Arrays.asList(1L, 1L, 7L, 1L), change(8, "1"));
        assertEquals(Arrays.asList(0L, 1L, 7L, 1L), change(8, "1"));
        assertState(7, 1, 1);
        assertEquals(Arrays.asList(1L, 0L, 6L, 2L), change(8, "0"));
        assertEquals(Arrays.asList(0L, 0L, 6L, 2L), change(8, "0"));
        assertState(6, 0, 2);
        assertEquals(1L, change(8, "toggle").get(1)); assertEquals(0L, change(8, "toggle").get(1));
        assertState(6, 0, 4);
    }

    @Test void immediateReadSeesAcceptedStateBeforeDatabaseProjection() {
        assertEquals(Collections.emptyList(), read(8));
        empty(); assertEquals(Arrays.asList(6L, 0L), read(8));
        change(8, "1");
        assertEquals(Arrays.asList(7L, 1L), read(8)); assertEquals(Arrays.asList(7L, 0L), read(9));
        change(8, "0"); assertEquals(Arrays.asList(6L, 0L), read(8));
    }

    @Test void deletingMetadataCannotRebuildFromLaggingDatabaseWhilePendingExists() {
        empty(); change(8, "1"); redis.delete(keys.get(0));
        assertEquals(Collections.singletonList(-2L), change(9, "1"));
        assertEquals(-1L, seed("6", "0", "6"));
        assertFalse(redis.hasKey(keys.get(0)));
        assertEquals(1L, redis.opsForZSet().size(keys.get(1)));
        assertEquals("1", redis.opsForValue().get(keys.get(3)));
        assertEquals(1L, redis.opsForStream().size(keys.get(2)));
    }

    @Test void liveOutboxEventStillPreventsRebuildIfPendingMarkerWasLost() {
        empty(); change(8, "1"); redis.delete(Arrays.asList(keys.get(0), keys.get(3)));
        assertEquals(-1L, seed("6", "0", "6"));
        assertFalse(redis.hasKey(keys.get(0)));
        assertEquals(1L, redis.opsForZSet().size(keys.get(1)));
        assertEquals(1L, redis.opsForStream().size(keys.get(2)));
    }

    @Test void existingMetadataCannotBeOverwrittenByAnotherInitializer() {
        empty(); change(8, "1");
        assertEquals(1L, seed("100", "999", "100"));
        assertState(7, 1, 1); assertEquals("1", meta("version"));
    }

    @Test void wrongOutboxTypeDoesNotPartiallyMutateStateOrRateKeys() {
        empty(); redis.opsForList().rightPush(keys.get(2), "wrong-type");
        assertThrows(RuntimeException.class, () -> change(8, "1"));
        assertEquals("6", meta("count")); assertEquals("0", meta("version"));
        assertEquals("0", redis.opsForValue().get(keys.get(3)));
        assertFalse(redis.hasKey(keys.get(1))); assertFalse(redis.hasKey(keys.get(4))); assertFalse(redis.hasKey(keys.get(5)));
        assertEquals("wrong-type", redis.opsForList().index(keys.get(2), 0));
    }

    @Test void wrongPendingTypeAndCorruptCountFailBeforeAcceptedStateChanges() {
        empty(); redis.delete(keys.get(3)); redis.opsForHash().put(keys.get(3), "x", "1");
        assertThrows(RuntimeException.class, () -> change(8, "1"));
        assertEquals("6", meta("count")); assertFalse(redis.hasKey(keys.get(2)));
        redis.delete(keys.get(3)); redis.opsForValue().set(keys.get(3), "0");
        redis.opsForHash().put(keys.get(0), "count", "7"); // No corresponding user relation.
        assertThrows(RuntimeException.class, () -> change(8, "1"));
        assertEquals("0", redis.opsForValue().get(keys.get(3))); assertFalse(redis.hasKey(keys.get(2)));
    }

    @Test void historicalBaselineCannotBeCancelledByAnUnknownUser() {
        assertEquals(1L, seed("7", "19", "6", "8", "10"));
        assertEquals(Arrays.asList(0L, 0L, 7L, 19L), change(9, "0"));
        assertEquals(Arrays.asList(1L, 0L, 6L, 20L), change(8, "0"));
        assertEquals(Arrays.asList(0L, 0L, 6L, 20L), change(8, "0"));
        assertState(6, 0, 1); assertEquals("6", meta("baseline"));
    }

    @Test void backlogLimitRejectsNewChangesWithoutCreatingAnExtraEvent() {
        empty(); assertEquals(1L, change(8, "1", 100, 100, 1).get(0));
        assertEquals(Collections.singletonList(-4L), change(9, "1", 100, 100, 1));
        assertState(7, 1, 1); assertEquals("1", meta("version"));
    }

    @Test void sameMillisecondRequestsHaveDistinctRateMembersAndAreActuallyLimited() throws Exception {
        empty();
        String source = StreamUtils.copyToString(new ClassPathResource("like_change.lua").getInputStream(), StandardCharsets.UTF_8);
        String clockLine = "local clock=redis.call('TIME')";
        assertTrue(source.contains(clockLine), "固定时钟注入位置变化，需要更新测试");
        DefaultRedisScript<List> fixedClock = new DefaultRedisScript<>(
                source.replace(clockLine, "local clock={'1800000000','123000'}"), List.class);
        for (int user = 1; user <= 8; user++) {
            List<String> own = new ArrayList<>(keys); own.set(5, prefix + "user-rate:" + user);
            List result = redis.execute(fixedClock, own, "100", Integer.toString(user), "1", "1000", "8", "100",
                    "unique-request-" + user);
            assertNotNull(result); assertEquals(1L, result.get(0));
        }
        List result = redis.execute(fixedClock, keys, "100", "9", "1", "1000", "8", "100", "unique-request-9");
        assertEquals(Collections.singletonList(-3L), result);
        assertEquals(8L, redis.opsForZSet().size(keys.get(4)));
        assertEquals(8L, redis.opsForZSet().count(keys.get(4), 1800000000123D, 1800000000123D));
        assertState(14, 8, 8);
    }

    @Test void invalidSeedScoreCannotLeaveAUsersSetPartiallyRebuilt() {
        redis.opsForZSet().add(keys.get(1), "88", 1);
        redis.opsForValue().set(keys.get(3), "0");
        assertThrows(RuntimeException.class, () -> seed("8", "0", "6", "8", "10", "9", "bad-score"));
        assertFalse(redis.hasKey(keys.get(0)));
        assertEquals(Collections.singleton("88"), redis.opsForZSet().range(keys.get(1), 0, -1));
        assertEquals("0", redis.opsForValue().get(keys.get(3)));
    }

    @Test void invalidSeedCardinalityAndDuplicateUsersAreRejectedBeforeWriting() {
        assertThrows(RuntimeException.class, () -> seed("8", "0", "6", "8", "10"));
        assertThrows(RuntimeException.class, () -> seed("8", "0", "6", "8", "10", "8", "11"));
        assertThrows(RuntimeException.class, () -> seed("5", "0", "6"));
        assertFalse(redis.hasKey(keys.get(0))); assertFalse(redis.hasKey(keys.get(1)));
        assertFalse(redis.hasKey(keys.get(3)));
    }

    @Test void validLoginReadRenewsExistingSessionAndReturnsItsUser() {
        String token = prefix + "token";
        Map<String, String> user = new HashMap<>(); user.put("id", "8"); user.put("nickName", "测试用户");
        redis.opsForHash().putAll(token, user); redis.expire(token, 2, TimeUnit.SECONDS);
        List data = redis.execute(loginRefresh, Collections.singletonList(token), "60");
        assertNotNull(data); assertEquals(4, data.size());
        assertTrue(data.containsAll(Arrays.asList("id", "8", "nickName", "测试用户")));
        Long ttl = redis.getExpire(token, TimeUnit.MILLISECONDS);
        assertNotNull(ttl); assertTrue(ttl > 55000 && ttl <= 60000);
        assertEquals("8", redis.opsForHash().get(token, "id"));
    }

    @Test void expiredLoginCannotBeRevivedByRefresh() throws Exception {
        String token = prefix + "token";
        redis.opsForHash().put(token, "id", "8"); redis.expire(token, 30, TimeUnit.MILLISECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (Boolean.TRUE.equals(redis.hasKey(token))) {
            assertTrue(System.nanoTime() < deadline, "短期登录态未按预期过期"); Thread.sleep(10);
        }
        assertEquals(Collections.emptyList(), redis.execute(loginRefresh, Collections.singletonList(token), "60"));
        assertFalse(redis.hasKey(token)); assertEquals(-2L, redis.getExpire(token));
    }

    @Test void unknownLoginTokenDoesNotCreateAnEmptySession() {
        String token = prefix + "unknown-token";
        assertEquals(Collections.emptyList(), redis.execute(loginRefresh, Collections.singletonList(token), "60"));
        assertFalse(redis.hasKey(token));
    }
}
