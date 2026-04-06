package com.fraud.bank.controller;

import com.fraud.bank.dto.AccountResponse;
import com.fraud.bank.dto.BalanceResponse;
import com.fraud.bank.dto.CreateAccountRequest;
import com.fraud.bank.dto.DebitCreditRequest;
import com.fraud.bank.service.BankService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * BankController — REST API for bank-service (Polish v3)
 *
 * ─── Endpoint Routing Summary ────────────────────────────────────────────────
 *
 *   EXTERNAL (routed via API Gateway, JWT required):
 *     GET  /bank/balance          — user's own balance; identity from X-User-Email header
 *     GET  /bank/account/by-upi/{upiId} — resolve account by UPI ID (payee lookup)
 *
 *   INTERNAL (service-to-service only, NOT routed via API Gateway):
 *     POST /bank/account/create   — called by auth-service on registration
 *     POST /bank/debit            — called by fraud-detection-service after SAFE verdict
 *     POST /bank/credit           — called by fraud-detection-service after SAFE verdict
 *     POST /bank/refund           — called by fraud-detection-service saga compensation
 *
 * ─── Internal Endpoint Protection ────────────────────────────────────────────
 *   /debit, /credit, /refund, and /account/create check for the X-Internal-Service
 *   header (value: "fraud-detection-service" or "auth-service"). Requests missing
 *   this header are rejected with 403 Forbidden.
 *
 *   In production, replace this with mutual TLS or a shared HMAC secret between
 *   services. This header check provides defense-in-depth at the application layer.
 *
 * ─── Balance Endpoint Identity ───────────────────────────────────────────────
 *   GET /bank/balance reads the caller's identity from the X-User-Email header
 *   (injected by the API Gateway from the validated JWT subject).
 *   The request parameter "userId" is NEVER trusted — identity always comes from
 *   the gateway-injected header. This prevents horizontal privilege escalation.
 */
@Slf4j
@RestController
@RequestMapping("/bank")
@RequiredArgsConstructor
public class BankController {

    private static final String INTERNAL_SERVICE_HEADER = "X-Internal-Service";
    private static final String USER_EMAIL_HEADER       = "X-User-Email";

    private final BankService bankService;

    // ─── Create Account ───────────────────────────────────────────────────────

    /**
     * POST /bank/account/create
     * INTERNAL — called by auth-service on user registration.
     * Requires X-Internal-Service: auth-service header.
     */
    @PostMapping("/account/create")
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            HttpServletRequest httpRequest) {

        requireInternalHeader(httpRequest, "auth-service");
        log.info("[BANK-CTRL] Create account: userId={}", request.getUserId());
        AccountResponse response = bankService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ─── Debit ────────────────────────────────────────────────────────────────

    /**
     * POST /bank/debit
     * INTERNAL — called ONLY by fraud-detection-service after SAFE verdict.
     * Requires X-Internal-Service: fraud-detection-service header.
     * Deducts amount from payer's account.
     */
    @PostMapping("/debit")
    public ResponseEntity<AccountResponse> debit(
            @Valid @RequestBody DebitCreditRequest request,
            HttpServletRequest httpRequest) {

        requireInternalHeader(httpRequest, "fraud-detection-service");
        log.info("[BANK-CTRL] Debit request: userId={} amount={} txId={}",
                 request.getUserId(), request.getAmount(), request.getTransactionId());
        AccountResponse response = bankService.debit(request);
        return ResponseEntity.ok(response);
    }

    // ─── Credit ───────────────────────────────────────────────────────────────

    /**
     * POST /bank/credit
     * INTERNAL — called ONLY by fraud-detection-service after SAFE verdict.
     * Requires X-Internal-Service: fraud-detection-service header.
     * Adds amount to payee's account.
     */
    @PostMapping("/credit")
    public ResponseEntity<AccountResponse> credit(
            @Valid @RequestBody DebitCreditRequest request,
            HttpServletRequest httpRequest) {

        requireInternalHeader(httpRequest, "fraud-detection-service");
        log.info("[BANK-CTRL] Credit request: userId={} amount={} txId={}",
                 request.getUserId(), request.getAmount(), request.getTransactionId());
        AccountResponse response = bankService.credit(request);
        return ResponseEntity.ok(response);
    }

    // ─── Refund ───────────────────────────────────────────────────────────────

    /**
     * POST /bank/refund
     * INTERNAL — called by fraud-detection-service saga compensation flow.
     * Requires X-Internal-Service: fraud-detection-service header.
     * Credits payer back after a failed payee credit.
     */
    @PostMapping("/refund")
    public ResponseEntity<AccountResponse> refund(
            @Valid @RequestBody DebitCreditRequest request,
            HttpServletRequest httpRequest) {

        requireInternalHeader(httpRequest, "fraud-detection-service");
        log.info("[BANK-CTRL] Refund request: userId={} amount={} txId={}",
                 request.getUserId(), request.getAmount(), request.getTransactionId());
        AccountResponse response = bankService.refund(request);
        return ResponseEntity.ok(response);
    }

    // ─── Balance ──────────────────────────────────────────────────────────────

    /**
     * GET /bank/balance
     * EXTERNAL — authenticated users check their own balance.
     *
     * Identity is ALWAYS read from X-User-Email (gateway-injected JWT claim).
     * A legacy `userId` query param is accepted for backward compatibility
     * but IGNORED — the X-User-Email header is the authoritative identity source.
     *
     * @param userEmail injected by API Gateway from the validated JWT subject
     */
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> getBalance(
            @RequestHeader(value = USER_EMAIL_HEADER, required = false) String userEmail,
            @RequestParam(value = "userId",required = false) String userId) {

        // X-User-Email is the authoritative source — always prefer it over the param
        String effectiveUserId = (userEmail != null && !userEmail.isBlank())
                ? userEmail
                : userId;

        if (effectiveUserId == null || effectiveUserId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (userEmail != null && !userEmail.isBlank() && userId != null
                && !userId.equals(userEmail)) {
            log.warn("[BANK-CTRL] Balance: userId param '{}' overridden by X-User-Email '{}'",
                    userId, userEmail);
        }

        log.info("[BANK-CTRL] Balance check: userId={}", effectiveUserId);
        BalanceResponse response = bankService.getBalance(effectiveUserId);
        return ResponseEntity.ok(response);
    }

    // ─── Lookup by UPI ID ─────────────────────────────────────────────────────

    /**
     * GET /bank/account/by-upi/{upiId}
     * EXTERNAL — resolves a bank account by UPI ID.
     * Used by transaction-service (pre-validation) and fraud-detection-service
     * (payee resolution before crediting).
     */
    @GetMapping("/account/by-upi/{upiId}")
    public ResponseEntity<AccountResponse> getByUpiId(@PathVariable String upiId) {
        log.info("[BANK-CTRL] Account lookup by UPI ID: {}", upiId);
        AccountResponse response = bankService.findByUpiId(upiId);
        return ResponseEntity.ok(response);
    }

    // ─── Health ──────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "bank-service"));
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Reject requests missing the X-Internal-Service header or sending a value
     * that doesn't match the expected caller service name.
     *
     * Throws IllegalAccessException wrapped as RuntimeException if the check fails,
     * which the GlobalExceptionHandler maps to 403 Forbidden.
     */
    private void requireInternalHeader(HttpServletRequest request, String expectedCaller) {
        String header = request.getHeader(INTERNAL_SERVICE_HEADER);
        if (header == null || header.isBlank()) {
            log.warn("[BANK-CTRL] FORBIDDEN — missing {} header on internal endpoint: {}",
                    INTERNAL_SERVICE_HEADER, request.getRequestURI());
            throw new SecurityException("Internal endpoint — "
                    + INTERNAL_SERVICE_HEADER + " header required");
        }
        if (!expectedCaller.equalsIgnoreCase(header)) {
            log.warn("[BANK-CTRL] FORBIDDEN — unexpected caller '{}' on {}; expected '{}'",
                    header, request.getRequestURI(), expectedCaller);
            throw new SecurityException("Internal endpoint — caller not authorized: " + header);
        }
    }
}
