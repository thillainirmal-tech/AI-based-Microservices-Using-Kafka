package com.fraud.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * TransactionRequest — Incoming REST Request DTO
 *
 * This is what the client (React frontend or Postman) sends to:
 *   POST /api/transactions
 *
 * Bean Validation annotations ensure we reject malformed requests
 * before they reach Kafka.
 *
 * Example JSON:
 * {
 *   "transactionId": "TXN-001",
 *   "userId":        "USR-42",
 *   "amount":        15000.00,
 *   "location":      "Mumbai",
 *   "device":        "iPhone-14",
 *   "merchantCategory": "Electronics"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {

    /** Required — unique transaction identifier (UUID or custom) */
    @NotBlank(message = "transactionId must not be blank")
    private String transactionId;

    /** Required — user who initiated the transaction */
    @NotBlank(message = "userId must not be blank")
    private String userId;

    /**
     * Required — transaction amount.
     * Must be > 0. No upper limit enforced here; fraud detection handles that.
     */
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    /** Required — location from which the transaction was initiated */
    @NotBlank(message = "location must not be blank")
    private String location;

    /** Required — device used for the transaction */
    @NotBlank(message = "device must not be blank")
    private String device;

    /**
     * Optional — merchant category (e.g., "Electronics", "Food", "Travel").
     * Enriches AI fraud analysis context.
     */
    private String merchantCategory;
}
