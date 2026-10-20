package com.hmdp.config;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class RedissonConfig {
    @Value("${spring.redis.host:127.0.0.1}") private String host;
    @Value("${spring.redis.port:6379}") private int port;
    @Value("${spring.redis.database:8}") private int database;
    @Value("${spring.redis.password:}") private String password;
    @Bean public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer().setAddress("redis://" + host + ":" + port).setDatabase(database);
        if (password != null && !password.isEmpty()) server.setPassword(password);
        return Redisson.create(config);
    }
}
