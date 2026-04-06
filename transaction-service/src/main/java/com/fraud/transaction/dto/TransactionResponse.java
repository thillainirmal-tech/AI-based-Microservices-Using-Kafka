package com.fraud.transaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TransactionResponse — REST API Response DTO
 *
 * Returned to the client after a transaction is submitted.
 * At this point the transaction has been accepted and queued to Kafka —
 * the fraud result will be processed asynchronously.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    /** Echo of the submitted transactionId */
    private String transactionId;

    /** Submission status message */
    private String message;

    /** HTTP-equivalent status code (for programmatic clients) */
    private int statusCode;

    /**
     * Convenience factory — creates a success response
     */
    public static TransactionResponse accepted(String transactionId) {
        return TransactionResponse.builder()
                .transactionId(transactionId)
                .message("Transaction accepted and queued for fraud analysis")
                .statusCode(202)
                .build();
    }
}
