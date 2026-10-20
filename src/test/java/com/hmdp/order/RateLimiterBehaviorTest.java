package com.hmdp.order;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.dto.UserDTO;
import com.hmdp.limiter.annotation.RateLimiter;
import com.hmdp.limiter.aop.RateLimiterAspect;
import com.hmdp.limiter.exception.RateLimitException;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RateLimiterBehaviorTest {
    static class Target {
        @RateLimiter(type=RateLimiter.LimitType.USER,limit=2) public void limited() {}
    }
    @RestController static class DeniedController {
        @GetMapping("/limited") public void limited() { throw new RateLimitException("操作过于频繁，请稍后重试"); }
    }
    @AfterEach void clear() { UserHolder.removeUser(); }
    JoinPoint point() throws Exception {
        JoinPoint point=mock(JoinPoint.class); MethodSignature signature=mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature); when(signature.getMethod()).thenReturn(Target.class.getMethod("limited"));
        return point;
    }
    RateLimiter annotation() throws Exception { return Target.class.getMethod("limited").getAnnotation(RateLimiter.class); }
    RateLimiterAspect aspect(StringRedisTemplate redis) {
        RateLimiterAspect aspect=new RateLimiterAspect(); ReflectionTestUtils.setField(aspect,"stringRedisTemplate",redis); return aspect;
    }
    void user(long id) { UserDTO user=new UserDTO(); user.setId(id); UserHolder.saveUser(user); }
    @Test void authenticatedUsersGetSeparateKeysAndMissingContextIsRejected() throws Exception {
        StringRedisTemplate redis=mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(RedisScript.class),anyList(),anyString(),anyString());
        RateLimiterAspect aspect=aspect(redis);
        user(8L); aspect.doBefore(point(),annotation());
        user(9L); aspect.doBefore(point(),annotation());
        ArgumentCaptor<List> keys=ArgumentCaptor.forClass(List.class);
        verify(redis,times(2)).execute(any(RedisScript.class),keys.capture(),eq("1"),eq("2"));
        assertTrue(keys.getAllValues().get(0).get(0).toString().endsWith(":user:8"));
        assertTrue(keys.getAllValues().get(1).get(0).toString().endsWith(":user:9"));
        UserHolder.removeUser(); assertThrows(RateLimitException.class,()->aspect.doBefore(point(),annotation()));
    }
    @Test void nullRedisResultDoesNotBypassRateLimit() throws Exception {
        RateLimiterAspect aspect=aspect(mock(StringRedisTemplate.class));
        user(8L);
        assertThrows(RateLimitException.class,()->aspect.doBefore(point(),annotation()));
    }
    @Test void rejectedRequestReturns429WithChineseMessage() throws Exception {
        MockMvcBuilders.standaloneSetup(new DeniedController()).setControllerAdvice(new WebExceptionAdvice()).build()
                .perform(get("/limited")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("操作过于频繁，请稍后重试"));
    }
}
