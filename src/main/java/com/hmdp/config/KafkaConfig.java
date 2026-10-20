package com.hmdp.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.SeekToCurrentErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.listener.SeekToCurrentBatchErrorHandler;

import java.util.Collections;
import java.util.Map;

@Configuration
public class KafkaConfig {
    @Value("${hmdp.seckill.consumer-concurrency:4}")
    private int batchConcurrency = 4;
    @Value("${hmdp.seckill.batch-size:500}")
    private int batchSize = 500;
    @Bean
    public DefaultKafkaProducerFactory<String, String> producerFactory(KafkaProperties properties) {
        Map<String, Object> props = properties.buildProducerProperties();
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(DefaultKafkaProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> seckillVoucherOrderKafkaListenerContainerFactory(
            KafkaProperties properties) {
        Map<String, Object> props = properties.buildConsumerProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Retain failed records, including malformed messages. Never silently skip an order.
        SeekToCurrentErrorHandler handler = new SeekToCurrentErrorHandler(
                new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setClassifications(Collections.emptyMap(), true);
        factory.setErrorHandler(handler);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> seckillVoucherBatchKafkaListenerContainerFactory(
            KafkaProperties properties) {
        Map<String,Object> props = properties.buildConsumerProperties();
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, batchSize);
        ConcurrentKafkaListenerContainerFactory<String,String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(props));
        factory.setBatchListener(true);
        factory.setConcurrency(batchConcurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        SeekToCurrentBatchErrorHandler handler = new SeekToCurrentBatchErrorHandler();
        handler.setBackOff(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        factory.setBatchErrorHandler(handler);
        return factory;
    }
}
