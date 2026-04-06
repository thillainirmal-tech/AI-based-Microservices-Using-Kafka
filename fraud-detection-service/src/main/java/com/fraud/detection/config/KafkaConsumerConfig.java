package com.fraud.detection.config;

import com.fraud.common.dto.TransactionEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaConsumerConfig — Kafka consumer configuration (Polish v3)
 *
 * Key improvements:
 *   - KAFKA_BOOTSTRAP_SERVERS injected via @Value (was hardcoded to localhost)
 *   - DeadLetterPublishingRecoverer routes poison-pill messages to a DLQ topic
 *     after 3 retries (FixedBackOff — 1s delay, 3 attempts)
 *   - Manual ACK mode (MANUAL_IMMEDIATE) — offset committed only after full
 *     pipeline processing, not before
 *   - ErrorHandlingDeserializer wraps JsonDeserializer — deserialization errors
 *     produce a null event (caught by TransactionConsumer null guard) instead of
 *     crashing the container
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:fraud-detection-group-v2}")
    private String groupId;

    // Custom ConsumerFactory bypasses spring.kafka.consumer.auto-offset-reset from YAML.
    // Must be injected explicitly — YAML auto-offset-reset: earliest is ignored by the custom factory.
    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${kafka.topic.dlq:transactions.DLT}")
    private String dlqTopic;

    // ─── ConsumerFactory ─────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, TransactionEvent> consumerFactory() {

        Map<String, Object> props = new HashMap<>();

        // Bootstrap servers — REQUIRED via env var KAFKA_BOOTSTRAP_SERVERS
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);

        // Manual commit — offset committed in TransactionConsumer.acknowledge()
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Performance tuning
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        // 10 min — accommodates Spring AI latency on fraud analysis
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        // ErrorHandlingDeserializer — wraps JsonDeserializer so that
        // malformed/undeserializable messages become null events instead of
        // crashing the consumer. TransactionConsumer's null guard handles these.
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);

        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.fraud.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionEvent.class.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    // ─── KafkaListenerContainerFactory ────────────────────────────────────────

    /**
     * Container factory with:
     *   - Manual ACK (MANUAL_IMMEDIATE) — TransactionConsumer calls acknowledge()
     *   - DLQ recoverer — routes failed messages to ${kafka.topic.dlq} after retries
     *   - FixedBackOff — 3 retry attempts with 1-second delay between each
     *   - Concurrency 3 — matches 3 Kafka partitions for parallel consumption
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionEvent>
    kafkaListenerContainerFactory(@Qualifier("notificationKafkaTemplate")
                                  KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, TransactionEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());

        // Concurrency = number of topic partitions
        factory.setConcurrency(3);

        // Manual ACK — TransactionConsumer controls when offsets are committed
        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Dead Letter Queue: route failed messages to DLQ after 3 retries.
        // Pattern: {original-topic}.DLT (configurable via kafka.topic.dlq).
        // This prevents bad messages from blocking the partition indefinitely.
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, ex) -> new org.apache.kafka.common.TopicPartition(dlqTopic, 0));

        // FixedBackOff: 1000ms interval, 3 max attempts before routing to DLQ
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(1000L, 3L));

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
