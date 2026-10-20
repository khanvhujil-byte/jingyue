package com.jingyue;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final Db db; private final RedisOps ops;
    @Value("${jingyue.development:false}") private boolean development;
    private static final Set<String> DEMO=new HashSet<>(Arrays.asList("13900000001","13900000002","13900000003","13900000004"));
    private final DefaultRedisScript<Long> consume=new DefaultRedisScript<>(
        "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end; redis.call('DEL',KEYS[1]); return 1",Long.class);
    public AuthController(Db db,RedisOps ops) { this.db=db; this.ops=ops; }
    @PostMapping("/code") public Map<String,Object> code(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        String phone=Values.required(body,"phone",20);
        ops.limit("code-ip:"+req.getRemoteAddr(),2); ops.limit("code:"+phone,1);
        if(!development||!DEMO.contains(phone)) throw new ApiFault(403,"DEMO_ACCOUNT_ONLY","演示登录仅支持指定演示账号，不发送真实短信");
        String code=String.format("%06d",new SecureRandom().nextInt(1000000));
        ops.redis.opsForValue().set("jy:v1:code:"+phone,code,120,TimeUnit.SECONDS);
        return Values.map("developmentOnly",true,"code",code,"expiresIn",120,"message","演示验证码，不发送短信");
    }
    @PostMapping("/login") public Map<String,Object> login(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        String phone=Values.required(body,"phone",20), code=Values.required(body,"code",6);
        ops.limit("login-ip:"+req.getRemoteAddr(),10); ops.limit("login:"+phone,5);
        if(!development||!DEMO.contains(phone)) throw new ApiFault(403,"DEMO_ACCOUNT_ONLY","请使用指定演示账号");
        Long consumed=ops.redis.execute(consume,Collections.singletonList("jy:v1:code:"+phone),code);
        if(consumed==null||consumed!=1) throw new ApiFault(400,"INVALID_CODE","验证码无效或已使用");
        Map<String,Object> row=db.need("SELECT id,nick_name,role FROM tb_user WHERE phone=?",phone);
        Map<String,Object> user=Values.map("id",Values.text(row,"id"),"name",row.get("nick_name"),"role",row.get("role"));
        String token=Values.token(),key="jy:v1:login:"+token;
        ops.redis.execute(new DefaultRedisScript<Long>("redis.call('HSET',KEYS[1],'user',ARGV[1]); redis.call('EXPIRE',KEYS[1],1800); return 1",Long.class),Collections.singletonList(key),Values.json(user));
        return Values.map("token",token,"user",user,"expiresIn",1800);
    }
    @GetMapping("/me") public Map<String,Object> me() { return Identity.current(); }
    @PostMapping("/logout") public Map<String,Object> logout(HttpServletRequest req) {
        ops.redis.delete("jy:v1:login:"+AuthFilter.token(req)); return Values.map("loggedOut",true);
    }
}
