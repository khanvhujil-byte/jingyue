package com.jingyue;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.github.benmanes.caffeine.cache.*;
import org.redisson.api.*;
import java.util.*;
import java.util.concurrent.*;
import static com.jingyue.Values.*;

@Component
public class CatalogCache implements MessageListener {
    public static final String CHANNEL="jy:v1:catalog:invalidate";
    final Db db;final RedisOps ops;final RedissonClient redisson;
    final Cache<Long,Map<String,Object>> local;final int redisTtl;
    final Map<Long,Long> observed=new ConcurrentHashMap<>();
    final DefaultRedisScript<Long> fill=new DefaultRedisScript<>("local v=tonumber(redis.call('GET',KEYS[1]) or '0'); if v>tonumber(ARGV[1]) then return 0 end; redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3]); return 1",Long.class);
    final DefaultRedisScript<Long> evict=new DefaultRedisScript<>("local v=tonumber(redis.call('GET',KEYS[1]) or '0'); if v<=tonumber(ARGV[1]) then redis.call('SET',KEYS[1],ARGV[1]); redis.call('DEL',KEYS[2]); end; return 1",Long.class);
    public CatalogCache(Db db,RedisOps ops,RedissonClient redisson){this(db,ops,redisson,30,120);}
    @org.springframework.beans.factory.annotation.Autowired
    public CatalogCache(Db db,RedisOps ops,RedissonClient redisson,@org.springframework.beans.factory.annotation.Value("${jingyue.cache.local-ttl-seconds:30}")int localTtl,@org.springframework.beans.factory.annotation.Value("${jingyue.cache.redis-ttl-seconds:120}")int redisTtl){
        if(localTtl<1||localTtl>300||redisTtl<5||redisTtl>3600)throw new IllegalStateException("缓存有效期超出配置范围");this.db=db;this.ops=ops;this.redisson=redisson;this.redisTtl=redisTtl;local=Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(localTtl,TimeUnit.SECONDS).build();
    }
    public Map<String,Object> detail(long id) {
        Map<String,Object> cached=local.getIfPresent(id);if(cached!=null)return cached;
        String key="jy:v1:catalog:{"+id+"}";
        String shared=ops.redis.opsForValue().get(key+":value");
        if(shared!=null)return remember(id,object(shared));
        RLock lock=redisson.getLock(key+":lock");boolean held=false;
        try {
            held=lock.tryLock(1,TimeUnit.SECONDS);
            if(!held)throw new ApiFault(503,"CACHE_BUSY","景点信息正在更新，请稍后重试");
            shared=ops.redis.opsForValue().get(key+":value");if(shared!=null)return remember(id,object(shared));
            Map<String,Object> row=db.need(CatalogController.DETAIL+" AND s.id=?",id);
            Map<String,Object> value=CatalogController.attraction(row);
            List<Map<String,Object>> products=new ArrayList<>();for(Map<String,Object> p:db.rows("SELECT id,name,description FROM tb_ticket_product WHERE attraction_id=? AND status='ACTIVE' ORDER BY id",id))products.add(map("productId",text(p,"id"),"name",p.get("name"),"description",p.get("description")));value.put("ticketProducts",products);
            Long saved=ops.redis.execute(fill,Arrays.asList(key+":version",key+":value"),text(value,"contentVersion"),json(value),Integer.toString(redisTtl+ThreadLocalRandom.current().nextInt(31)));
            if(saved==null||saved==0)throw new ApiFault(503,"CONTENT_CHANGED","景点信息已更新，请刷新");
            return remember(id,value);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new ApiFault(503,"CACHE_BUSY","请求已中断");}
        finally{if(held&&lock.isHeldByCurrentThread())lock.unlock();}
    }
    synchronized Map<String,Object> remember(long id,Map<String,Object> v){if(number(v,"contentVersion")<observed.getOrDefault(id,0L))throw new ApiFault(503,"CONTENT_CHANGED","景点信息已更新，请刷新");local.put(id,v);return v;}
    public void invalidate(long id,long version){String key="jy:v1:catalog:{"+id+"}";ops.redis.execute(evict,Arrays.asList(key+":version",key+":value"),Long.toString(version));onVersion(id,version);ops.redis.convertAndSend(CHANNEL,json(map("id",Long.toString(id),"version",version)));}
    synchronized void onVersion(long id,long v){observed.merge(id,v,Math::max);Map<String,Object> old=local.getIfPresent(id);if(old!=null&&number(old,"contentVersion")<=v)local.invalidate(id);}
    public void onMessage(Message m,byte[] pattern){Map<String,Object> e=object(new String(m.getBody(),java.nio.charset.StandardCharsets.UTF_8));onVersion(number(e,"id"),number(e,"version"));}
}
