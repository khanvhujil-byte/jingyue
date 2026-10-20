package com.hmdp.service.like.async;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Durable Redis outbox -> MySQL projection. Each SQL batch commits before XDEL.
 * A crash between those operations replays safely because tombstones retain versions.
 * This is table sharding in one datasource, not automatic cross-database transactions.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.like.async-enabled", havingValue = "true")
public class LikeBatchProjector {
    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>();
    static {
        ACK_SCRIPT.setScriptText(com.hmdp.utils.RedisScriptContent.read("like_project_ack.lua"));
        ACK_SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final JdbcTemplate jdbc;
    private final LikeShardRouter router;
    private final TransactionTemplate transaction;
    @Value("${hmdp.like.projector-batch-size:1000}")
    private int batchSize = 1000;
    private final long[] idleUntil=new long[16],lastWarning=new long[16];

    public LikeBatchProjector(StringRedisTemplate redis, RedissonClient redisson, JdbcTemplate jdbc,
                              LikeShardRouter router, PlatformTransactionManager transactionManager) {
        this.redis = redis;
        this.redisson = redisson;
        this.jdbc = jdbc;
        this.router = router;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(15);
    }

    @Scheduled(fixedDelayString = "${hmdp.like.projector-delay-ms:20}")
    public void scheduledProject() { projectOnce(); }

    /** At most one bounded batch per partition, preserving fairness when one blog is hot. */
    public int projectOnce() {
        int completed = 0;
        for (int partition = 0; partition < router.shardCount(); partition++) {
            long now=System.currentTimeMillis();
            if(!router.takeDirty(partition)&&now<idleUntil[partition])continue;
            RLock lock = redisson.getLock(router.prefixForPartition(partition) + "projector-lock");
            boolean acquired = false;
            try {
                // No leaseTime: Redisson watchdog extends ownership while this process lives.
                acquired = lock.tryLock();
                if (!acquired) continue;
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                        router.prefixForPartition(partition) + "outbox", Range.unbounded(),
                        Limit.limit().count(Math.max(1, Math.min(batchSize, 1000))));
                if (records != null && !records.isEmpty()) {completed += projectBatch(partition, records);idleUntil[partition]=0;}
                else idleUntil[partition]=now+250;
            } catch (Exception error) {
                // Malformed data or SQL/Redis failure is retained. Other partitions can progress.
                idleUntil[partition]=now+1000;
                if(now-lastWarning[partition]>10000){lastWarning[partition]=now;log.error("点赞分片{}落库失败，原事件保留；类型{}",partition,error.getClass().getSimpleName());}
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
        return completed;
    }

    /**
     * Also exposed for maintenance/tests. Database locks and versions keep this safe even if
     * two workers process a replay concurrently or the Redis lock expires during a long pause.
     * Returns acknowledged record count, not count of state changes.
     */
    public int projectBatch(int partition, List<MapRecord<String, Object, Object>> records) {
        router.tableForPartition(partition);
        if (records.isEmpty()) return 0;
        if (records.size() > 1000) throw new IllegalArgumentException("点赞批次超过上限");
        LinkedHashMap<Pair, Event> newest = new LinkedHashMap<>();
        String stream = router.prefixForPartition(partition) + "outbox";
        for (MapRecord<String, Object, Object> record : records) {
            if (!stream.equals(record.getStream())) throw new IllegalArgumentException("点赞事件所在分片错误");
            Event event = parse(partition, record.getValue());
            Event previous = newest.get(event.pair);
            if (previous == null || previous.version < event.version) newest.put(event.pair, event);
            else if (previous.version == event.version && previous.liked != event.liked)
                throw new IllegalArgumentException("同一点赞版本出现冲突状态");
        }
        transaction.executeWithoutResult(status -> apply(partition, new ArrayList<>(newest.values())));
        // Never delete before transaction.execute returns: commit can fail too.
        Map<Long, Integer> keyIndexes = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        keys.add(stream);
        List<String> ackArgs = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            long blogId = number(record.getValue(), "blogId");
            if (!keyIndexes.containsKey(blogId)) {
                keys.add(router.prefixForPartition(partition) + "pending:" + blogId);
                keyIndexes.put(blogId, keys.size()); // Lua KEYS are one-based.
            }
            Collections.addAll(ackArgs, record.getId().getValue(), keyIndexes.get(blogId).toString(), Long.toString(blogId));
        }
        Long acknowledged = redis.execute(ACK_SCRIPT, keys, ackArgs.toArray());
        if (acknowledged == null || acknowledged < 0) throw new IllegalStateException("点赞事件确认失败，事件保留等待重试");
        return records.size();
    }

    private void apply(int partition, List<Event> candidates) {
        candidates.sort(Comparator.comparingLong((Event e) -> e.pair.blogId).thenComparingLong(e -> e.pair.userId));
        SortedSet<Long> blogIds = new TreeSet<>();
        for (Event event : candidates) blogIds.add(event.pair.blogId);
        String blogsSql = "SELECT id,liked FROM tb_blog WHERE id IN (" + placeholders(blogIds.size()) + ") ORDER BY id FOR UPDATE";
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query(blogsSql, blogIds.toArray(), rs -> { counts.put(rs.getLong("id"), rs.getLong("liked")); });
        if (counts.size() != blogIds.size()) throw new IllegalStateException("点赞事件引用的笔记不存在，事件保留");
        SortedSet<Long> userIds = new TreeSet<>();
        for (Event event : candidates) userIds.add(event.pair.userId);
        List<Long> foundUsers = jdbc.queryForList("SELECT id FROM tb_user WHERE id IN ("
                + placeholders(userIds.size()) + ")", userIds.toArray(), Long.class);
        if (foundUsers.size() != userIds.size()) throw new IllegalStateException("点赞事件引用的用户不存在，事件保留");

        String pairs = pairPlaceholders(candidates.size());
        Object[] pairArgs = pairArgs(candidates);
        Map<Pair, State> states = new HashMap<>();
        jdbc.query("SELECT blog_id,user_id,version,liked FROM " + router.tableForPartition(partition)
                        + " WHERE (blog_id,user_id) IN (" + pairs + ")", pairArgs,
                rs -> { states.put(new Pair(rs.getLong("blog_id"), rs.getLong("user_id")),
                        new State(rs.getLong("version"), rs.getBoolean("liked"))); });
        Set<Pair> currentLikes = new HashSet<>();
        jdbc.query("SELECT blog_id,user_id FROM tb_blog_like WHERE (blog_id,user_id) IN (" + pairs + ")", pairArgs,
                rs -> { currentLikes.add(new Pair(rs.getLong("blog_id"), rs.getLong("user_id"))); });

        List<Event> accepted = new ArrayList<>(), additions = new ArrayList<>(), removals = new ArrayList<>(), relikes = new ArrayList<>();
        Map<Long, Integer> deltas = new TreeMap<>();
        for (Event event : candidates) {
            State state = states.get(event.pair);
            if (state != null && state.version > event.version) continue;
            if (state != null && state.version == event.version) {
                if (state.liked != event.liked) throw new IllegalStateException("点赞版本与状态冲突，事件保留");
                continue;
            }
            boolean existing = currentLikes.contains(event.pair);
            if (state != null && state.liked != existing)
                throw new IllegalStateException("点赞关系与版本表不一致，请先对账");
            accepted.add(event);
            if (existing == event.liked) {
                // A newer true event is a real re-like: ingress does not emit no-op events.
                // The intervening unlike may have been coalesced out of this batch.
                if (existing) relikes.add(event);
                continue;
            }
            (event.liked ? additions : removals).add(event);
            deltas.merge(event.pair.blogId, event.liked ? 1 : -1, Integer::sum);
        }
        if (accepted.isEmpty()) return;

        if (!additions.isEmpty()) {
            String sql = "INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES " + values(additions.size(), 3);
            List<Object> args = new ArrayList<>();
            for (Event event : additions) Collections.addAll(args, event.pair.userId, event.pair.blogId, event.time);
            requireAffected(jdbc.update(sql, args.toArray()), additions.size());
        }
        if (!removals.isEmpty()) requireAffected(jdbc.update(
                "DELETE FROM tb_blog_like WHERE (blog_id,user_id) IN (" + pairPlaceholders(removals.size()) + ")",
                pairArgs(removals)), removals.size());

        if (!relikes.isEmpty()) {
            StringBuilder sql = new StringBuilder("UPDATE tb_blog_like SET created_at=CASE ");
            List<Object> times = new ArrayList<>();
            for (Event event : relikes) {
                sql.append("WHEN blog_id=? AND user_id=? THEN ? ");
                Collections.addAll(times, event.pair.blogId, event.pair.userId, event.time);
            }
            sql.append("ELSE created_at END WHERE (blog_id,user_id) IN (")
                    .append(pairPlaceholders(relikes.size())).append(")");
            Collections.addAll(times, pairArgs(relikes));
            jdbc.update(sql.toString(), times.toArray());
        }

        List<Object> args = new ArrayList<>();
        for (Event event : accepted) Collections.addAll(args, event.pair.blogId, event.pair.userId,
                event.version, event.liked ? 1 : 0, event.time);
        jdbc.update("INSERT INTO " + router.tableForPartition(partition)
                + "(blog_id,user_id,version,liked,liked_at) VALUES " + values(accepted.size(), 5)
                + " ON DUPLICATE KEY UPDATE version=VALUES(version),liked=VALUES(liked),liked_at=VALUES(liked_at)", args.toArray());

        // One counter UPDATE per batch for all affected blogs. A hot blog pays one write, not N.
        if (!deltas.isEmpty()) {
            StringBuilder sql = new StringBuilder("UPDATE tb_blog SET liked=liked + CASE id ");
            args.clear();
            for (Map.Entry<Long, Integer> delta : deltas.entrySet()) {
                long next = counts.get(delta.getKey()) + delta.getValue();
                if (next < 0 || next > Integer.MAX_VALUE) throw new IllegalStateException("点赞计数超出范围，事务回滚");
                sql.append("WHEN ? THEN ? ");
                Collections.addAll(args, delta.getKey(), delta.getValue());
            }
            sql.append("ELSE 0 END WHERE id IN (").append(placeholders(deltas.size())).append(")");
            args.addAll(deltas.keySet());
            jdbc.update(sql.toString(), args.toArray());
        }
    }

    private Event parse(int partition, Map<Object, Object> fields) {
        try {
            long blog = number(fields, "blogId"), user = number(fields, "userId"),
                    version = number(fields, "version"), time = number(fields, "time");
            String liked = String.valueOf(fields.get("liked"));
            if (blog <= 0 || user <= 0 || version <= 0 || time < 0 || router.partition(blog) != partition
                    || !("1".equals(liked) || "0".equals(liked))) throw new IllegalArgumentException();
            return new Event(new Pair(blog, user), version, "1".equals(liked), time);
        } catch (RuntimeException error) { throw new IllegalArgumentException("点赞事件字段无效，原事件保留", error); }
    }
    private long number(Map<Object, Object> fields, String name) { return Long.parseLong(String.valueOf(fields.get(name))); }
    private static void requireAffected(int actual, int expected) {
        if (actual != expected) throw new IllegalStateException("点赞关系写入数量不符，事务回滚");
    }
    private static String placeholders(int n) { return String.join(",", Collections.nCopies(n, "?")); }
    private static String values(int n, int columns) { return String.join(",", Collections.nCopies(n, "(" + placeholders(columns) + ")")); }
    private static String pairPlaceholders(int n) { return values(n, 2); }
    private static Object[] pairArgs(List<Event> events) {
        List<Object> args = new ArrayList<>();
        for (Event event : events) Collections.addAll(args, event.pair.blogId, event.pair.userId);
        return args.toArray();
    }
    private static class Pair {
        final long blogId, userId;
        Pair(long blogId, long userId) { this.blogId = blogId; this.userId = userId; }
        @Override public boolean equals(Object other) {
            return other instanceof Pair && ((Pair) other).blogId == blogId && ((Pair) other).userId == userId;
        }
        @Override public int hashCode() { return Objects.hash(blogId, userId); }
    }
    private static class State {
        final long version; final boolean liked;
        State(long version, boolean liked) { this.version = version; this.liked = liked; }
    }
    private static class Event extends State {
        final Pair pair; final long time;
        Event(Pair pair, long version, boolean liked, long time) { super(version, liked); this.pair = pair; this.time = time; }
    }
}
