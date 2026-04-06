package com.fraud.detection.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — Centralised Error Handling for fraud-detection-service
 *
 * Intercepts exceptions from all @RestController methods and converts them
 * into consistent, structured JSON error responses.
 *
 * Handled exception types:
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │ ConstraintViolationException  → 400  (path variable @NotBlank) │
 *  │ MethodArgumentNotValidException → 400 (@Valid request body)   │
 *  │ MethodArgumentTypeMismatchException → 400 (type mismatch)     │
 *  │ FraudDetectionException         → 500 (business error)        │
 *  │ Exception (catch-all)           → 500 (unexpected)            │
 *  └─────────────────────────────────────────────────────────────┘
 *
 * All responses share the same JSON structure for client consistency:
 * {
 *   "timestamp": "2024-01-15 10:30:00",
 *   "httpStatus": 400,
 *   "error": "Bad Request",
 *   "message": "...",
 *   "details": { ... }   // optional
 * }
 *
 * CHANGE LOG v1.1:
 *  — Added ConstraintViolationException handler for @Validated path variables
 *  — Added MethodArgumentTypeMismatchException handler
 *  — Standardised all responses to use LinkedHashMap for field ordering
 *  — Added SLF4J structured logging for each exception type
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ══════════════════════════════════════════════════════════════════════
    //  400 — Validation Errors
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handles @Valid failures on @RequestBody parameters.
     *
     * Triggered when a field in the request body fails a JSR-303 constraint
     * (e.g., @NotBlank, @NotNull, @DecimalMin).
     *
     * Response body includes a "fieldErrors" map showing exactly which fields
     * failed and why — makes debugging straightforward for API clients.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleRequestBodyValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (existing, duplicate) -> existing  // keep first error per field
                ));

        log.warn("[EXCEPTION] Request body validation failed — path: {} | fields: {}",
                request.getRequestURI(), fieldErrors);

        Map<String, Object> body = buildErrorBody(
                HttpStatus.BAD_REQUEST,
                "Request body validation failed. Check 'fieldErrors' for details.",
                request);
        body.put("fieldErrors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles @NotBlank / @NotNull violations on @PathVariable parameters.
     *
     * Triggered when a path variable fails a JSR-303 constraint declared
     * on the controller method parameter (requires @Validated on the class).
     *
     * Example: GET /api/fraud/result/  → blank transactionId
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handlePathVariableValidation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        // Collect all violation messages
        String violations = ex.getConstraintViolations()
                .stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));

        log.warn("[EXCEPTION] Path variable / parameter validation failed — path: {} | {}",
                request.getRequestURI(), violations);

        Map<String, Object> body = buildErrorBody(
                HttpStatus.BAD_REQUEST,
                "Path variable validation failed: " + violations,
                request);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles type mismatch on path variables or request parameters.
     *
     * Example: GET /api/fraud/result/123abc when an integer is expected.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {

        String message = String.format(
                "Path variable '%s' has an invalid value '%s'. Expected type: %s",
                ex.getName(),
                ex.getValue(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");

        log.warn("[EXCEPTION] Type mismatch — path: {} | {}", request.getRequestURI(), message);

        Map<String, Object> body = buildErrorBody(HttpStatus.BAD_REQUEST, message, request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  500 — Business / System Errors
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Handles custom business rule exceptions from the fraud detection pipeline.
     *
     * Thrown explicitly by FraudDetectionService or AiFraudAnalysisService
     * when a critical, non-recoverable condition is encountered.
     */
    @ExceptionHandler(FraudDetectionException.class)
    public ResponseEntity<Map<String, Object>> handleFraudDetectionException(
            FraudDetectionException ex,
            HttpServletRequest request) {
        log.error("[EXCEPTION] FraudDetectionException — path: {} | {}", request.getRequestURI(), ex.getMessage(), ex);
        Map<String, Object> body = buildErrorBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Fraud detection pipeline error: " + ex.getMessage(),
                request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Catch-all handler for any unhandled exception.
     *
     * Deliberately vague in the client-facing message to avoid leaking
     * internal implementation details. Full stack trace is logged server-side.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request) {
        log.error("[EXCEPTION] Unexpected error — path: {} | class: {} | message: {}",
                request.getRequestURI(), ex.getClass().getSimpleName(), ex.getMessage(), ex);
        Map<String, Object> body = buildErrorBody(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Please try again later.",
                request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helper
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Builds the base error response map shared by all exception handlers.
     * Using LinkedHashMap to guarantee field order in the JSON output.
     *
     * Response fields:
     *  - traceId    : unique UUID per error event — use for log correlation in ELK/Splunk/CloudWatch
     *  - timestamp  : when the error occurred
     *  - httpStatus : numeric HTTP status code
     *  - error      : standard HTTP reason phrase
     *  - message    : human-readable explanation
     *  - path       : the endpoint that triggered the error
     */
    private Map<String, Object> buildErrorBody(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", UUID.randomUUID().toString());   // Unique ID for log correlation
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("httpStatus", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
