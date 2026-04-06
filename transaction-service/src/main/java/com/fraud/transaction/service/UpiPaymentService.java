package com.fraud.transaction.service;

import com.fraud.common.dto.TransactionEvent;
import com.fraud.transaction.client.BankServiceValidationClient;
import com.fraud.transaction.dto.UpiPaymentRequest;
import com.fraud.transaction.exception.TransactionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * UpiPaymentService — builds a TransactionEvent for a UPI payment
 * and publishes it to Kafka for fraud analysis (Polish v3).
 *
 * Pre-flight checks (before publishing to Kafka):
 *   1. Rate limit — reject if payer has exceeded the per-user rate limit
 *   2. Payee UPI validation — reject if payee UPI ID is not registered in bank-service
 *
 * Flow after pre-flight:
 *   3. Build TransactionEvent with payer identity from JWT (payerUserId = email from header)
 *   4. Include X-Trace-Id (from MDC) in event for end-to-end trace correlation through Kafka
 *   5. Publish to Kafka topic "transactions"
 *   6. fraud-detection-service consumes, runs 3-layer fraud pipeline
 *   7. If SAFE → fraud-detection-service calls bank-service to debit payer and credit payee
 *   8. If FRAUD/REVIEW → payment is blocked; no money movement occurs
 *
 * CRITICAL: payerUserId comes from X-User-Email header (injected by gateway from JWT).
 * It is NEVER taken from the request body to prevent identity spoofing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UpiPaymentService {

    private final KafkaProducerService        kafkaProducerService;
    private final BankServiceValidationClient bankValidationClient;
    private final RateLimitService            rateLimitService;

    /**
     * Initiate a UPI payment by publishing a TransactionEvent to Kafka.
     *
     * Performs pre-flight rate limit and UPI ID validation before publishing.
     * Throws TransactionException (HTTP 422) if either check fails.
     *
     * @param request     validated UPI payment request from controller
     * @param payerEmail  email extracted from JWT by the API Gateway (X-User-Email header)
     * @return transactionId — clients poll GET /api/transactions/{id} for the fraud verdict
     */
    public String initiateUpiPayment(UpiPaymentRequest request, String payerEmail) {

        // ── Pre-flight 1: Rate limiting ────────────────────────────────────────
        if (!rateLimitService.isAllowed(payerEmail)) {
            log.warn("[UPI-SERVICE] Rate limit exceeded — payer={} payeeUpiId={}",
                    payerEmail, request.getPayeeUpiId());
            // 429 Too Many Requests — standard HTTP status for rate limiting
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Please wait before initiating another payment.");
        }

        // ── Pre-flight 2: Payee UPI ID validation ──────────────────────────────
        if (!bankValidationClient.isUpiIdRegistered(request.getPayeeUpiId())) {
            log.warn("[UPI-SERVICE] Payee UPI ID not registered — payer={} payeeUpiId={}",
                    payerEmail, request.getPayeeUpiId());
            throw new TransactionException(
                "Payee UPI ID is not registered: " + request.getPayeeUpiId());
        }

        // ── Build TransactionEvent ─────────────────────────────────────────────
        String transactionId = UUID.randomUUID().toString();
        String traceId = MDC.get("traceId");  // populated by TraceIdFilter from X-Trace-Id

        String paymentMode = (request.getPaymentMode() != null
                              && !request.getPaymentMode().isBlank())
                             ? request.getPaymentMode().toUpperCase()
                             : "BANK";

        TransactionEvent event = TransactionEvent.builder()
                .transactionId(transactionId)
                // userId = payer email (for legacy fraud pipeline compatibility)
                .userId(payerEmail)
                // UPI-specific fields
                .payerUserId(payerEmail)
                .payeeUpiId(request.getPayeeUpiId())
                .paymentMode(paymentMode)
                // Payment details
                .amount(request.getAmount())
                .device(request.getDevice() != null ? request.getDevice() : "UNKNOWN")
                .location(request.getLocation() != null ? request.getLocation() : "UNKNOWN")
                .merchantCategory(request.getMerchantCategory())
                .timestamp(LocalDateTime.now())
                // Distributed trace ID — propagated through Kafka for end-to-end correlation
                .traceId(traceId)
                .build();

        kafkaProducerService.sendTransaction(event);

        log.info("[UPI-SERVICE] Payment initiated: txId={} payer={} payee={} amount={} mode={} traceId={}",
                 transactionId, payerEmail, request.getPayeeUpiId(),
                 request.getAmount(), paymentMode, traceId);

        return transactionId;
    }
}
