package com.fraud.bank.exception;

/**
 * InsufficientFundsException — thrown when a debit is attempted on an account
 * that does not have enough balance to cover the requested amount.
 *
 * Maps to HTTP 422 Unprocessable Entity in GlobalExceptionHandler.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
