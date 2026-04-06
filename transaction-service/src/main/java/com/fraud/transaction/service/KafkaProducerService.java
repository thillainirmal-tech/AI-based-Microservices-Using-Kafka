package com.fraud.transaction.service;

import com.fraud.common.dto.TransactionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * KafkaProducerService — async Kafka event publisher (Polish v3)
 *
 * Publishes TransactionEvent to the "transactions" topic for fraud analysis.
 *
 * Key properties:
 *   - Non-blocking: send() returns immediately; result logged via CompletableFuture callback
 *   - Idempotent producer: enable.idempotence=true in application.yml
 *   - acks=all: waits for all ISR replicas before callback fires
 *
 * All System.out.println debug statements have been removed.
 * ex.printStackTrace() has been replaced with structured SLF4J logging.
 */
@Slf4j
@Service
public class KafkaProducerService {

    @Autowired
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @Value("${kafka.topic.transactions:transactions}")
    private String transactionTopic;

    /**
     * Publish a TransactionEvent to Kafka asynchronously.
     * Returns immediately — does not block the caller.
     * Success/failure is logged via the CompletableFuture callback.
     *
     * @param event the transaction event to publish
     */
    public void publishTransaction(TransactionEvent event) {
        log.info("[KAFKA-PRODUCER] Publishing → txId={} userId={} amount={}",
                event.getTransactionId(), event.getUserId(), event.getAmount());

        try {
            CompletableFuture<SendResult<String, TransactionEvent>> future =
                    kafkaTemplate.send(transactionTopic, event.getTransactionId(), event);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[KAFKA-PRODUCER] ✓ Sent txId={} → partition={} offset={}",
                            event.getTransactionId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[KAFKA-PRODUCER] ✗ Failed to send txId={} — error={}",
                            event.getTransactionId(), ex.getMessage(), ex);
                }
            });

        } catch (Exception e) {
            log.error("[KAFKA-PRODUCER] ✗ Unexpected error publishing txId={}",
                    event.getTransactionId(), e);
        }
    }

    /**
     * Alias for {@link #publishTransaction(TransactionEvent)}.
     * Provided for backward compatibility with callers that use sendTransaction().
     *
     * @param event the transaction event to publish
     */
    public void sendTransaction(TransactionEvent event) {
        publishTransaction(event);
    }
}
