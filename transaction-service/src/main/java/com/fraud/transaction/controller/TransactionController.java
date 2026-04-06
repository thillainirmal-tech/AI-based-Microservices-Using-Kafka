package com.fraud.transaction.controller;

import com.fraud.transaction.client.FraudServiceClient;
import com.fraud.transaction.dto.TransactionRequest;
import com.fraud.transaction.dto.TransactionResponse;
import com.fraud.transaction.dto.TransactionStatusResponse;
import com.fraud.transaction.service.TransactionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * TransactionController — REST API for transaction-service (Polish v3)
 *
 * Endpoints:
 *   POST /api/transactions          — submit transaction for fraud analysis (legacy)
 *   GET  /api/transactions/{id}     — poll combined fraud verdict + payment status
 *   GET  /api/transactions/health   — liveness check
 *
 * GET /api/transactions/{id} combines two fraud-detection-service calls:
 *   1. GET /api/fraud/result/{id}   → FraudResult
 *   2. GET /api/fraud/payment/{id}  → PaymentRecord
 * and returns a unified TransactionStatusResponse.
 *
 * Identity enforcement:
 *   POST /api/transactions — protected by gateway; X-User-Email overrides body userId
 *                           to prevent identity spoofing via request body manipulation.
 *   GET  /api/transactions/{id} — transactionId from path; no user identity required.
 *   POST /api/upi/pay         — payerEmail ONLY from X-User-Email (see UpiController).
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    @Autowired private TransactionService transactionService;
    @Autowired private FraudServiceClient fraudServiceClient;

    // ── Submit Transaction ─────────────────────────
    @PostMapping
    public ResponseEntity<TransactionResponse> submitTransaction(
            @Valid @RequestBody TransactionRequest request,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail) {

        if (userEmail != null && !userEmail.isBlank()) {
            if (!userEmail.equals(request.getUserId())) {
                logger.warn("[TX-CTRL] IDENTITY_OVERRIDE body={} header={}",
                        request.getUserId(), userEmail);
            }
            request.setUserId(userEmail);
        }

        logger.info("[TX-LIFECYCLE] SUBMITTED txId={} userId={} amount={}",
                request.getTransactionId(), request.getUserId(), request.getAmount());

        TransactionResponse response = transactionService.processTransaction(request);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    // ── Transaction Status ─────────────────────────
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionStatusResponse> getTransactionStatus(
            @PathVariable("transactionId") String transactionId) {

        logger.info("[TX-LIFECYCLE] STATUS txId={}", transactionId);

        try {
            Map<String, Object> fraudResult = fraudServiceClient.getFraudResult(transactionId);

            if (fraudResult == null) {
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(TransactionStatusResponse.builder()
                                .transactionId(transactionId)
                                .fraudStatus("PENDING")
                                .overallStatus("PENDING")
                                .message("Queued for fraud analysis")
                                .retryAfterSeconds(3)
                                .timestamp(LocalDateTime.now().toString())
                                .build());
            }

            String fraudStatus = safeStr(fraudResult, "status");

            TransactionStatusResponse.TransactionStatusResponseBuilder builder =
                    TransactionStatusResponse.builder()
                            .transactionId(transactionId)
                            .fraudStatus(fraudStatus)
                            .fraudConfidence(safeDouble(fraudResult, "confidenceScore"))
                            .fraudDetectionLayer(safeStr(fraudResult, "detectionLayer"))
                            .fraudReason(safeStr(fraudResult, "reason"))
                            .timestamp(LocalDateTime.now().toString());

            if ("FRAUD".equals(fraudStatus) || "REVIEW".equals(fraudStatus)) {
                return ResponseEntity.ok(builder
                        .overallStatus("BLOCKED")
                        .message("Blocked (" + fraudStatus + ")")
                        .build());
            }

            Map<String, Object> paymentRecord = fraudServiceClient.getPaymentRecord(transactionId);

            if (paymentRecord == null) {
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(builder
                                .overallStatus("PENDING")
                                .message("Fraud SAFE — processing payment")
                                .retryAfterSeconds(2)
                                .build());
            }

            // FULL MAPPING FIX
            String paymentStatus = safeStr(paymentRecord, "paymentStatus");
            String payerEmail = safeStr(paymentRecord, "payerEmail");
            String payeeEmail = safeStr(paymentRecord, "payeeEmail");
            String payeeUpiId = safeStr(paymentRecord, "payeeUpiId");
            String paymentMode = safeStr(paymentRecord, "paymentMode");
            BigDecimal amount = safeBigDecimal(paymentRecord, "amount");
            String razorpayOrderId = safeStr(paymentRecord, "razorpayOrderId");

            String overall = switch (paymentStatus) {
                case "SUCCESS" -> "COMPLETE";
                case "FAILED" -> "FAILED";
                case "COMPENSATED" -> "COMPENSATED";
                case "COMPENSATION_FAILED" -> "CRITICAL";
                case "RAZORPAY_ORDER_CREATED" -> "RAZORPAY_PENDING";
                default -> "PENDING";
            };

            return ResponseEntity.ok(builder
                    .overallStatus(overall)
                    .paymentStatus(paymentStatus)
                    .paymentMode(paymentMode)
                    .amount(amount)
                    .payerEmail(payerEmail)
                    .payeeEmail(payeeEmail)
                    .payeeUpiId(payeeUpiId)
                    .razorpayOrderId(razorpayOrderId)
                    .message("Final status")
                    .build());

        } catch (Exception e) {
            logger.error("[TX-CTRL] ERROR txId={}", transactionId, e);

            return ResponseEntity.ok(
                    TransactionStatusResponse.builder()
                            .transactionId(transactionId)
                            .overallStatus("BLOCKED")
                            .message("Safe fallback")
                            .build()
            );
        }
    }

    // ── Helpers ─────────────────────────
    private String safeStr(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Double safeDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        try { return Double.parseDouble(val.toString()); }
        catch (Exception e) { return null; }
    }

    private BigDecimal safeBigDecimal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        try { return new BigDecimal(val.toString()); }
        catch (Exception e) { return null; }
    }
}
