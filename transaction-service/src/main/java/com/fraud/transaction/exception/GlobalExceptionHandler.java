package com.fraud.transaction.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GlobalExceptionHandler — centralised error handling for transaction-service (Polish v3)
 *
 * All error responses use the standard schema:
 *   { traceId, timestamp, httpStatus, error, message, path }
 *
 * Exception → HTTP status mapping:
 *   ResponseStatusException     → uses exception's status (e.g., 429 for rate limit)
 *   MethodArgumentNotValidException → 400 Bad Request (validation)
 *   TransactionException            → 422 Unprocessable Entity (business rules)
 *   Exception (catch-all)           → 500 Internal Server Error
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles ResponseStatusException — used for rate limiting (429) and other
     * HTTP-status-specific errors thrown from service layer.
     * Passes through the exception's status code directly.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.warn("[TX-EX] {} — path={} message={}", status.value(), request.getRequestURI(), ex.getReason());
        return ResponseEntity.status(status)
                .body(buildError(status, ex.getReason(), request));
    }

    /**
     * Handles @Valid bean validation failures.
     * Returns field-level error details so the client knows which fields failed.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        StringBuilder details = new StringBuilder();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            if (details.length() > 0) details.append(", ");
            details.append(error.getField()).append(": ").append(error.getDefaultMessage());
        }

        log.warn("[TX-EX] Validation error at {}: {}", request.getRequestURI(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, "Validation failed: " + details, request));
    }

    /**
     * Handles custom business rule violations from the service layer.
     */
    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<Map<String, Object>> handleTransactionException(
            TransactionException ex, HttpServletRequest request) {
        log.warn("[TX-EX] Transaction exception at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(buildError(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), request));
    }

    /**
     * Catch-all handler for any unexpected exceptions.
     * Avoids leaking stack traces to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(
            Exception ex, HttpServletRequest request) {
        log.error("[TX-EX] Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please try again later.", request));
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private Map<String, Object> buildError(HttpStatus status, String message,
                                            HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId",    UUID.randomUUID().toString());
        body.put("timestamp",  LocalDateTime.now().toString());
        body.put("httpStatus", status.value());
        body.put("error",      status.getReasonPhrase());
        body.put("message",    message);
        body.put("path",       request.getRequestURI());
        return body;
    }
}
