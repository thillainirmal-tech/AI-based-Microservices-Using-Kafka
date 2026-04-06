package com.fraud.detection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fraud.common.dto.FraudResult;
import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.config.FraudRulesProperties;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.PaymentStatus;
import com.fraud.detection.model.UserTransactionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RedisService — Unified Redis Cache Operations
 *
 * Owns ALL Redis interactions for the fraud detection system.
 * Three distinct key namespaces:
 *
 *  ┌──────────────────────────────────────────────────────────────┐
 *  │  Namespace 1: User Transaction History                       │
 *  │  Key schema : "user:{userId}:history"                        │
 *  │  Value type : UserTransactionHistory (JSON)                  │
 *  │  TTL        : 24h (fixed sliding window)                     │
 *  │  Purpose    : Feed rule engine and history analyser          │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  Namespace 2: Fraud Analysis Results                         │
 *  │  Key schema : "fraud:result:{transactionId}"                 │
 *  │  Value type : FraudResult (JSON)                             │
 *  │  TTL        : fraud.rules.result-ttl-hours (default 72h)     │
 *  │  Purpose    : Serve GET /api/fraud/result/{id}               │
 *  ├──────────────────────────────────────────────────────────────┤
 *  │  Namespace 3: Payment Records (HARDENING v2)                 │
 *  │  Key schema : "payment:record:{transactionId}"               │
 *  │  Value type : PaymentRecord (JSON)                           │
 *  │  TTL        : fraud.rules.result-ttl-hours (default 72h)     │
 *  │  Purpose    : Idempotency gate, compensation tracking,       │
 *  │               serve GET /api/fraud/payment/{id}              │
 *  └──────────────────────────────────────────────────────────────┘
 *
 * All read failures are fail-open (return null/false — never block pipeline).
 * All write failures are non-fatal (logged, never re-thrown to caller).
 */
@Service
public class RedisService {

    private static final Logger log = LoggerFactory.getLogger(RedisService.class);

    // ─── Key Schema Constants ──────────────────────────────────────────────
    private static final String HISTORY_KEY_PREFIX  = "user:";
    private static final String HISTORY_KEY_SUFFIX  = ":history";
    private static final String RESULT_KEY_PREFIX   = "fraud:result:";
    private static final String PAYMENT_KEY_PREFIX  = "payment:record:";
    private static final String TIMELINE_KEY_PREFIX = "tx:timeline:";

    private static final long HISTORY_TTL_HOURS = 24;

    // ─── Dependencies ──────────────────────────────────────────────────────

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private FraudRulesProperties fraudRules;

    private final ObjectMapper objectMapper;

    public RedisService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 1 — User Transaction History
    // ══════════════════════════════════════════════════════════════════════

    private String buildHistoryKey(String userId) {
        return HISTORY_KEY_PREFIX + userId + HISTORY_KEY_SUFFIX;
    }

    /**
     * Retrieve cached transaction history. Returns null on miss or Redis error (fail-open).
     */
    public UserTransactionHistory getHistory(String userId) {
        String key = buildHistoryKey(userId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.debug("[REDIS][HISTORY] Cache miss — user: {}", userId);
                return null;
            }
            UserTransactionHistory history =
                    objectMapper.convertValue(cached, UserTransactionHistory.class);
            log.debug("[REDIS][HISTORY] Cache hit — user: {} | {} transactions",
                    userId,
                    history.getRecentTransactions() != null
                            ? history.getRecentTransactions().size() : 0);
            return history;
        } catch (Exception e) {
            log.error("[REDIS][HISTORY] Read failed for user: {} — {}", userId, e.getMessage(), e);
            return null; // fail-open
        }
    }

    /**
     * Persist user transaction history with sliding 24h TTL. Non-fatal on write failure.
     */
    public void saveHistory(UserTransactionHistory history) {
        String key = buildHistoryKey(history.getUserId());
        try {
            redisTemplate.opsForValue().set(key, history, HISTORY_TTL_HOURS, TimeUnit.HOURS);
            log.debug("[REDIS][HISTORY] Saved — user: {} | {} transactions | TTL: {}h",
                    history.getUserId(),
                    history.getRecentTransactions() != null
                            ? history.getRecentTransactions().size() : 0,
                    HISTORY_TTL_HOURS);
        } catch (Exception e) {
            log.error("[REDIS][HISTORY] Write failed for user: {} — {}", history.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * Fetch → append → save in one call. Creates new history if absent.
     */
    public UserTransactionHistory recordTransaction(TransactionEvent event) {
        UserTransactionHistory history = getHistory(event.getUserId());
        if (history == null) {
            log.info("[REDIS][HISTORY] First transaction for user: {} — initialising history",
                    event.getUserId());
            history = UserTransactionHistory.builder()
                    .userId(event.getUserId())
                    .build();
        }
        history.addTransaction(event);
        saveHistory(history);
        log.info("[REDIS][HISTORY] Recorded txId={} for user={} | Total in window: {}",
                event.getTransactionId(), event.getUserId(),
                history.getRecentTransactions().size());
        return history;
    }

    public int getTransactionCount(String userId) {
        UserTransactionHistory history = getHistory(userId);
        if (history == null || history.getRecentTransactions() == null) return 0;
        return history.getRecentTransactions().size();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 2 — Fraud Analysis Results
    // ══════════════════════════════════════════════════════════════════════

    private String buildResultKey(String transactionId) {
        return RESULT_KEY_PREFIX + transactionId;
    }

    /**
     * Persist FraudResult. TTL from fraud.rules.result-ttl-hours. Non-fatal on failure.
     */
    public void saveFraudResult(FraudResult result) {
        String key = buildResultKey(result.getTransactionId());
        long ttlHours = fraudRules.getResultTtlHours();
        try {
            redisTemplate.opsForValue().set(key, result, ttlHours, TimeUnit.HOURS);
            log.info("[REDIS][RESULT] Saved — txId={} status={} TTL={}h",
                    result.getTransactionId(), result.getStatus(), ttlHours);
        } catch (Exception e) {
            log.error("[REDIS][RESULT] Write failed for txId={} — {}", result.getTransactionId(), e.getMessage(), e);
        }
    }

    /**
     * Retrieve FraudResult. Returns null on miss or Redis error (fail-open).
     */
    public FraudResult getFraudResult(String transactionId) {
        String key = buildResultKey(transactionId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.info("[REDIS][RESULT] Not found for txId={}", transactionId);
                return null;
            }
            FraudResult result = objectMapper.convertValue(cached, FraudResult.class);
            log.info("[REDIS][RESULT] Retrieved — txId={} status={} layer={}",
                    transactionId, result.getStatus(), result.getDetectionLayer());
            return result;
        } catch (Exception e) {
            log.error("[REDIS][RESULT] Read failed for txId={} — {}", transactionId, e.getMessage(), e);
            return null;
        }
    }

    /** Delete FraudResult (used for REVIEW re-processing). Returns true if key existed. */
    public boolean deleteFraudResult(String transactionId) {
        String key = buildResultKey(transactionId);
        try {
            Boolean deleted = redisTemplate.delete(key);
            boolean wasDeleted = Boolean.TRUE.equals(deleted);
            if (wasDeleted) log.info("[REDIS][RESULT] Deleted txId={}", transactionId);
            else           log.warn("[REDIS][RESULT] Delete: key not found for txId={}", transactionId);
            return wasDeleted;
        } catch (Exception e) {
            log.error("[REDIS][RESULT] Delete failed for txId={} — {}", transactionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Idempotency check — does a FraudResult already exist for this transaction?
     * Fail-open (returns false on Redis error so the pipeline re-runs rather than blocks).
     */
    public boolean fraudResultExists(String transactionId) {
        String key = buildResultKey(transactionId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("[REDIS][RESULT] hasKey failed for txId={} — assuming absent", transactionId);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 3 — Payment Records (Hardening v2)
    // ══════════════════════════════════════════════════════════════════════

    private String buildPaymentKey(String transactionId) {
        return PAYMENT_KEY_PREFIX + transactionId;
    }

    /**
     * Persist a PaymentRecord. Used after every state transition in the payment lifecycle.
     * TTL matches fraud result TTL (default 72h). Non-fatal on write failure.
     *
     * @param record the current payment state to store
     */
    public void savePaymentRecord(PaymentRecord record) {
        String key = buildPaymentKey(record.getTransactionId());
        long ttlHours = fraudRules.getResultTtlHours();
        try {
            redisTemplate.opsForValue().set(key, record, ttlHours, TimeUnit.HOURS);
            log.info("[REDIS][PAYMENT] Saved — txId={} status={} TTL={}h",
                    record.getTransactionId(), record.getPaymentStatus(), ttlHours);
        } catch (Exception e) {
            log.error("[REDIS][PAYMENT] Write failed for txId={} — {}",
                    record.getTransactionId(), e.getMessage(), e);
        }
    }

    /**
     * Retrieve a PaymentRecord. Returns null on miss or Redis error (fail-open).
     * Used by:
     *   - PaymentProcessorService (idempotency gate)
     *   - FraudController GET /api/fraud/payment/{id}
     *   - transaction-service GET /api/transactions/{id}
     */
    public PaymentRecord getPaymentRecord(String transactionId) {
        String key = buildPaymentKey(transactionId);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached == null) {
                log.debug("[REDIS][PAYMENT] Not found — txId={}", transactionId);
                return null;
            }
            PaymentRecord record = objectMapper.convertValue(cached, PaymentRecord.class);
            log.info("[REDIS][PAYMENT] Retrieved — txId={} status={}",
                    transactionId, record.getPaymentStatus());
            return record;
        } catch (Exception e) {
            log.error("[REDIS][PAYMENT] Read failed for txId={} — {}", transactionId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Check whether ANY payment record exists for this transaction.
     * Used as the first idempotency gate in PaymentProcessorService.
     * Fail-open: returns false on Redis error so payment proceeds (idempotency via status check).
     */
    public boolean paymentRecordExists(String transactionId) {
        String key = buildPaymentKey(transactionId);
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("[REDIS][PAYMENT] hasKey failed for txId={} — assuming absent", transactionId);
            return false;
        }
    }

    /**
     * Convenience: check whether payment is in a terminal state (no further processing needed).
     * Terminal states: SUCCESS, FAILED, COMPENSATED, COMPENSATION_FAILED, RAZORPAY_ORDER_CREATED, SKIPPED.
     * Non-terminal: PENDING, COMPENSATING.
     *
     * @return true if terminal and should be skipped; false if absent or non-terminal
     */
    public boolean isPaymentTerminal(String transactionId) {
        PaymentRecord record = getPaymentRecord(transactionId);
        if (record == null) return false;
        return switch (record.getPaymentStatus()) {
            case SUCCESS, FAILED, COMPENSATED, COMPENSATION_FAILED,
                 RAZORPAY_ORDER_CREATED, SKIPPED -> true;
            case PENDING, COMPENSATING            -> false;
        };
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAMESPACE 4 — Transaction Timeline
    //
    //  Key schema : "tx:timeline:{transactionId}"
    //  Value type : List<String> — ordered step entries, e.g.
    //               "INITIATED=2024-01-15T10:30:00.123"
    //  TTL        : matches fraud result TTL (default 72h)
    //
    //  Steps (in order):
    //    INITIATED       — event received by Kafka consumer
    //    FRAUD_VERDICT   — fraud pipeline completed and result saved
    //    PAYMENT_STARTED — PaymentProcessorService began processing
    //    DEBIT_DONE      — payer's account debited successfully
    //    CREDIT_DONE     — payee's account credited successfully
    //    COMPLETED       — terminal payment state reached
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Append a step to the transaction timeline.
     *
     * Stores: "STEP_NAME=ISO_TIMESTAMP" as a new list element.
     * The list preserves insertion order — first in, first out (chronological).
     * Non-fatal: Redis unavailability is logged and silently ignored.
     *
     * @param txId  the transaction ID
     * @param step  the pipeline stage name (e.g. "INITIATED", "DEBIT_DONE")
     */
    public void appendTimeline(String txId, String step) {
        String key = TIMELINE_KEY_PREFIX + txId;
        try {
            String entry = step + "=" + LocalDateTime.now();
            redisTemplate.opsForList().rightPush(key, entry);
            // Refresh TTL on every append — the key stays alive for the full window
            redisTemplate.expire(key, fraudRules.getResultTtlHours(), TimeUnit.HOURS);
            log.debug("[REDIS][TIMELINE] txId={} step={}", txId, step);
        } catch (Exception e) {
            // Non-fatal — timeline is observability only; never block the payment pipeline
            log.warn("[REDIS][TIMELINE] Append failed txId={} step={} — {}",
                    txId, step, e.getMessage());
        }
    }

    /**
     * Retrieve the full ordered timeline for a transaction.
     * Returns an empty list on miss or Redis error (fail-open).
     *
     * @param txId the transaction ID
     * @return ordered list of "STEP=TIMESTAMP" strings, empty if not found
     */
    @SuppressWarnings("unchecked")
    public List<String> getTimeline(String txId) {
        String key = TIMELINE_KEY_PREFIX + txId;
        try {
            List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            return raw.stream().map(Object::toString).toList();
        } catch (Exception e) {
            log.warn("[REDIS][TIMELINE] Read failed txId={} — {}", txId, e.getMessage());
            return Collections.emptyList();
        }
    }
    // ─────────────────────────────────────────────
// Razorpay Order ↔ Transaction Mapping
// ─────────────────────────────────────────────

    public void saveOrderMapping(String orderId, String txId) {
        redisTemplate.opsForValue().set(
                "razorpay:order:" + orderId,
                txId,
                72,
                TimeUnit.HOURS
        );
    }

    public String getTxIdByOrderId(String orderId) {
        Object value = redisTemplate.opsForValue().get("razorpay:order:" + orderId);

        if (value == null) return null;

        return value.toString(); // ✅ SAFE
    }
    // ─────────────────────────────────────────────
// Razorpay: Mark Payment SUCCESS (Webhook)
// ─────────────────────────────────────────────
    public void markPaymentSuccess(String txId) {

        PaymentRecord record = getPaymentRecord(txId);

        if (record == null) {
            log.error("[RAZORPAY] PaymentRecord not found for txId={}", txId);
            return;
        }

        // 🔒 Idempotency (very important)
        if (record.getPaymentStatus() == PaymentStatus.SUCCESS) {
            log.warn("[RAZORPAY] Already SUCCESS txId={}", txId);
            return;
        }

        record.setPaymentStatus(PaymentStatus.SUCCESS);
        record.setPaymentMode("RAZORPAY");
        record.setCompletedAt(LocalDateTime.now());
        log.info("[TX-TRACE] transactionId={} stage=RAZORPAY_SUCCESS", txId);
        savePaymentRecord(record);
        log.info("[RAZORPAY] Payment marked SUCCESS txId={}", txId);
    }

    public void markPaymentFailed(String txId) {

        PaymentRecord record = getPaymentRecord(txId);

        if (record == null) {
            log.error("[RAZORPAY] PaymentRecord not found for txId={}", txId);
            return;
        }

        // Idempotency
        if (record.getPaymentStatus() == PaymentStatus.FAILED) {
            log.warn("[RAZORPAY] Already FAILED txId={}", txId);
            return;
        }

        record.setPaymentStatus(PaymentStatus.FAILED);
        record.setCompletedAt(LocalDateTime.now());
        record.setFailureReason("Razorpay payment failed");

        savePaymentRecord(record);

        log.warn("[RAZORPAY] Payment marked FAILED txId={}", txId);
    }
    public void savePaymentId(String txId, String paymentId) {
        redisTemplate.opsForValue().set("razorpay:payment:" + txId, paymentId);
    }
    public String getPaymentId(String txId) {
        Object val = redisTemplate.opsForValue().get("razorpay:payment:" + txId);
        return val != null ? val.toString() : null;
    }
    public void markCompensating(String txId, String reason) {

        PaymentRecord record = getPaymentRecord(txId);

        record.setPaymentStatus(PaymentStatus.COMPENSATING);
        record.setCompensationReason(reason);

        savePaymentRecord(record);
    }
    public void markCompensated(String txId) {

        PaymentRecord record = getPaymentRecord(txId);

        record.setPaymentStatus(PaymentStatus.COMPENSATED);

        savePaymentRecord(record);
    }
    public void markCompensationFailed(String txId, String reason) {

        PaymentRecord record = getPaymentRecord(txId);

        record.setPaymentStatus(PaymentStatus.COMPENSATION_FAILED);
        record.setCompensationReason(reason);

        savePaymentRecord(record);
    }
}
