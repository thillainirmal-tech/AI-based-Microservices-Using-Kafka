package com.fraud.detection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;

import com.fraud.detection.config.FraudRulesProperties;

/**
 * FraudDetectionApplication — Spring Boot Entry Point
 *
 * Pipeline executed per transaction:
 *   Kafka Consumer
 *     → FraudDetectionService (Rule Engine → Redis History → Spring AI)
 *       → RedisService.saveFraudResult()
 *         → GET /api/fraud/result/{id} available to client
 *
 * Port: 8082
 * Kafka topic consumed: "transactions"
 * Redis namespaces:
 *   "user:{userId}:history"         — rolling 24h user behaviour
 *   "fraud:result:{transactionId}"  — fraud verdict (72h TTL)
 *
 * CHANGE LOG v1.1:
 *   — Added @EnableConfigurationProperties(FraudRulesProperties.class)
 *     to activate the @ConfigurationProperties bean that replaces
 *     all hardcoded thresholds with externalised YAML config.
 */
@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties(FraudRulesProperties.class)
public class FraudDetectionApplication {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionApplication.class, args);
        log.info("═══════════════════════════════════════════════════════");
        log.info("  Fraud Detection Service started on port 8082         ");
        log.info("  Kafka Consumer ← topic: transactions                 ");
        log.info("  Redis namespaces: user:*:history | fraud:result:*    ");
        log.info("  Spring AI: OpenAI GPT (Layer 3)                      ");
        log.info("  GET /api/fraud/result/{id} → retrieve verdict        ");
        log.info("═══════════════════════════════════════════════════════");
    }
}
