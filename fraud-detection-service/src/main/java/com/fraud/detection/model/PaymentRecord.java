package com.fraud.detection.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PaymentRecord — payment lifecycle state persisted in Redis.
 *
 * Stored at key: "payment:record:{transactionId}"
 * TTL: matches fraud.rules.result-ttl-hours (default 72h)
 *
 * Serves three purposes:
 *   1. Idempotency gate — if record exists (non-PENDING), skip re-processing
 *   2. Compensation tracking — records refund outcome after credit failure
 *   3. Status API — returned by GET /api/fraud/payment/{id}
 *      and GET /api/transactions/{id} (combined with FraudResult)
 *
 * PaymentStatus state machine:
 *   PENDING → SUCCESS
 *           → FAILED
 *           → COMPENSATING → COMPENSATED
 *                          → COMPENSATION_FAILED  (CRITICAL — ops alert required)
 *           → RAZORPAY_ORDER_CREATED
 *           → SKIPPED  (non-UPI / legacy transaction)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Transaction ID — matches TransactionEvent.transactionId */
    private String transactionId;

    /** Current payment lifecycle state */
    private PaymentStatus paymentStatus;

    /** Payer email — from JWT (X-User-Email), never from request body */
    private String payerEmail;

    /**
     * Payee email — resolved from payeeUpiId via bank-service lookup.
     * Null if UPI ID could not be resolved (payment aborted before debit).
     */
    private String payeeEmail;

    /** Payee's UPI ID as provided in the original payment request */
    private String payeeUpiId;

    /** Payment amount in primary currency unit (INR) */
    private BigDecimal amount;

    /** "BANK" or "RAZORPAY" */
    private String paymentMode;

    /**
     * Razorpay order ID — only set when paymentMode=RAZORPAY
     * and order was successfully created. Null for BANK mode.
     */
    private String razorpayOrderId;

    /**
     * Human-readable reason for FAILED or COMPENSATION_FAILED status.
     * Null for SUCCESS, COMPENSATED, SKIPPED.
     */
    private String failureReason;

    /**
     * Reason recorded during compensation attempt.
     * Set regardless of whether compensation succeeded or failed.
     */
    private String compensationReason;

    /** When payment processing began (PENDING state set) */
    private LocalDateTime initiatedAt;

    /** When payment reached a terminal state (SUCCESS/FAILED/COMPENSATED/etc.) */
    private LocalDateTime completedAt;

    /** Total elapsed time for the payment operation in milliseconds */
    private Long elapsedMs;
}
