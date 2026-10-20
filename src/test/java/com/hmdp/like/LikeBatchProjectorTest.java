package com.hmdp.like;

import com.hmdp.service.like.async.LikeBatchProjector;
import com.hmdp.service.like.async.LikeShardRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual production DDL/SQL and transactions. Business Redis/MySQL is never contacted. */
class LikeBatchProjectorTest {
    final LikeShardRouter router = new LikeShardRouter();
    final int partition = router.partition(100L);
    final String stream = router.prefix(100L) + "outbox";
    JdbcTemplate jdbc;
    StringRedisTemplate redis;
    RedissonClient redisson;
    StreamOperations<String, Object, Object> streams;
    LikeBatchProjector projector;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:projector-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE tb_blog(id BIGINT PRIMARY KEY,liked INT NOT NULL)");
        jdbc.execute("CREATE TABLE tb_user(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO tb_blog VALUES(100,6),(101,0)");
        StringBuilder users = new StringBuilder("INSERT INTO tb_user VALUES");
        for (int user = 1; user <= 1200; user++) users.append(user == 1 ? "(" : ",(").append(user).append(")");
        jdbc.update(users.toString());
        new ResourceDatabasePopulator(new ClassPathResource("db/blog_like.sql"),
                new ClassPathResource("db/blog_like_async.sql")).execute(ds);
        jdbc.update("INSERT INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(100,6)");
        redis = mock(StringRedisTemplate.class);
        streams = mock(StreamOperations.class);
        when(redis.<Object, Object>opsForStream()).thenReturn(streams);
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        projector = new LikeBatchProjector(redis, redisson, jdbc, router, new DataSourceTransactionManager(ds));
    }

    private MapRecord<String, Object, Object> event(long user, long version, boolean liked) {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("blogId", "100"); fields.put("userId", Long.toString(user));
        fields.put("version", Long.toString(version)); fields.put("liked", liked ? "1" : "0");
        fields.put("time", Long.toString(version * 10));
        return MapRecord.create(stream, fields).withId(RecordId.of(version + "-" + user));
    }
    private void project(MapRecord<String, Object, Object>... records) { projector.projectBatch(partition, Arrays.asList(records)); }
    private int count() { return jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=100", Integer.class); }
    private int relations() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like", Integer.class); }
    private int stateRows() { return jdbc.queryForObject("SELECT COUNT(*) FROM " + router.table(100L), Integer.class); }

    @Test void routesStablyAndRejectsTableIdentifierInjection() {
        assertEquals(16, router.shardCount());
        assertEquals(4, partition);
        assertEquals("like:{p4}:", router.prefix(100L));
        assertEquals("tb_blog_like_state_04", router.table(100L));
        assertThrows(IllegalArgumentException.class, () -> router.tableForPartition(16));
        assertThrows(IllegalArgumentException.class, () -> router.tableForPartition(-1));
    }

    @Test void multiUserBatchPreservesHistoricalBaselineAndReplayDoesNotDoubleCount() {
        List<MapRecord<String, Object, Object>> events = new ArrayList<>();
        for (long user = 1; user <= 32; user++) events.add(event(user, user, true));
        assertEquals(32, projector.projectBatch(partition, events));
        assertEquals(38, count()); assertEquals(32, relations()); assertEquals(32, stateRows());
        projector.projectBatch(partition, events);
        assertEquals(38, count()); assertEquals(32, relations());
    }

    @Test void batchCoalescesNewestStateEvenWhenDeliveryOrderIsReversed() {
        project(event(8, 3, true), event(8, 1, true), event(8, 2, false));
        assertEquals(7, count()); assertEquals(1, relations());
        assertEquals(3L, jdbc.queryForObject("SELECT version FROM " + router.table(100L), Long.class));
    }

    @Test void unlikeTombstonePreventsOldLikeFromResurrecting() {
        project(event(8, 2, false));
        project(event(8, 1, true));
        assertEquals(6, count()); assertEquals(0, relations()); assertEquals(1, stateRows());
        assertEquals(0, jdbc.queryForObject("SELECT liked FROM " + router.table(100L), Integer.class));
        project(event(8, 3, true));
        assertEquals(7, count()); assertEquals(1, relations());
    }

    @Test void coalescedUnlikeAndRelikeUpdateTimeWithoutChangingCounter() {
        project(event(8, 1, true));
        project(event(8, 2, false), event(8, 3, true));
        assertEquals(7, count()); assertEquals(1, relations());
        assertEquals(30L, jdbc.queryForObject("SELECT created_at FROM tb_blog_like", Long.class));
        assertEquals(3L, jdbc.queryForObject("SELECT version FROM " + router.table(100L), Long.class));
        project(event(8, 3, true)); project(event(8, 2, false));
        assertEquals(7, count()); assertEquals(1, relations());
        assertEquals(30L, jdbc.queryForObject("SELECT created_at FROM tb_blog_like", Long.class));
    }

    @Test void importedCanonicalRelationshipCanBeCancelledWithoutAStateRow() {
        jdbc.update("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(8,100,1)");
        jdbc.update("UPDATE tb_blog SET liked=7 WHERE id=100");
        project(event(8, 1, false));
        assertEquals(6, count()); assertEquals(0, relations()); assertEquals(1, stateRows());
    }

    @Test void counterFailureRollsBackRelationshipAndVersionAndDoesNotAcknowledge() {
        jdbc.execute("ALTER TABLE tb_blog ADD CONSTRAINT reject_increment CHECK(id<>100 OR liked=6)");
        assertThrows(RuntimeException.class, () -> project(event(8, 1, true)));
        assertEquals(6, count()); assertEquals(0, relations()); assertEquals(0, stateRows());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
        jdbc.execute("ALTER TABLE tb_blog DROP CONSTRAINT reject_increment");
        project(event(8, 1, true));
        assertEquals(7, count()); assertEquals(1, relations());
    }

    @Test void acknowledgementFailureAfterCommitReplaysSafely() {
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new IllegalStateException("模拟确认时断网")).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> project(event(8, 1, true)));
        assertEquals(7, count()); assertEquals(1, relations());
        project(event(8, 1, true));
        assertEquals(7, count()); assertEquals(1, relations());
    }

    @Test void malformedEventRejectsWholeBatchWithoutDeletingGoodEvents() {
        MapRecord<String, Object, Object> bad = event(9, 2, true);
        bad.getValue().put("liked", "yes");
        assertThrows(IllegalArgumentException.class, () -> project(event(8, 1, true), bad));
        assertEquals(6, count()); assertEquals(0, relations());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test void missingBlogOrUserIsRetainedForInvestigation() {
        MapRecord<String, Object, Object> bad = event(8, 1, true);
        bad.getValue().put("blogId", "116"); // Same partition, absent blog.
        assertThrows(IllegalStateException.class, () -> project(bad));
        assertThrows(IllegalStateException.class, () -> project(event(99999, 2, true)));
        assertEquals(0, relations()); assertEquals(0, stateRows());
    }

    @Test void conflictingSameVersionFailsClosedInsteadOfChoosingAnArbitraryState() {
        assertThrows(IllegalArgumentException.class, () -> project(event(8, 1, true), event(8, 1, false)));
        project(event(8, 1, true));
        assertThrows(IllegalStateException.class, () -> project(event(8, 1, false)));
        assertEquals(7, count()); assertEquals(1, relations());
    }

    @Test void concurrentReplayIsSafeEvenWithoutDistributedLock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> {
                start.await(); project(event(8, 1, true)); return null;
            }));
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(7, count()); assertEquals(1, relations()); assertEquals(1, stateRows());
        } finally { pool.shutdownNow(); }
    }

    @Test void boundedProjectOnceDrainsBacklogAcrossPasses() {
        List<MapRecord<String, Object, Object>> first = new ArrayList<>(), second = new ArrayList<>();
        for (long user = 1; user <= 1200; user++) (user <= 1000 ? first : second).add(event(user, user, true));
        AtomicInteger passes = new AtomicInteger();
        when(streams.range(eq(stream), any(), any())).thenAnswer(invocation -> {
            int pass = passes.getAndIncrement();
            return pass == 0 ? first : pass == 1 ? second : Collections.emptyList();
        });
        assertEquals(1000, projector.projectOnce()); assertEquals(1006, count());
        assertEquals(200, projector.projectOnce()); assertEquals(1206, count());
        assertEquals(0, projector.projectOnce()); assertEquals(1200, relations());
        verify(redisson, atLeastOnce()).getLock(router.prefix(100L) + "projector-lock");
    }
}
