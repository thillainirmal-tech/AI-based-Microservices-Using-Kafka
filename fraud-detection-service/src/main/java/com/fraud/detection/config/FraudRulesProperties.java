package com.fraud.detection.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * FraudRulesProperties — Externalised Fraud Detection Thresholds
 *
 * Maps all "fraud.rules.*" entries from application.yml into a
 * single, type-safe, validated configuration bean.
 *
 * WHY @ConfigurationProperties instead of multiple @Value:
 *  - Groups related config in one place (single source of truth)
 *  - Full IDE autocompletion with spring-boot-configuration-processor
 *  - Bean-level validation via @Validated + JSR-303 annotations
 *  - Easier to override per environment (dev/staging/prod profiles)
 *
 * Usage in any @Service or @Component:
 *   @Autowired
 *   private FraudRulesProperties rules;
 *   rules.getHighAmountThreshold()   → BigDecimal("10000")
 *   rules.getMaxTransactionsPerDay() → 10
 *
 * application.yml mapping:
 *   fraud:
 *     rules:
 *       high-amount-threshold: 10000
 *       max-transactions-per-day: 10
 *       impossible-travel-minutes: 5
 *       review-confidence-min: 0.4
 *       review-confidence-max: 0.6
 *       result-ttl-hours: 72
 */
@Data
@Validated                                          // Triggers JSR-303 validation on startup
@ConfigurationProperties(prefix = "fraud.rules")   // Binds all fraud.rules.* yaml keys
public class FraudRulesProperties {

    // ─── Amount Rules ──────────────────────────────────────────────────────

    /**
     * Transactions ABOVE this amount are immediately flagged as FRAUD.
     *
     * Default: 10000 INR
     * Override: fraud.rules.high-amount-threshold: 50000
     */
    @NotNull(message = "high-amount-threshold must be configured")
    @DecimalMin(value = "1.0", message = "high-amount-threshold must be at least 1")
    private BigDecimal highAmountThreshold = new BigDecimal("10000");

    // ─── Frequency Rules ───────────────────────────────────────────────────

    /**
     * Maximum number of transactions a user may submit within the 24-hour
     * Redis TTL window before being flagged as FRAUD (velocity check).
     *
     * Default: 10
     * Override: fraud.rules.max-transactions-per-day: 20
     */
    @Min(value = 1, message = "max-transactions-per-day must be at least 1")
    private int maxTransactionsPerDay = 10;

    // ─── Travel Rules ──────────────────────────────────────────────────────

    /**
     * Two transactions from DIFFERENT locations within this many minutes
     * are flagged as FRAUD (impossible travel / card sharing detection).
     *
     * Default: 5 minutes
     * Override: fraud.rules.impossible-travel-minutes: 10
     */
    @Min(value = 1, message = "impossible-travel-minutes must be at least 1 minute")
    private long impossibleTravelMinutes = 5;

    // ─── AI Review Confidence Band ──────────────────────────────────────────

    /**
     * AI confidence scores within [reviewConfidenceMin, reviewConfidenceMax]
     * produce a REVIEW verdict instead of a hard SAFE or FRAUD.
     *
     * Transactions in this confidence band are uncertain — human review needed.
     *
     * Default range: 0.4 – 0.6  (40% – 60% confidence in fraud)
     */
    @DecimalMin(value = "0.0", message = "review-confidence-min must be >= 0.0")
    @DecimalMax(value = "1.0", message = "review-confidence-min must be <= 1.0")
    private double reviewConfidenceMin = 0.4;

    @DecimalMin(value = "0.0", message = "review-confidence-max must be >= 0.0")
    @DecimalMax(value = "1.0", message = "review-confidence-max must be <= 1.0")
    private double reviewConfidenceMax = 0.6;

    // ─── Redis Result Storage ──────────────────────────────────────────────

    /**
     * How long to keep FraudResult entries in Redis after they are written.
     * After this TTL expires, the result is automatically evicted.
     *
     * Default: 72 hours (3 days)
     * Override: fraud.rules.result-ttl-hours: 168
     */
    @Min(value = 1, message = "result-ttl-hours must be at least 1 hour")
    private long resultTtlHours = 72;

    // ─── Cross-field Validation ────────────────────────────────────────────

    /**
     * Validates that reviewConfidenceMin < reviewConfidenceMax after all
     * properties have been bound from application.yml.
     *
     * JSR-303 @Min / @DecimalMax annotations validate each field individually
     * but cannot enforce relationships between fields — @PostConstruct fills that gap.
     *
     * Fails fast at startup so a misconfigured YAML is caught immediately,
     * rather than producing silent classification errors at runtime.
     */
    @PostConstruct
    public void validateConfidenceBand() {
        if (reviewConfidenceMin >= reviewConfidenceMax) {
            throw new IllegalStateException(
                    String.format("Invalid fraud.rules configuration: "
                                    + "review-confidence-min (%.2f) must be strictly less than "
                                    + "review-confidence-max (%.2f). "
                                    + "Check your application.yml or environment overrides.",
                            reviewConfidenceMin, reviewConfidenceMax));
        }
    }
}
