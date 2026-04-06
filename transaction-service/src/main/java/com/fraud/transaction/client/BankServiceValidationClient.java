package com.fraud.transaction.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * BankServiceValidationClient — pre-payment payee UPI ID validation.
 *
 * Called by UpiPaymentService BEFORE publishing to Kafka to verify the payee's
 * UPI ID exists in bank-service. This prevents Kafka messages that will always
 * fail in the fraud-detection-service payment processor, polluting the audit log
 * with guaranteed-failed transactions.
 *
 * Validation is a read-only operation — no state is changed.
 *
 * If bank-service is unreachable (RestClientException), the validation is treated
 * as passing (fail-open) to preserve availability. The payment will still be
 * rejected by PaymentProcessorService if the UPI ID is truly invalid.
 * This trade-off can be reversed to fail-closed by changing the catch block.
 */
@Slf4j
@Component
public class BankServiceValidationClient {

    private final RestTemplate restTemplate;
    private final String bankServiceUrl;

    public BankServiceValidationClient(
            RestTemplate restTemplate,
            @Value("${bank-service.url:http://localhost:8084}") String bankServiceUrl) {
        this.restTemplate   = restTemplate;
        this.bankServiceUrl = bankServiceUrl;
    }

    /**
     * Check whether a UPI ID is registered in bank-service.
     *
     * Calls GET /bank/account/by-upi/{upiId}.
     *   - 200 → UPI ID exists → returns true
     *   - 404 → UPI ID not found → returns false
     *   - Network error → fail-open → returns true (with warning log)
     *
     * @param upiId the payee's UPI ID to validate
     * @return true if the UPI ID is registered or bank-service is unreachable
     */
    public boolean isUpiIdRegistered(String upiId) {
        String url = bankServiceUrl + "/bank/account/by-upi/" + upiId;
        try {
            restTemplate.getForEntity(url, Object.class);
            log.debug("[BANK-VALIDATION] UPI ID '{}' is registered.", upiId);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("[BANK-VALIDATION] UPI ID '{}' is NOT registered in bank-service.", upiId);
            return false;
        } catch (RestClientException e) {
            // Fail-open: bank-service unreachable, do not block payment initiation.
            // PaymentProcessorService will detect the invalid UPI ID later.
            log.warn("[BANK-VALIDATION] bank-service unreachable during UPI pre-validation "
                    + "for upiId='{}' — failing open. Error: {}", upiId, e.getMessage());
            return true;
        }
    }
}
