package com.jingyue;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.jingyue.Values.*;

@Component
public class ApiMetrics extends OncePerRequestFilter {
    final ConcurrentMap<String,LongAdder> counters=new ConcurrentHashMap<>();final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();final Db db;final RedisOps ops;
    public ApiMetrics(Db db,RedisOps ops){this.db=db;this.ops=ops;}
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{if(!req.getRequestURI().startsWith("/api/v1/")){chain.doFilter(req,res);return;}long start=System.nanoTime();int n=active.incrementAndGet();peak.accumulateAndGet(n,Math::max);try{chain.doFilter(req,res);}finally{active.decrementAndGet();String scope=req.getRequestURI().contains("/ticket-requests")?"受理与查询":req.getRequestURI().contains("payment")?"支付":req.getRequestURI().contains("redemption")?"核销":"其他";counters.computeIfAbsent(scope+":"+res.getStatus(),k->new LongAdder()).increment();counters.computeIfAbsent(scope+":耗时毫秒",k->new LongAdder()).add((System.nanoTime()-start)/1000000);}}
    public Object snapshot(){Map<String,Object> c=new TreeMap<>();counters.forEach((k,v)->c.put(k,v.sum()));Object pool=null;if(db.jdbc.getDataSource() instanceof com.zaxxer.hikari.HikariDataSource){com.zaxxer.hikari.HikariPoolMXBean bean=((com.zaxxer.hikari.HikariDataSource)db.jdbc.getDataSource()).getHikariPoolMXBean();if(bean!=null)pool=map("active",bean.getActiveConnections(),"idle",bean.getIdleConnections(),"waiting",bean.getThreadsAwaitingConnection());}Runtime rt=Runtime.getRuntime();Object redis=null;try(org.springframework.data.redis.connection.RedisConnection connection=ops.redis.getConnectionFactory().getConnection()){java.util.Properties info=connection.info("memory");redis=map("usedBytes",info.getProperty("used_memory"),"maxBytes",info.getProperty("maxmemory"));}return map("http",c,"activeHandlers",active.get(),"peakHandlers",peak.get(),"databasePool",pool,"heapUsedBytes",rt.totalMemory()-rt.freeMemory(),"heapCommittedBytes",rt.totalMemory(),"jvmThreads",java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount(),"redisMemory",redis);}
    @Scheduled(fixedDelay=30000) public void alert(){long now=System.currentTimeMillis();try{long closes=db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE state='PENDING_PAYMENT' AND pay_deadline<?",now-30000),releases=db.count("SELECT COUNT(*) FROM tb_ticket_outbox WHERE event_type='RELEASE' AND state='PENDING' AND created_at<?",now-30000);if(closes+releases>0)org.slf4j.LoggerFactory.getLogger(getClass()).warn("票务任务超时：待关闭{}，待归还{}；保留原证据等待处理",closes,releases);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("运行告警采样失败，不能解释为积压为零");}}
}
