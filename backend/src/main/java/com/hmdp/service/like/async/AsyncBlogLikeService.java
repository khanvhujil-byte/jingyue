package com.hmdp.service.like.async;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.limiter.exception.RateLimitException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name="hmdp.like.async-enabled",havingValue="true")
public class AsyncBlogLikeService {
    private static final DefaultRedisScript<List> CHANGE=script("like_change.lua",List.class);
    private static final DefaultRedisScript<List> READ=script("like_read.lua",List.class);
    private static final DefaultRedisScript<Long> SEED=script("like_seed.lua",Long.class);
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final JdbcTemplate jdbc;
    private final LikeShardRouter router;
    // Cache existence/initialization only. Never cache personalized state or stock.
    private final Cache<Long,Boolean> ready=Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(5,TimeUnit.MINUTES).build();
    private final Cache<Long,Boolean> missing=Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(5,TimeUnit.SECONDS).build();
    @Value("${hmdp.like.max-pending-events:100000}") private long maxPending=100000;
    @Value("${hmdp.like.partition-rate-per-second:8000}") private int partitionRate=8000;
    @Value("${hmdp.like.user-rate-per-second:20}") private int userRate=20;

    public AsyncBlogLikeService(StringRedisTemplate redis, RedissonClient redisson, JdbcTemplate jdbc, LikeShardRouter router) {
        this.redis=redis; this.redisson=redisson; this.jdbc=jdbc; this.router=router;
    }
    private static <T> DefaultRedisScript<T> script(String file,Class<T> type) {
        DefaultRedisScript<T> s=new DefaultRedisScript<>(); s.setScriptText(com.hmdp.utils.RedisScriptContent.read(file)); s.setResultType(type); return s;
    }
    public Result change(Long blogId,Long userId,Boolean desired) {
        if(blogId==null||blogId<=0||userId==null||userId<=0) return Result.fail("缺少笔记或用户信息");
        if(!ensureReady(blogId)) return Result.fail("笔记不存在");
        String p=router.prefix(blogId);
        List<String> keys=Arrays.asList(p+"meta:"+blogId,p+"users:"+blogId,p+"outbox",p+"pending:"+blogId,
                p+"rate",p+"rate:user:"+userId);
        for(int attempt=0;attempt<2;attempt++) {
            List result=redis.execute(CHANGE,keys,blogId.toString(),userId.toString(),
                    desired==null?"toggle":(desired?"1":"0"),Long.toString(maxPending),Integer.toString(partitionRate),
                    Integer.toString(userRate),UUID.randomUUID().toString());
            if(result==null||result.isEmpty()) throw new IllegalStateException("点赞受理结果未知，请查询状态");
            int code=((Number)result.get(0)).intValue();
            if(code==-2) { ready.invalidate(blogId); if(!ensureReady(blogId)) return Result.fail("笔记不存在"); continue; }
            if(code==-3) throw new RateLimitException("操作过于频繁，请稍后重试");
            if(code==-4) throw new RateLimitException("点赞人数较多，请稍后重试");
            if(code!=0&&code!=1) throw new IllegalStateException("点赞受理失败");
            if(code==1)router.changed(blogId);
            return Result.ok(((Number)result.get(1)).intValue()==1);
        }
        throw new IllegalStateException("点赞状态尚未就绪");
    }
    public boolean ensureReady(long blogId) {
        if(missing.getIfPresent(blogId)!=null) return false;
        Boolean ok=ready.get(blogId,this::initialize);
        if(!Boolean.TRUE.equals(ok)) { missing.put(blogId,Boolean.TRUE); ready.invalidate(blogId); }
        return Boolean.TRUE.equals(ok);
    }
    private boolean initialize(long blogId) {
        String p=router.prefix(blogId),meta=p+"meta:"+blogId;
        RLock lock=redisson.getLock(p+"init:"+blogId);
        RLock projectionLock=redisson.getLock(p+"projector-lock");
        lock.lock();
        boolean projectionLocked=false;
        try {
            if(Boolean.TRUE.equals(redis.hasKey(meta))) return true;
            // Freeze commit+ACK while taking the bootstrap snapshot. A stale snapshot
            // must not become eligible merely because its outstanding events just drained.
            projectionLock.lock(); projectionLocked=true;
            String pending=redis.opsForValue().get(p+"pending:"+blogId);
            if(pending!=null&&!"0".equals(pending)) throw new IllegalStateException("该笔记还有待落库事件，暂不能重建");
            List<Long> counts=jdbc.queryForList("SELECT liked FROM tb_blog WHERE id=?",Long.class,blogId);
            if(counts.isEmpty()) return false;
            List<Map<String,Object>> likes=jdbc.queryForList("SELECT user_id,created_at FROM tb_blog_like WHERE blog_id=? ORDER BY created_at,user_id",blogId);
            List<Long> bases=jdbc.queryForList("SELECT anonymous_likes FROM tb_blog_like_baseline WHERE blog_id=?",Long.class,blogId);
            long baseline=bases.isEmpty()?0:bases.get(0);
            if(counts.get(0)!=baseline+likes.size()) throw new IllegalStateException("历史点赞计数不一致，请先对账");
            Long version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM "+router.table(blogId)+" WHERE blog_id=?",Long.class,blogId);
            List<String> args=new ArrayList<>();
            Collections.addAll(args,Long.toString(counts.get(0)),Long.toString(version==null?0:version),Long.toString(baseline));
            for(Map<String,Object> row:likes) Collections.addAll(args,row.get("user_id").toString(),row.get("created_at").toString());
            Long seeded=redis.execute(SEED,Arrays.asList(meta,p+"users:"+blogId,p+"outbox",p+"pending:"+blogId),args.toArray());
            if(seeded==null||seeded!=1) throw new IllegalStateException("该笔记还有待落库事件，暂不能从数据库恢复状态");
            return true;
        } finally {
            if(projectionLocked&&projectionLock.isHeldByCurrentThread()) projectionLock.unlock();
            if(lock.isHeldByCurrentThread()) lock.unlock();
        }
    }
    public void decorate(Blog blog,Long userId) {
        for(int i=0;i<2;i++) {
            if(!ensureReady(blog.getId())) return;
            String p=router.prefix(blog.getId());
            List values=redis.execute(READ,Arrays.asList(p+"meta:"+blog.getId(),p+"users:"+blog.getId()),userId==null?"":userId.toString());
            if(values!=null&&values.size()==2) {
                blog.setLiked(((Number)values.get(0)).intValue()); blog.setIsLike(((Number)values.get(1)).intValue()==1); return;
            }
            ready.invalidate(blog.getId());
        }
        throw new IllegalStateException("点赞状态尚未就绪");
    }
    public boolean isLiked(Long blogId,Long userId) {
        Blog blog=new Blog().setId(blogId); decorate(blog,userId); return Boolean.TRUE.equals(blog.getIsLike());
    }
    public List<Long> firstLikers(Long blogId) {
        if(!ensureReady(blogId)) return Collections.emptyList();
        Set<String> ids=redis.opsForZSet().range(router.prefix(blogId)+"users:"+blogId,0,4);
        return ids==null?Collections.emptyList():ids.stream().map(Long::valueOf).collect(Collectors.toList());
    }
}
