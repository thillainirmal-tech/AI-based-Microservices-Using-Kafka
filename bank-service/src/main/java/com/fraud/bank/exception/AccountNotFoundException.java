package com.fraud.bank.exception;

/**
 * AccountNotFoundException — thrown when a bank account cannot be found
 * by userId or upiId.
 *
 * Maps to HTTP 404 Not Found in GlobalExceptionHandler.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
