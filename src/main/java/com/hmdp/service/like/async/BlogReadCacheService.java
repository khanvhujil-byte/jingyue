package com.hmdp.service.like.async;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.UserMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Cache public metadata only; personalized state is read atomically after copying. */
@Service
@ConditionalOnProperty(name="hmdp.like.async-enabled",havingValue="true")
public class BlogReadCacheService {
    private final Cache<Long,String> local=Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(5,TimeUnit.SECONDS).build();
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final BlogMapper blogs;
    private final UserMapper users;
    public BlogReadCacheService(StringRedisTemplate redis,RedissonClient redisson,BlogMapper blogs,UserMapper users) {
        this.redis=redis; this.redisson=redisson; this.blogs=blogs; this.users=users;
    }
    public Blog get(long id) {
        String json=local.get(id,this::load);
        return "".equals(json)?null:JSONUtil.toBean(json,Blog.class);
    }
    private String load(long id) {
        String key="cache:blog:public:"+id;
        String json=redis.opsForValue().get(key);
        if(json!=null) return json;
        RLock lock=redisson.getLock("lock:blog:public:"+id);
        lock.lock();
        try {
            json=redis.opsForValue().get(key);
            if(json!=null) return json;
            Blog blog=blogs.selectById(id);
            if(blog==null) { redis.opsForValue().set(key,"",15,TimeUnit.SECONDS); return ""; }
            User author=users.selectById(blog.getUserId());
            if(author!=null) blog.setName(author.getNickName()).setIcon(author.getIcon());
            blog.setIsLike(null); blog.setLiked(null);
            json=JSONUtil.toJsonStr(blog);
            redis.opsForValue().set(key,json,60+ThreadLocalRandom.current().nextInt(30),TimeUnit.SECONDS);
            return json;
        } finally { if(lock.isHeldByCurrentThread()) lock.unlock(); }
    }
    public void invalidate(long id) { local.invalidate(id); redis.delete("cache:blog:public:"+id); }
}
