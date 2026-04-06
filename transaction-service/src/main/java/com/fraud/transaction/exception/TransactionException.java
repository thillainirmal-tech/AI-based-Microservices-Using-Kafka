package com.fraud.transaction.exception;

/**
 * TransactionException — Custom Business Exception
 *
 * Thrown when a transaction request violates a business rule
 * that cannot be expressed as a Bean Validation constraint
 * (e.g., duplicate transactionId, rate-limit exceeded).
 */
public class TransactionException extends RuntimeException {

    public TransactionException(String message) {
        super(message);
    }

    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
