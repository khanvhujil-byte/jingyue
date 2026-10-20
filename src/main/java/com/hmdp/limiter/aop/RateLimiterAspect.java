package com.hmdp.limiter.aop;

import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.limiter.exception.RateLimitException;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Arrays;

/**
     【滑动窗口限流】-2
     限流切面：拦截所有加了自定义限流注解的接口方法
        获取到自定义注解上的参数:  限流key前缀、时间窗口大小、时间窗口内允许的请求数、限流提示信息、限流维度
        执行限流Lua脚本(限流实现算法：滑动窗口限流)
 */
@Slf4j
@Aspect
@Component
public class RateLimiterAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 限流Lua脚本
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT;

    static {
        SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>();
        SLIDING_WINDOW_SCRIPT.setScriptText(com.hmdp.utils.RedisScriptContent.read("limiter.lua"));
        SLIDING_WINDOW_SCRIPT.setResultType(Long.class);
    }

    // 前置拦截 注解了rateLimiter的方法
    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) {
        // 获取注解上的参数
        String key = rateLimiter.key();
        long window = rateLimiter.window();
        long limit = rateLimiter.limit();

        // 构建完整的限流key
        String fullKey = buildRateLimitKey(point, rateLimiter, key);
        // 执行限流脚本
        Long result = executeSlidingWindowScript(fullKey, window, limit);

        // 如果返回0表示被限流
        if (result == null || result <= 0) {
            throw new RateLimitException(rateLimiter.message());
        }
    }

    /**
     * 执行滑动窗口限流脚本
     *
     * @param key    限流key
     * @param window 时间窗口（秒）
     * @param limit  限制请求数量
     * @return 当前窗口内请求数量计数（如果被限流返回0）
     */
    public Long executeSlidingWindowScript(String key, Long window, Long limit) {
        if (window == null || limit == null || window <= 0 || limit <= 0)
            throw new IllegalArgumentException("限流配置必须为正整数");
        return stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                Arrays.asList(key, key + ":seq"),
                window.toString(), limit.toString()
        );
    }

    /**
     * 构建限流key
     */
    private String buildRateLimitKey(JoinPoint point, RateLimiter rateLimiter, String baseKey) {
        StringBuilder keyBuilder = new StringBuilder(baseKey);

        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();

        // key默认 添加类名和方法名
        keyBuilder.append(method.getDeclaringClass().getName())
                .append(":")
                .append(method.getName());

        // 根据限流类型添加额外维度（为什么要这样构建？可以先看lua脚本的写法，限流的本质是利用了Redis的ZSet数据类型的一个key，因此key的构建决定了限流的维度）
        switch (rateLimiter.type()) {
            case IP:
                // 如果是按IP限流，那么在key的拼接上需要加上 ip 信息
                keyBuilder.append(":ip:").append(getClientIp());
                break;
            case USER:
                // 如果是按用户限流，那么在key的拼接上需要加上 用户 信息
                keyBuilder.append(":user:").append(getCurrentUserId());
                break;
            case METHOD:
            default:
                // 方法级限流使用默认key（默认key已带上类名和方法名  ）
                break;
        }

        return keyBuilder.toString();
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp() {
        ServletRequestAttributes attributes=(ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
        if(attributes==null) throw new IllegalStateException("无法获取请求来源");
        // Forwarding headers require a separately configured trusted proxy.
        return attributes.getRequest().getRemoteAddr();
    }

    /**
     * 获取当前用户ID（需要根据实际系统实现）
     */
    private String getCurrentUserId() {
        UserDTO user=UserHolder.getUser();
        if(user==null || user.getId()==null) throw new RateLimitException("请先登录后再操作");
        return user.getId().toString();
    }
}
