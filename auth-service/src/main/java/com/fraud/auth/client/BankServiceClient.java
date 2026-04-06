package com.fraud.auth.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * BankServiceClient — REST client for auth-service → bank-service calls.
 *
 * Called by AuthService during user registration to create a default bank account
 * (balance = 10,000) for the new user. This call happens AFTER the user record is
 * committed to the database — not inside the @Transactional boundary.
 *
 * Internal Endpoint Authorization:
 *   /bank/account/create on bank-service requires the header:
 *     X-Internal-Service: auth-service
 *   This header is automatically included in all calls from this client.
 *
 * Failure is non-fatal: user is still registered if bank account creation fails.
 * For production: wrap in a distributed saga or event-driven pattern.
 */
@Slf4j
@Component
public class BankServiceClient {

    private static final String INTERNAL_SERVICE_HEADER = "X-Internal-Service";
    private static final String SERVICE_NAME            = "auth-service";

    private final RestTemplate restTemplate;
    private final String bankServiceUrl;

    public BankServiceClient(RestTemplate restTemplate,
                             @Value("${bank-service.url:http://localhost:8084}") String bankServiceUrl) {
        this.restTemplate   = restTemplate;
        this.bankServiceUrl = bankServiceUrl;
    }

    /**
     * Create a bank account for a newly registered user.
     *
     * POST bank-service/bank/account/create
     * Body: { "userId": email, "upiId": "name@upi" }
     * Headers: X-Internal-Service: auth-service
     *
     * @param userId email of the newly registered user
     * @param upiId  auto-generated UPI ID (e.g. "johnsmith@upi")
     */
    public void createBankAccount(String userId, String upiId) {
        String url = bankServiceUrl + "/bank/account/create";

        Map<String, String> body = Map.of(
                "userId", userId,
                "upiId",  upiId
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(INTERNAL_SERVICE_HEADER, SERVICE_NAME);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            restTemplate.postForEntity(url, request, Map.class);
            log.info("[BANK-CLIENT] Bank account created for userId={} upiId={}", userId, upiId);
        } catch (RestClientException e) {
            // Non-fatal: user is still registered. Ops team must reconcile.
            log.error("[BANK-CLIENT] ✗ Failed to create bank account for userId={} | Error: {}",
                      userId, e.getMessage());
        }
    }
}
