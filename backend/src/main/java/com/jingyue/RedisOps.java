package com.jingyue;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;

@Component
public class RedisOps {
    public static final String PREFIX="jy:v1:{ticket}:";
    public static final String STATE=PREFIX+"state", STREAM=PREFIX+"outbox", HEALTH=PREFIX+"health";
    public final StringRedisTemplate redis;
    private final RedisScript<Long> limit=script("limit.lua",Long.class);
    public RedisOps(StringRedisTemplate redis) { this.redis=redis; }
    public static <T> DefaultRedisScript<T> script(String name,Class<T> type) {
        return scriptText(com.hmdp.utils.RedisScriptContent.read("lua/"+name),type);
    }
    public static <T> DefaultRedisScript<T> scriptText(String text,Class<T> type) {
        return new StaticScript<>(text,type);
    }
    /** Immutable script avoids DefaultRedisScript's synchronized getSha1() hot path. */
    static final class StaticScript<T> extends DefaultRedisScript<T> {
        final String text; final Class<T> type; final String sha1;
        StaticScript(String text,Class<T> type){super(text,type);this.text=text;this.type=type;this.sha1=sha1(text);}
        public String getSha1(){return sha1;}
        static String sha1(String value){try{byte[] b=java.security.MessageDigest.getInstance("SHA-1").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder s=new StringBuilder(40);for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    }
    public void limit(String scope,int max) {
        Long result=redis.execute(limit,Collections.singletonList("jy:v1:rate:"+scope),Integer.toString(max),Values.token());
        if(result==null||result!=1) throw new ApiFault(429,"RATE_LIMITED","操作较频繁，请稍后重试");
    }
    public String field(String key) { Object v=redis.opsForHash().get(STATE,key); return v==null?"":v.toString(); }
}
