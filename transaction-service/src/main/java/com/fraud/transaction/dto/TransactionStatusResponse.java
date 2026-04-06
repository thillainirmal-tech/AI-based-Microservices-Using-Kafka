package com.fraud.transaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * TransactionStatusResponse — combined fraud verdict + payment lifecycle status.
 *
 * Returned by GET /api/transactions/{transactionId}
 *
 * Combines:
 *   - FraudResult  from fraud-detection-service GET /api/fraud/result/{id}
 *   - PaymentRecord from fraud-detection-service GET /api/fraud/payment/{id}
 *
 * Status semantics:
 *   fraudStatus   : SAFE | REVIEW | FRAUD | PENDING (not yet analysed)
 *   paymentStatus : SUCCESS | FAILED | COMPENSATED | COMPENSATION_FAILED |
 *                   RAZORPAY_ORDER_CREATED | SKIPPED | PENDING (not yet processed)
 *
 * Clients should poll this endpoint after submitting a UPI payment.
 * Typical sequence:
 *   1. POST /api/upi/pay → 202 { transactionId }
 *   2. GET  /api/transactions/{id} → poll until fraudStatus != PENDING
 *   3. If fraudStatus=SAFE and paymentStatus=SUCCESS → payment complete
 *   4. If fraudStatus=FRAUD → payment blocked, no money moved
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionStatusResponse {

    private String transactionId;

    // ─── Fraud Verdict Fields ──────────────────────────────────────────────
    private String  fraudStatus;         // SAFE | FRAUD | REVIEW | PENDING
    private Double  fraudConfidence;     // 0.0–1.0; null when PENDING
    private String  fraudDetectionLayer; // RULE_ENGINE | HISTORY | AI; null when PENDING
    private String  fraudReason;         // null when SAFE

    // ─── Payment Lifecycle Fields ──────────────────────────────────────────
    private String     paymentStatus;       // SUCCESS | FAILED | COMPENSATED | ... | null
    private String     paymentMode;         // BANK | RAZORPAY | null
    private BigDecimal amount;
    private String     payerEmail;
    private String     payeeEmail;          // null if payment aborted before resolution
    private String     payeeUpiId;
    private String     razorpayOrderId;     // non-null when paymentStatus=RAZORPAY_ORDER_CREATED
    private String     paymentFailureReason;// non-null on FAILED/COMPENSATED/COMPENSATION_FAILED
    private String     compensationReason;  // non-null on COMPENSATED/COMPENSATION_FAILED

    // ─── Timing ───────────────────────────────────────────────────────────
    private LocalDateTime paymentInitiatedAt;
    private LocalDateTime paymentCompletedAt;
    private Long          paymentElapsedMs;

    // ─── Meta ──────────────────────────────────────────────────────────────
    private String  overallStatus;  // HIGH-LEVEL: PENDING | COMPLETE | BLOCKED | FAILED | COMPENSATED
    private String  message;
    private String  timestamp;

    /** retryAfterSeconds — hint for clients when status is PENDING */
    private Integer retryAfterSeconds;
}
