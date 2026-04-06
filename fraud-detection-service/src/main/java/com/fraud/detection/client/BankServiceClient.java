package com.fraud.detection.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * BankServiceClient — REST client for fraud-detection-service → bank-service calls (Polish v3)
 *
 * All money movement goes through this client. It is invoked ONLY by
 * PaymentProcessorService, and only after a SAFE fraud verdict.
 *
 * Bank Service Internal Endpoint Protection:
 *   /debit, /credit, /refund on bank-service require the header:
 *     X-Internal-Service: fraud-detection-service
 *   This provides application-layer protection against accidental external callers.
 *   All calls from this client automatically include this header.
 *
 * Failure contract:
 *   All methods return boolean success/failure (never throw).
 *   Callers (PaymentProcessorService) decide whether to compensate or abort.
 *
 * In production: replace RestTemplate with Feign or WebClient for circuit-breaking.
 */
@Slf4j
@Component
public class BankServiceClient {

    private static final String INTERNAL_SERVICE_HEADER = "X-Internal-Service";
    private static final String SERVICE_NAME            = "fraud-detection-service";

    private final RestTemplate restTemplate;
    private final String       bankServiceUrl;

    public BankServiceClient(RestTemplate restTemplate,
                             @Value("${bank-service.url:http://localhost:8084}") String bankServiceUrl) {
        this.restTemplate   = restTemplate;
        this.bankServiceUrl = bankServiceUrl;
    }

    // ─── UPI ID Resolution ────────────────────────────────────────────────────

    /**
     * Resolve payee's userId (email) from their UPI ID.
     * Returns null if UPI ID is not registered (payment must be aborted).
     * This is a read endpoint — no internal-service header required.
     */
    public String resolveUserIdByUpiId(String upiId) {
        String url = bankServiceUrl + "/bank/account/by-upi/" + upiId;
        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getBody() != null && response.getBody().containsKey("userId")) {
                String userId = (String) response.getBody().get("userId");
                log.info("[BANK-CLIENT] Resolved UPI '{}' → userId='{}'", upiId, userId);
                return userId;
            }
            log.warn("[BANK-CLIENT] UPI '{}' resolved but no userId in response body", upiId);
            return null;
        } catch (RestClientException e) {
            log.error("[BANK-CLIENT] Failed to resolve UPI '{}': {}", upiId, e.getMessage());
            return null;
        }
    }

    // ─── Debit ────────────────────────────────────────────────────────────────

    /**
     * Debit (subtract) amount from payer's account.
     * Called ONLY after SAFE fraud verdict. Returns true on HTTP 2xx.
     * Sends X-Internal-Service header to satisfy bank-service internal endpoint protection.
     */
    public boolean debit(String userId, BigDecimal amount, String transactionId) {
        String url = bankServiceUrl + "/bank/debit";
        Map<String, Object> body = Map.of(
                "userId",        userId,
                "amount",        amount,
                "transactionId", transactionId,
                "reason",        "UPI payment — SAFE verdict"
        );
        try {
            restTemplate.postForEntity(url, buildInternalRequest(body), Map.class);
            log.info("[BANK-CLIENT] ✓ Debit OK — userId={} amount={} txId={}", userId, amount, transactionId);
            return true;
        } catch (RestClientException e) {
            log.error("[BANK-CLIENT] ✗ Debit FAILED — userId={} amount={} txId={} | {}",
                    userId, amount, transactionId, e.getMessage());
            return false;
        }
    }

    // ─── Credit ───────────────────────────────────────────────────────────────

    /**
     * Credit (add) amount to payee's account.
     * Called only if debit succeeded. Returns true on HTTP 2xx.
     * Sends X-Internal-Service header to satisfy bank-service internal endpoint protection.
     *
     * CRITICAL: if this returns false after debit has succeeded,
     * PaymentProcessorService MUST call refund() immediately.
     */
    public boolean credit(String userId, BigDecimal amount, String transactionId) {
        String url = bankServiceUrl + "/bank/credit";
        Map<String, Object> body = Map.of(
                "userId",        userId,
                "amount",        amount,
                "transactionId", transactionId,
                "reason",        "UPI payment receipt"
        );
        try {
            restTemplate.postForEntity(url, buildInternalRequest(body), Map.class);
            log.info("[BANK-CLIENT] ✓ Credit OK — userId={} amount={} txId={}", userId, amount, transactionId);
            return true;
        } catch (RestClientException e) {
            log.error("[BANK-CLIENT] ✗ Credit FAILED — userId={} amount={} txId={} | {}",
                    userId, amount, transactionId, e.getMessage());
            return false;
        }
    }

    // ─── Refund (Compensation) ────────────────────────────────────────────────

    /**
     * Refund — credit amount BACK to the payer as compensation.
     *
     * Called by PaymentProcessorService when:
     *   - debit(payer) succeeded
     *   - credit(payee) FAILED
     *   → Payer must be made whole to avoid net money loss
     *
     * Uses the /bank/refund endpoint (separate TransactionType.REFUND idempotency key).
     * Sends X-Internal-Service header to satisfy bank-service internal endpoint protection.
     *
     * Returns true if refund succeeded, false if it also failed.
     * If false: PaymentProcessorService marks status COMPENSATION_FAILED
     * and the operations team must reconcile manually.
     */
    public boolean refund(String payerUserId, BigDecimal amount, String originalTransactionId) {
        String refundTxId = originalTransactionId + ":REFUND";
        String url = bankServiceUrl + "/bank/refund";
        Map<String, Object> body = Map.of(
                "userId",        payerUserId,
                "amount",        amount,
                "transactionId", refundTxId,
                "reason",        "Compensation refund — payee credit failed for txId=" + originalTransactionId
        );
        try {
            restTemplate.postForEntity(url, buildInternalRequest(body), Map.class);
            log.info("[BANK-CLIENT] ✓ Refund OK — payerUserId={} amount={} refundTxId={}",
                    payerUserId, amount, refundTxId);
            return true;
        } catch (RestClientException e) {
            log.error("[BANK-CLIENT] ✗✗ Refund FAILED — payerUserId={} amount={} refundTxId={} | {} "
                    + "— MANUAL RECONCILIATION REQUIRED",
                    payerUserId, amount, refundTxId, e.getMessage());
            return false;
        }
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Build an HttpEntity with the X-Internal-Service header identifying this caller.
     * Bank-service internal endpoints (/debit, /credit, /refund) require this header.
     */
    private HttpEntity<Map<String, Object>> buildInternalRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(INTERNAL_SERVICE_HEADER, SERVICE_NAME);
        return new HttpEntity<>(body, headers);
    }
}
