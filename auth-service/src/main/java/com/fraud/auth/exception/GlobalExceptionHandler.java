package com.fraud.auth.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — auth-service (Hardening v2)
 *
 * Consistent error schema across all services:
 *   { traceId, timestamp, httpStatus, error, message, path }
 *
 * Security: AuthController already swallows distinction between
 * "email not found" and "wrong password" (both return 401 with a generic message)
 * to prevent user enumeration. This handler reinforces that.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("[AUTH-EX] Validation failed at {}: {}", request.getRequestURI(), details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST,
                        "Validation failed: " + details, request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        // Email-already-registered → 409 Conflict
        // Invalid-credentials      → 401 Unauthorized (controller handles that)
        log.warn("[AUTH-EX] IllegalArgument at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(buildError(HttpStatus.CONFLICT, ex.getMessage(), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(
            Exception ex, HttpServletRequest request) {
        log.error("[AUTH-EX] Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please try again.", request));
    }

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
