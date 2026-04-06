package com.fraud.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * FraudResult — Shared Fraud Analysis Verdict DTO
 *
 * Produced by the fraud-detection-service after running the full
 * 3-layer pipeline (Rule Engine → Redis History → Spring AI).
 *
 * Stored in Redis under key: "fraud:result:{transactionId}"
 * Retrievable via:          GET /api/fraud/result/{transactionId}
 *
 * CHANGE LOG:
 *  v1.1 — Added FraudStatus.REVIEW for borderline AI cases
 *        — Added reviewNotes field for human-review guidance
 *        — Added @JsonInclude to suppress null fields in API response
 *        — Added @JsonFormat for consistent LocalDateTime serialization
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)   // Omit null fields from JSON output
public class FraudResult implements Serializable {

    // ─── Core Identity Fields ──────────────────────────────────────────────

    /** Unique transaction identifier — echoed from the original request */
    private String transactionId;

    /** User who initiated the transaction */
    private String userId;

    // ─── Verdict Fields ────────────────────────────────────────────────────

    /**
     * Final fraud verdict:
     *   SAFE   — transaction is legitimate, allow it
     *   FRAUD  — transaction is fraudulent, block it
     *   REVIEW — confidence is borderline; escalate to human analyst
     */
    private FraudStatus status;

    /**
     * Human-readable explanation of the verdict.
     * Always populated regardless of status.
     *
     * Examples:
     *  - "Transaction amount 25000 INR exceeds high-risk threshold of 10000 INR"
     *  - "Transaction from unknown location: Antarctica"
     *  - "AI analysis: unusual spending pattern on new device"
     */
    private String reason;

    /**
     * AI confidence score: 0.0 → 1.0
     *   0.0 = certainly SAFE
     *   1.0 = certainly FRAUD
     *   0.4–0.6 = borderline → REVIEW
     *
     * Populated by all detection layers for consistency:
     *   RULE_BASED    → deterministic (0.85–1.0)
     *   REDIS_HISTORY → probabilistic (0.7–0.95)
     *   AI            → model-derived (0.0–1.0)
     */
    private Double confidenceScore;

    /**
     * Detection layer that produced this result:
     *   RULE_BASED    — fast rule engine caught it
     *   REDIS_HISTORY — user behaviour history triggered it
     *   AI            — OpenAI GPT made the call
     *   AI_FALLBACK   — AI was unavailable, defaulted to SAFE
     */
    private String detectionLayer;

    // ─── Review Fields (populated only when status = REVIEW) ──────────────

    /**
     * Additional notes for human analysts when status is REVIEW.
     * Contains the specific anomalies detected and what further
     * investigation is recommended.
     *
     * Example: "Device change from iPhone-14 to Samsung-Galaxy detected.
     *           Amount (8500 INR) is within threshold but device pattern is unusual."
     */
    private String reviewNotes;

    // ─── Audit Fields ──────────────────────────────────────────────────────

    /**
     * Timestamp when fraud analysis was completed.
     * Formatted as "yyyy-MM-dd HH:mm:ss" for readability.
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime analyzedAt;

    // ──────────────────────────────────────────────────────────────────────
    // FraudStatus Enum
    // ──────────────────────────────────────────────────────────────────────

    /**
     * FraudStatus — All possible fraud analysis outcomes.
     *
     * SAFE   → Allow the transaction. No suspicious indicators found.
     *
     * FRAUD  → Block the transaction immediately. High-confidence
     *          indicator triggered (amount threshold, unknown location,
     *          impossible travel, or AI verdict with confidence > 0.6).
     *
     * REVIEW → Flag the transaction for human review. AI confidence
     *          score falls in the uncertain range (0.4–0.6), or a soft
     *          anomaly was detected (device change without other indicators).
     *          Do NOT auto-block — hold for analyst decision.
     */
    public enum FraudStatus {

        /** Transaction appears legitimate — allow processing */
        SAFE,

        /** Transaction is flagged as fraudulent — block immediately */
        FRAUD,

        /**
         * Transaction is in a grey zone — requires human analyst review.
         * The system is not confident enough to auto-block or auto-approve.
         */
        REVIEW
    }
}
