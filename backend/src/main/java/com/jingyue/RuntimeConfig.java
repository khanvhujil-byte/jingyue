package com.jingyue;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.SeekToCurrentBatchErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RuntimeConfig {
    @Bean public org.springframework.data.redis.listener.RedisMessageListenerContainer catalogNotifications(org.springframework.data.redis.connection.RedisConnectionFactory factory,CatalogCache cache) {
        org.springframework.data.redis.listener.RedisMessageListenerContainer c=new org.springframework.data.redis.listener.RedisMessageListenerContainer();
        c.setConnectionFactory(factory);c.addMessageListener(cache,new org.springframework.data.redis.listener.ChannelTopic(CatalogCache.CHANNEL));return c;
    }
    @Bean(destroyMethod="shutdown") public RedissonClient redisson(
        @Value("${spring.redis.host}") String host,@Value("${spring.redis.port}") int port,
        @Value("${spring.redis.password:}") String password) {
        Config config=new Config();
        org.redisson.config.SingleServerConfig server=config.useSingleServer().setAddress("redis://"+host+":"+port).setDatabase(0);
        if(!password.isEmpty()) server.setPassword(password);
        return Redisson.create(config);
    }
    @Bean public ConcurrentKafkaListenerContainerFactory<String,String> kafkaListenerContainerFactory(ConsumerFactory<String,String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String,String> factory=new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory); factory.setBatchListener(true); factory.setConcurrency(4);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setBatchErrorHandler(new SeekToCurrentBatchErrorHandler());
        return factory;
    }
    @Bean public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("jingyue-worker-");scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(10); return scheduler;
    }
}
