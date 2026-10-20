package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import com.hmdp.limiter.exception.RateLimitException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RateLimitException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result handleRateLimit(RateLimitException e) {
        return Result.fail(e.getMessage()==null ? "操作过于频繁，请稍后重试" : e.getMessage());
    }

    // 全局异常处理捕捉，记录通用日志，返回给前端通用响应 Result.fail("服务器异常")
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器繁忙，请稍后重试");
    }
}
