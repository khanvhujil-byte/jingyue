package com.jingyue;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final RedisOps ops;
    private final DefaultRedisScript<String> read=new DefaultRedisScript<>(
        "local v=redis.call('HGET',KEYS[1],'user'); if not v then return nil end; redis.call('EXPIRE',KEYS[1],1800); return v",String.class);
    public AuthFilter(RedisOps ops) { this.ops=ops; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        if(!req.getRequestURI().startsWith("/api/v1/")) { chain.doFilter(req,res); return; }
        String requestId=UUID.randomUUID().toString(); req.setAttribute("requestId",requestId);
        res.setHeader("X-Request-Id",requestId); res.setHeader("Cache-Control","no-store");
        try {
            String token=token(req);
            if(!token.isEmpty()) {
                String data=ops.redis.execute(read,Collections.singletonList("jy:v1:login:"+token));
                if(data!=null) Identity.set(Values.object(data));
            }
            if(!isPublic(req)) Identity.id();
            chain.doFilter(req,res);
        } catch(ApiFault e) { error(res,e,requestId); }
        catch(org.springframework.data.redis.RedisConnectionFailureException e) { error(res,new ApiFault(503,"IDENTITY_UNAVAILABLE","服务暂不可用，请稍后重试"),requestId); }
        finally { Identity.clear(); }
    }
    private boolean isPublic(HttpServletRequest req) {
        String p=req.getRequestURI();
        if("POST".equals(req.getMethod()) && (p.equals("/api/v1/auth/code")||p.equals("/api/v1/auth/login"))) return true;
        return "GET".equals(req.getMethod()) && (
            p.equals("/api/v1/health")||p.equals("/api/v1/attractions")||p.matches("/api/v1/attractions/[0-9]+(?:/ticket-calendar)?")
            ||p.equals("/api/v1/travel-notes")||p.matches("/api/v1/travel-notes/[0-9]+")||p.matches("/api/v1/media/[A-Za-z0-9_-]+"));
    }
    public static String token(HttpServletRequest req) {
        String value=req.getHeader("Authorization");
        if(value==null||!value.startsWith("Bearer ")) return "";
        String token=value.substring(7); return token.matches("[A-Za-z0-9_-]{43}")?token:"";
    }
    public static void error(HttpServletResponse res,ApiFault fault,String requestId) throws IOException {
        res.setStatus(fault.status); res.setContentType("application/json;charset=UTF-8");
        if(fault.status==429) res.setHeader("Retry-After","1");
        res.getWriter().write(Values.json(Values.map("code",fault.code,"message",fault.getMessage(),"requestId",requestId,"retryAfter",fault.status==429?1:null)));
    }
}
