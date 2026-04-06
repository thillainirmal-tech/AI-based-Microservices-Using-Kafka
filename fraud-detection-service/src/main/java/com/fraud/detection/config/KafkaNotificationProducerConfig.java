package com.fraud.detection.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaNotificationProducerConfig — Explicit producer configuration for the
 * notifications Kafka topic (and the DLQ recoverer in KafkaConsumerConfig).
 *
 * WHY THIS CLASS EXISTS:
 *   Spring Boot's KafkaAutoConfiguration creates a KafkaTemplate backed by
 *   StringSerializer when no explicit Java-based ProducerFactory/KafkaTemplate
 *   bean is present. In local profile the YAML producer block is absent, so the
 *   auto-configured template uses StringSerializer and fails at runtime when
 *   NotificationProducer.send(NotificationEvent) is called:
 *
 *     "Can't convert value of class com.fraud.common.dto.NotificationEvent
 *      to class org.apache.kafka.common.serialization.StringSerializer"
 *
 *   This class defines an explicit ProducerFactory and KafkaTemplate using
 *   JsonSerializer — making the fix profile-independent (local AND docker).
 *
 * BEAN NAMING & CONFLICT AVOIDANCE:
 *   - Both beans are named with qualifier "notificationKafkaTemplate" /
 *     "notificationProducerFactory" to avoid colliding with any other
 *     ProducerFactory or KafkaTemplate that might exist (e.g., a future
 *     transaction producer).
 *   - Spring Boot's @ConditionalOnMissingBean(KafkaTemplate.class) backs off
 *     once ANY KafkaTemplate bean is registered, so the auto-configured
 *     StringSerializer template is suppressed.
 *   - KafkaConsumerConfig.kafkaListenerContainerFactory and
 *     NotificationProducer both use @Qualifier("notificationKafkaTemplate")
 *     so injection is unambiguous.
 *
 * SERIALIZATION STRATEGY:
 *   - Key:   StringSerializer  (simple string keys for Kafka partitioning)
 *   - Value: JsonSerializer    (NotificationEvent / Object POJO → JSON)
 *   - setAddTypeInfo(false)    suppresses the __TypeId__ header; the
 *     notification-service consumer is configured with USE_TYPE_INFO_HEADERS=false
 *     and VALUE_DEFAULT_TYPE=NotificationEvent, so the header is not needed.
 */
@Configuration
public class KafkaNotificationProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ─── Producer properties ─────────────────────────────────────────────────

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Reliability — same policy as transaction-service producer
        props.put(ProducerConfig.ACKS_CONFIG, "all");             // Wait for all ISR ACKs
        props.put(ProducerConfig.RETRIES_CONFIG, 3);               // Retry transient failures
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // Exactly-once delivery

        // REQUEST_TIMEOUT_MS must be < DELIVERY_TIMEOUT_MS so retries fit within the budget
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);   // 30s per send attempt
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000); // 2min total budget

        // Performance — small linger for low-latency notification sends
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16_384); // 16 KB

        return props;
    }

    // ─── ProducerFactory ─────────────────────────────────────────────────────

    /**
     * ProducerFactory for NotificationEvent messages.
     *
     * JsonSerializer<Object> is used (not JsonSerializer<NotificationEvent>)
     * so the same KafkaTemplate can also serve the DLQ recoverer in
     * KafkaConsumerConfig, which types its template as KafkaTemplate<String, Object>.
     *
     * setAddTypeInfo(false) prevents the producer from injecting a __TypeId__
     * header into every Kafka record. The notification-service consumer ignores
     * type headers (setUseTypeHeaders(false)) and maps directly to NotificationEvent,
     * so the header is unnecessary and its absence keeps the wire format clean.
     */
    @Bean("notificationProducerFactory")
    public ProducerFactory<String, Object> notificationProducerFactory() {
        JsonSerializer<Object> valueSerializer = new JsonSerializer<>();
        valueSerializer.setAddTypeInfo(false); // Suppress __TypeId__ header

        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(producerProps());
        factory.setValueSerializer(valueSerializer);
        return factory;
    }

    // ─── KafkaTemplate ───────────────────────────────────────────────────────

    /**
     * KafkaTemplate used by:
     *   1. NotificationProducer — sends NotificationEvent to the notifications topic
     *   2. DeadLetterPublishingRecoverer (KafkaConsumerConfig) — sends poison-pill
     *      TransactionEvent records to the DLQ topic after retries are exhausted
     *
     * Qualifier "notificationKafkaTemplate" is referenced in both injection sites
     * to keep bean resolution explicit and unambiguous.
     */
    @Bean("notificationKafkaTemplate")
    public KafkaTemplate<String, Object> notificationKafkaTemplate() {
        return new KafkaTemplate<>(notificationProducerFactory());
    }
}
