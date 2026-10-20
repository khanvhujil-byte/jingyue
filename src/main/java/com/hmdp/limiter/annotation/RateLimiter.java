package com.hmdp.limiter.annotation;

import java.lang.annotation.*;

/**
    【滑动窗口限流】-1
    自定义限流注解：注解属性包含 限流key前缀、时间窗口大小、时间窗口内允许的请求数、限流提示信息、限流维度
    window时间窗口大小 和 limit时间窗口内允许的请求数 这两个参数 取决于自己的系统的并发量
        ● 例如可以设置 1s 内 允许 100 个请求，则QPS最高是100
        ● 例如可以设置 0.5s 允许100个请求，则QPS最高是200
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {
    /**
     * 限流key前缀
     */
    String key() default "rate_limit:";

    /**
     * 时间窗口大小（秒）
     */
    int window() default 1;

    /**
     * 时间窗口内允许的请求数
     */
    int limit() default 2000;

    /**
     * 限流提示信息
     */
    String message() default "系统繁忙，请稍后再试";

    /**
     * 限流维度（默认按方法限流）
     */
    LimitType type() default LimitType.METHOD;

    enum LimitType {
        /**
         * 按调用方IP限流
         */
        IP,
        /**
         * 按用户ID限流
         */
        USER,
        /**
         * 按方法限流/全局限流（默认）
         */
        METHOD
    }
}