package com.fraud.detection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisConfig — Redis Template Configuration
 *
 * Provides a custom RedisTemplate with JSON serialization.
 *
 * Connection factory is intentionally NOT defined here.
 * Spring Boot auto-configures a LettuceConnectionFactory using:
 *   spring.data.redis.host     → ${REDIS_HOST}    (e.g. "redis" in Docker)
 *   spring.data.redis.port     → ${REDIS_PORT}    (e.g. 6379)
 *   spring.data.redis.lettuce.pool.* → pool settings from application-docker.yml
 *
 * Defining a custom LettuceConnectionFactory bean here would bypass the YAML
 * pool configuration entirely, silently disabling connection pooling.
 * By delegating to Spring Boot auto-config, commons-pool2 is used and the
 * pool settings (max-active, max-idle, min-idle, max-wait) are fully applied.
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate<String, Object>
     *
     * Key   → StringRedisSerializer              (clean string keys, e.g. "fraud:txId")
     * Value → GenericJackson2JsonRedisSerializer  (serialize any POJO as JSON)
     *
     * The ObjectMapper is configured with JavaTimeModule to handle
     * LocalDateTime in TransactionEvent / FraudResult correctly.
     *
     * The RedisConnectionFactory is injected by Spring Boot auto-configuration,
     * which creates a pooled LettuceConnectionFactory from application-docker.yml.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        // Configure ObjectMapper with Java 8 date/time support
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serializer: plain string (human-readable in Redis CLI)
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value serializer: JSON (readable and type-safe)
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
