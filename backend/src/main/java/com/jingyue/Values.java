package com.jingyue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class Values {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();
    private Values() {}
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) m.put(pairs[i].toString(),pairs[i+1]);
        return m;
    }
    public static String text(Map<?,?> m,String k) { Object v=m.get(k); return v==null?"":v.toString(); }
    public static long number(Map<?,?> m,String k) { return Long.parseLong(text(m,k)); }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("数据无法编码",e); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(String value) {
        try { return JSON.readValue(value,LinkedHashMap.class); } catch(Exception e) { throw new ApiFault(400,"INVALID_DATA","数据格式不正确"); }
    }
    public static String token() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String id() { long n; do { n=RANDOM.nextLong() & Long.MAX_VALUE; } while(n==0); return Long.toString(n); }
    public static String digest(String input) {
        try { byte[] b=MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray(),out=new char[b.length*2];for(int i=0;i<b.length;i++){int x=b[i]&255;out[i*2]=hex[x>>>4];out[i*2+1]=hex[x&15];}return new String(out);
        } catch(GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static String instant(long millis) { return Instant.ofEpochMilli(millis).atZone(ZONE).toOffsetDateTime().toString(); }
    public static long time(Object value) {
        try { return OffsetDateTime.parse(value.toString()).toInstant().toEpochMilli(); }
        catch(Exception e) { throw new ApiFault(400,"INVALID_TIME","时间必须包含时区"); }
    }
    public static String required(Map<?,?> body,String key,int max) {
        String value=text(body,key).trim();
        if(value.isEmpty()||value.length()>max) throw new ApiFault(400,"INVALID_PARAMETER","请检查参数："+key);
        return value;
    }
    public static long positive(Map<?,?> body,String key) {
        try { long v=number(body,key); if(v<=0) throw new NumberFormatException(); return v; }
        catch(Exception e) { throw new ApiFault(400,"INVALID_PARAMETER","请检查参数："+key); }
    }
}
