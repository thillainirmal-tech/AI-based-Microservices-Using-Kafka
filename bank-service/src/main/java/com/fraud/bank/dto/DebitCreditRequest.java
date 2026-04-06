package com.fraud.bank.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * DebitCreditRequest — payload for POST /bank/debit and POST /bank/credit
 *
 * Called exclusively by fraud-detection-service AFTER a SAFE verdict.
 * The `reason` field is used for audit logging.
 */
@Data
public class DebitCreditRequest {

    /** Email of the account holder to debit/credit */
    @NotBlank(message = "userId is required")
    private String userId;

    /** Amount to debit or credit (must be positive) */
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    /** Transaction ID for audit trail correlation */
    private String transactionId;

    /** Human-readable reason for this operation (e.g. "UPI payment SAFE verdict") */
    private String reason;
}
