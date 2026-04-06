package com.fraud.detection.exception;

/**
 * FraudDetectionException — Custom exception for the fraud detection pipeline.
 * Thrown when a critical error occurs during analysis that prevents a verdict.
 */
public class FraudDetectionException extends RuntimeException {

    public FraudDetectionException(String message) {
        super(message);
    }

    public FraudDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
