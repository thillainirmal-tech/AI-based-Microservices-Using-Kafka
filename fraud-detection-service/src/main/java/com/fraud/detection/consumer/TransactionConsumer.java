package com.fraud.detection.consumer;

import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.FraudResult.FraudStatus;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.payment.PaymentProcessorService;
import com.fraud.detection.service.FraudDetectionService;
import com.fraud.detection.service.RedisService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * TransactionConsumer — Kafka Message Consumer (Hardening v2)
 *
 * Full processing pipeline for every inbound TransactionEvent:
 *
 *  ┌───────────────────────────────────────────────────────────────┐
 *  │  1. Null guard — skip poison-pill messages immediately        │
 *  │  2. Fraud idempotency — skip if already analysed (Redis)      │
 *  │  3. FraudDetectionService.analyzeTransaction()                │
 *  │  4. saveFraudResult() — persist verdict BEFORE payment        │
 *  │  5. logFraudDecision() — human-readable + FRAUD_AUDIT JSON    │
 *  │  6. PaymentProcessorService.processPayment() (SAFE only)      │
 *  │     └── Internal idempotency gate (PaymentRecord in Redis)    │
 *  │     └── Compensation on credit failure (refund payer)         │
 *  │  7. ACK offset — always, even on failure (prevent blocking)   │
 *  └───────────────────────────────────────────────────────────────┘
 *
 * Retry-safety:
 *   - Fraud idempotency: fraudResultExists() before re-analysis
 *   - Payment idempotency: paymentRecordExists() inside PaymentProcessorService
 *   - ACK on catch: prevents partition stall; DLT path commented for production upgrade
 *
 * INVARIANTS:
 *   saveFraudResult()  MUST complete before processPayment() is called.
 *   Payment failure MUST NOT prevent ACK (fraud verdict already persisted).
 *   Identity (userId) MUST come from event.payerUserId (gateway-injected JWT claim).
 */
@Component
public class TransactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    @Autowired private FraudDetectionService   fraudDetectionService;
    @Autowired private RedisService            redisService;
    @Autowired private PaymentProcessorService paymentProcessorService;
    @Autowired private MeterRegistry           meterRegistry;

    // Used to manually route business-failure events to the DLQ.
    // Same bean as the DLQ recoverer in KafkaConsumerConfig — JsonSerializer backed,
    // no type headers. @Qualifier is mandatory: this is the only KafkaTemplate bean.
    @Autowired
    @Qualifier("notificationKafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    // DLQ topic — matches kafka.topic.dlq in application.yml (default: transactions.DLT).
    // Injected here so the value is consistent with KafkaConsumerConfig's DLQ recoverer.
    @Value("${kafka.topic.dlq:transactions.DLT}")
    private String dlqTopic;

    // ══════════════════════════════════════════════════════════════════════
    //  Kafka Listener
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Listens on the "transactions" Kafka topic.
     *
     * AckMode: MANUAL_IMMEDIATE — offset committed only when acknowledgment.acknowledge() is called.
     * Concurrency: controlled by KafkaConsumerConfig (3 threads default).
     */
    @KafkaListener(
            topics                = "${kafka.topic.transactions:transactions}",
            groupId               = "${spring.kafka.consumer.group-id:fraud-detection-group-v2}",
            containerFactory      = "kafkaListenerContainerFactory"
    )
    public void consumeTransaction(
            ConsumerRecord<String, TransactionEvent> record,
            Acknowledgment acknowledgment) {

        log.info("[CONSUMER] ▶ Received — key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());

        TransactionEvent event = record.value();

        // ── Guard 1: null message (ErrorHandlingDeserializer poison pill) ────
        if (event == null) {
            log.warn("[CONSUMER] Null message at partition={} offset={} — skipping",
                    record.partition(), record.offset());
            acknowledgment.acknowledge();
            return;
        }

        // ── MDC: populate traceId and transactionId for all downstream logs ────
        // traceId propagated from API Gateway → TransactionEvent.traceId through Kafka
        String traceId = event.getTraceId();
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        } else {
            MDC.put("traceId", "NO_TRACE-" + event.getTransactionId());
        }
        MDC.put("transactionId", event.getTransactionId());
        MDC.put("payeeUpiId", event.getPayeeUpiId());

        // Timeline: INITIATED — event received from Kafka
        redisService.appendTimeline(event.getTransactionId(), "INITIATED");
        log.info("[TX-TRACE] traceId={} transactionId={} stage=INITIATED",
                MDC.get("traceId"), event.getTransactionId());

        log.info("[CONSUMER] Processing — txId={} userId={} amount={} location={} device={} traceId={}",
                event.getTransactionId(), event.getUserId(),
                event.getAmount(), event.getLocation(), event.getDevice(), traceId);

        // ── Guard 2: fraud analysis idempotency ──────────────────────────────
        // If already analysed (e.g. Kafka redelivery after crash), skip re-analysis.
        // Payment idempotency is handled separately inside PaymentProcessorService.
        if (redisService.fraudResultExists(event.getTransactionId())) {
            log.warn("[CONSUMER] Idempotency: txId={} already analysed — skipping re-analysis. "
                    + "Payment processor will handle its own idempotency if needed.",
                    event.getTransactionId());
            // Still attempt payment in case it was interrupted mid-flight
            tryPayment(event);
            acknowledgment.acknowledge();
            MDC.clear();
            return;
        }

        try {
            // ── Step 3: Fraud Detection Pipeline ────────────────────────────
            long startMs = System.currentTimeMillis();
            FraudResult result = fraudDetectionService.analyzeTransaction(event);
            long elapsedMs = System.currentTimeMillis() - startMs;

            // ── Step 4: Persist Fraud Result BEFORE payment ──────────────────
            // CRITICAL INVARIANT: result must be in Redis before any money moves.
            // Clients must always be able to read the verdict regardless of payment outcome.
            redisService.saveFraudResult(result);

            // ── Step 5: Structured logging ───────────────────────────────────
            logFraudDecision(result, elapsedMs);

            // ── Step 5b: Transaction lifecycle marker + timeline + metrics ────
            redisService.appendTimeline(result.getTransactionId(), "FRAUD_VERDICT");
            log.info("[TX-TRACE] traceId={} transactionId={} stage=FRAUD_VERDICT status={}",
                    MDC.get("traceId"), result.getTransactionId(), result.getStatus());
            log.info("[TX-LIFECYCLE] VERDICT txId={} userId={} status={} confidence={} "
                    + "layer={} elapsedMs={}",
                    result.getTransactionId(), result.getUserId(),
                    result.getStatus(), result.getConfidenceScore(),
                    result.getDetectionLayer(), elapsedMs);

            if (FraudStatus.SAFE == result.getStatus()) {
                meterRegistry.counter("fraud.safe").increment();
            } else {
                meterRegistry.counter("fraud.blocked").increment();
            }

            // ── Step 6: Payment Processing (SAFE only) ───────────────────────
            if (FraudStatus.SAFE == result.getStatus()) {
                tryPayment(event);
            } else {
                log.info("[TX-LIFECYCLE] BLOCKED txId={} status={} — no money movement",
                        event.getTransactionId(), result.getStatus());
            }

            // ── Step 7: ACK ─────────────────────────────────────────────────
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[CONSUMER] ✗ PIPELINE FAILURE — txId={} class={} error={} "
                    + "| Routing to DLQ then ACKing to prevent partition block.",
                    event.getTransactionId(), e.getClass().getSimpleName(), e.getMessage(), e);

            // ── Manual DLQ routing for business-level failures ───────────────
            // The container-level DeadLetterPublishingRecoverer (KafkaConsumerConfig)
            // only fires for deserialization failures and uncaught container exceptions.
            // Application exceptions caught here bypass that path. We publish manually
            // so the event is preserved in the DLQ and can be replayed / investigated.
            //
            // Fire-and-forget: DLQ send failure must NOT prevent the ACK —
            // a blocked partition is a worse outcome than a missed DLQ entry.
            try {
                kafkaTemplate.send(dlqTopic, event.getTransactionId(), event);
                log.info("[CONSUMER] ↪ Business failure routed to DLQ — txId={} dlqTopic={}",
                        event.getTransactionId(), dlqTopic);
            } catch (Exception dlqEx) {
                log.error("[CONSUMER] ✗ DLQ send also failed — txId={} dlqError={}. "
                        + "Event may be lost. Check broker connectivity.",
                        event.getTransactionId(), dlqEx.getMessage(), dlqEx);
            }

            acknowledgment.acknowledge();

        } finally {
            // MUST clear MDC — Kafka consumer threads are reused across messages.
            // Stale MDC values would bleed into unrelated message log lines.
            MDC.clear();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Private Helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Invoke PaymentProcessorService with isolated error handling.
     * Payment failure MUST NOT propagate to the Kafka processing loop —
     * the fraud verdict is already persisted and the offset must be ACKed.
     */
    private void tryPayment(TransactionEvent event) {
        try {
            paymentProcessorService.processPayment(event);
        } catch (Exception ex) {
            log.error("[CONSUMER] ✗ Payment processing exception — txId={} error={}",
                    event.getTransactionId(), ex.getMessage(), ex);
            // Do not rethrow — payment failure is non-fatal for the Kafka pipeline.
            // PaymentProcessorService saves FAILED/COMPENSATION_FAILED record in Redis.
        }
    }

    /**
     * Emits one structured human-readable log AND one machine-parseable FRAUD_AUDIT JSON line.
     *
     * Human-readable:
     *   SAFE   → INFO  ✓
     *   REVIEW → INFO  ⚡
     *   FRAUD  → WARN  ⚠
     *
     * FRAUD_AUDIT JSON (parseable by ELK/Splunk/CloudWatch):
     *   [FRAUD_AUDIT] {"transactionId":"...","userId":"...","status":"...","confidence":0.95,
     *                  "layer":"...","reason":"...","payerEmail":"...","payeeUpiId":"...","elapsedMs":42}
     */
    private void logFraudDecision(FraudResult result, long elapsedMs) {

        String txId       = result.getTransactionId();
        String userId     = result.getUserId();
        FraudStatus status = result.getStatus();
        String reason     = result.getReason();
        Double confidence = result.getConfidenceScore();
        String layer      = result.getDetectionLayer();

        switch (status) {
            case SAFE -> log.info("[CONSUMER] ✓ SAFE — txId={} user={} layer={} confidence={} elapsedMs={}",
                    txId, userId, layer, confidence, elapsedMs);

            case REVIEW -> log.info("[CONSUMER] ⚡ REVIEW — txId={} user={} layer={} confidence={} reason={} elapsedMs={}",
                    txId, userId, layer, confidence, reason, elapsedMs);

            case FRAUD -> log.warn("[CONSUMER] ⚠ FRAUD — txId={} user={} layer={} confidence={} reason={} elapsedMs={}",
                    txId, userId, layer, confidence, reason, elapsedMs);

            default -> log.error("[CONSUMER] Unknown status={} txId={}", status, txId);
        }


        String payeeUpiId = MDC.get("payeeUpiId") != null ? MDC.get("payeeUpiId") : "";

        log.info("[FRAUD_AUDIT] {{\"transactionId\":\"{}\",\"userId\":\"{}\"," +
                        "\"payerEmail\":\"{}\",\"payeeUpiId\":\"{}\"," +
                        "\"status\":\"{}\",\"confidence\":{},\"layer\":\"{}\"," +
                        "\"reason\":\"{}\",\"elapsedMs\":{}}}",
                txId,
                userId,
                userId,
                payeeUpiId != null ? payeeUpiId : "",
                status,
                confidence,
                layer,
                reason != null ? reason : "",
                elapsedMs);
    }
}
