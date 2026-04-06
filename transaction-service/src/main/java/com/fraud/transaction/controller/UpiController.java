package com.fraud.transaction.controller;

import com.fraud.transaction.dto.UpiPaymentRequest;
import com.fraud.transaction.service.UpiPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * UpiController — UPI payment submission endpoint (Hardening v2)
 *
 * POST /api/upi/pay
 *
 * ─── Identity Enforcement (X-User-Email) ────────────────────────────────────
 *
 * RULE: The payer's identity MUST come from X-User-Email ONLY.
 *       NEVER from the request body. NEVER from any other header.
 *
 * Why:
 *   - The API Gateway validates the JWT and injects X-User-Email.
 *   - Accepting payerUserId from the request body would allow clients to
 *     impersonate other users (identity spoofing).
 *   - If X-User-Email is absent, the request bypassed the gateway — reject it.
 *
 * Enforcement:
 *   1. X-User-Email missing → 401 Unauthorized (gateway bypass detected)
 *   2. X-User-Email present → injected into TransactionEvent as payerUserId
 *   3. Any payerUserId field in the request body is IGNORED.
 *
 * ─── Security Logging ────────────────────────────────────────────────────────
 *
 * Every rejected request logs a WARNING with sufficient context for SIEM alerting.
 * Pattern: [UPI-CTRL] IDENTITY_VIOLATION — missing X-User-Email at ...
 */
@Slf4j
@RestController
@RequestMapping("/api/upi")
@RequiredArgsConstructor
public class UpiController {

    private final UpiPaymentService upiPaymentService;

    /**
     * POST /api/upi/pay
     *
     * Submits a UPI payment for async fraud analysis. Returns 202 Accepted.
     * The payer is identified exclusively by the X-User-Email header.
     *
     * Request:
     *   Authorization: Bearer <jwt>          (validated by gateway)
     *   X-User-Email: john@example.com       (injected by gateway after JWT validation)
     *   Body: { "payeeUpiId": "alice@upi", "amount": 500.00, "paymentMode": "BANK" }
     *
     * Response (202):
     *   { "transactionId": "uuid", "status": "PENDING",
     *     "message": "Poll /api/transactions/{id} for result",
     *     "payerEmail": "john@example.com", "payeeUpiId": "alice@upi", "amount": 500.00 }
     *
     * Error responses:
     *   401 — X-User-Email missing (gateway bypass)
     *   400 — request body validation failed
     */
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> pay(
            @Valid @RequestBody UpiPaymentRequest request,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        // ── Identity enforcement: reject gateway bypass ───────────────────────
        if (userEmail == null || userEmail.isBlank()) {
            // This should never happen in normal operation — the gateway injects this header.
            // If it's missing, the request bypassed the gateway (firewall misconfiguration
            // or direct internal access).
            String traceId = UUID.randomUUID().toString();
            log.warn("[UPI-CTRL] IDENTITY_VIOLATION — X-User-Email header absent. "
                    + "Request may have bypassed the API Gateway. "
                    + "payeeUpiId={} amount={} traceId={}",
                    request.getPayeeUpiId(), request.getAmount(), traceId);

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(buildError(HttpStatus.UNAUTHORIZED, traceId,
                            "Authentication required. "
                            + "X-User-Email header missing — request must pass through API Gateway.",
                            "/api/upi/pay"));
        }

        log.info("[UPI-CTRL] Pay — payer={} payee={} amount={} mode={}",
                userEmail, request.getPayeeUpiId(), request.getAmount(), request.getPaymentMode());

        String transactionId = upiPaymentService.initiateUpiPayment(request, userEmail);

        log.info("[TX-LIFECYCLE] INITIATED txId={} payerEmail={} payeeUpiId={} amount={} mode={}",
                transactionId, userEmail, request.getPayeeUpiId(),
                request.getAmount(),
                request.getPaymentMode() != null ? request.getPaymentMode() : "BANK");

        log.info("[UPI-CTRL] Accepted — txId={} payer={}", transactionId, userEmail);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "transactionId", transactionId,
                "status",        "PENDING",
                "message",       "Payment submitted for fraud analysis. "
                                 + "Poll GET /api/transactions/" + transactionId + " for result.",
                "payerEmail",    userEmail,
                "payeeUpiId",    request.getPayeeUpiId(),
                "amount",        request.getAmount(),
                "paymentMode",   request.getPaymentMode() != null ? request.getPaymentMode() : "BANK",
                "timestamp",     LocalDateTime.now().toString()
        ));
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildError(org.springframework.http.HttpStatus status,
                                            String traceId, String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId",    traceId);
        body.put("timestamp",  LocalDateTime.now().toString());
        body.put("httpStatus", status.value());
        body.put("error",      status.getReasonPhrase());
        body.put("message",    message);
        body.put("path",       path);
        return body;
    }
}
