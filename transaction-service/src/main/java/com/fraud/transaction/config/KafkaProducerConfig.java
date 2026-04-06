package com.fraud.transaction.config;

import com.fraud.common.dto.TransactionEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaProducerConfig — Kafka Producer Configuration
 *
 * Configures:
 *  1. ProducerFactory — builds Kafka producer instances with JSON serialization
 *  2. KafkaTemplate   — high-level API used by KafkaProducerService to send messages
 *  3. NewTopic bean   — auto-creates the "transactions" topic on startup if absent
 *
 * Serialization strategy:
 *  - Key:   StringSerializer   (transactionId string)
 *  - Value: JsonSerializer     (TransactionEvent POJO → JSON)
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Kafka topic name — resolved from application.yml at startup.
     * Using @Value instead of a static constant allows per-environment overrides
     * (e.g., different topic names in dev/staging/prod without code changes).
     */
    @Value("${kafka.topic.transactions:transactions}")
    private String transactionTopic;

    /**
     * Producer configuration properties map — private helper, NOT a Spring bean.
     *
     * Previously annotated @Bean, which exposed a raw Map<String, Object> into the
     * Spring context. Any component could accidentally receive it via @Autowired.
     * Removed @Bean: this method is only called internally by producerFactory(),
     * which is itself a @Bean and is only instantiated once by Spring.
     *
     * Acks=all ensures messages are committed by all in-sync replicas
     * before the producer considers the send successful (safe for finance).
     */
    private Map<String, Object> producerConfigs() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Reliability settings
        props.put(ProducerConfig.ACKS_CONFIG, "all");                      // Wait for all ISR acknowledgements
        props.put(ProducerConfig.RETRIES_CONFIG, 3);                        // Retry up to 3 times on transient failure
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);          // Exactly-once delivery semantics
        // REQUEST_TIMEOUT_MS: how long the producer waits for a broker ACK per individual send attempt.
        // Must be < DELIVERY_TIMEOUT_MS so retries can happen within the total delivery budget.
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);         // 30s per send attempt
        // DELIVERY_TIMEOUT_MS is the total time the producer will spend retrying.
        // Must be >= request.timeout.ms + linger.ms. Default (120s) is used here explicitly.
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);       // 2 minutes total delivery budget

        // Performance tuning
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);           // Batch up to 5ms for throughput
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);       // 16KB batch size

        return props;
    }

    /**
     * ProducerFactory creates the underlying Kafka producer.
     * JsonSerializer handles TransactionEvent → JSON conversion.
     *
     * setAddTypeInfo(false) must be called explicitly on the JsonSerializer instance
     * to prevent the producer from injecting a "__TypeId__" header into every message.
     * The consumer is configured with USE_TYPE_INFO_HEADERS=false so it ignores that
     * header anyway — but omitting it keeps the message payload clean and avoids
     * accidental coupling between producer and consumer class paths.
     */
    @Bean
    public ProducerFactory<String, TransactionEvent> producerFactory() {
        JsonSerializer<TransactionEvent> valueSerializer = new JsonSerializer<>();
        valueSerializer.setAddTypeInfo(false);   // Suppress __TypeId__ header injection

        DefaultKafkaProducerFactory<String, TransactionEvent> factory =
                new DefaultKafkaProducerFactory<>(producerConfigs());
        factory.setValueSerializer(valueSerializer);
        return factory;
    }

    /**
     * KafkaTemplate — the main interface used in KafkaProducerService.
     * Wraps ProducerFactory and provides send/sendDefault convenience methods.
     */
    @Bean
    public KafkaTemplate<String, TransactionEvent> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * Auto-create "transactions" topic with:
     *  - 3 partitions (allows parallel consumption by 3 consumer instances)
     *  - 1 replica   (increase to 3 in production cluster)
     *
     * Topic name is read from kafka.topic.transactions in application.yml,
     * defaulting to "transactions" if the property is absent.
     */
    @Bean
    public NewTopic transactionTopic() {
        return TopicBuilder.name(transactionTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /** Exposes the resolved topic name so KafkaProducerService can inject it via @Value. */
    public String getTransactionTopic() {
        return transactionTopic;
    }
}
