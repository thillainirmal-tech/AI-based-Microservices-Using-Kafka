package com.fraud.auth.service;

import com.fraud.auth.client.BankServiceClient;
import com.fraud.auth.dto.AuthResponse;
import com.fraud.auth.dto.LoginRequest;
import com.fraud.auth.dto.RegisterRequest;
import com.fraud.auth.entity.User;
import com.fraud.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthService — core authentication business logic (Polish v3)
 *
 * Registration flow:
 *   1. Validate email uniqueness (pre-check + DB unique constraint safety net)
 *   2. Normalize email: lowercase + trim
 *   3. Generate unique UPI ID from name
 *   4. BCrypt-hash password
 *   5. Persist User entity (@Transactional — DB work only)
 *   6. Call bank-service to create a default bank account
 *      ↳ OUTSIDE @Transactional — HTTP call must not hold a DB connection open
 *   7. Return JWT token + user info
 *
 * Login flow:
 *   1. Normalize email
 *   2. Look up user by email
 *   3. BCrypt-verify password
 *   4. Return JWT token + user info
 *
 * ─── Identity Safety ────────────────────────────────────────────────────────
 *   Email is always normalized (lowercase + trim) before persistence or lookup.
 *   This prevents duplicate registrations that differ only in case.
 *
 * ─── @Transactional Boundary ────────────────────────────────────────────────
 *   registerUser() is called via self-injection (@Lazy) so that Spring's AOP
 *   proxy wraps the method correctly. This keeps the DB transaction strictly
 *   scoped to DB work; the bankServiceClient HTTP call runs AFTER commit.
 *
 *   registerUserInternal() is the @Transactional method — does DB work only.
 *   register() is the public entry point — calls registerUserInternal(), then
 *   bankServiceClient.createBankAccount() after the transaction commits.
 */
@Slf4j
@Service
public class AuthService {

    private final UserRepository    userRepository;
    private final PasswordEncoder   passwordEncoder;
    private final JwtService        jwtService;
    private final BankServiceClient bankServiceClient;

    // Self-injection via @Lazy allows calling @Transactional methods on this
    // bean through the Spring proxy, so the transaction actually applies.
    @Lazy
    @Autowired
    private AuthService self;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       BankServiceClient bankServiceClient) {
        this.userRepository    = userRepository;
        this.passwordEncoder   = passwordEncoder;
        this.jwtService        = jwtService;
        this.bankServiceClient = bankServiceClient;
    }

    // ─── Register ────────────────────────────────────────────────────────────

    /**
     * Public entry point for user registration.
     *
     * Calls the @Transactional inner method via self-proxy, then performs the
     * bank-service HTTP call AFTER the transaction has committed successfully.
     * This prevents holding a DB connection open during a potentially slow HTTP call.
     */
    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        // DB work — committed before bank-service call
        RegistrationResult result = self.registerUserInternal(email,
                request.getName().trim(),
                request.getPassword());

        // Bank-service call runs AFTER DB transaction committed — non-fatal
        bankServiceClient.createBankAccount(email, result.upiId());

        // Issue JWT
        String token = jwtService.generateToken(email);

        return AuthResponse.builder()
                .token(token)
                .email(email)
                .name(result.name())
                .upiId(result.upiId())
                .message("Registration successful")
                .build();
    }

    /**
     * @Transactional method — performs ONLY DB operations.
     * Called via self-proxy so the transaction boundary is correctly applied.
     *
     * Throws IllegalArgumentException if the email is already registered.
     * DataIntegrityViolationException from DB unique constraint is caught and
     * re-thrown as IllegalArgumentException for consistent error handling.
     */
    @Transactional
    public RegistrationResult registerUserInternal(String email, String name, String rawPassword) {
        // Pre-check (fast path — avoids unique constraint exception in the happy case)
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email is already registered: " + email);
        }

        // Generate unique UPI ID
        String upiId = generateUniqueUpiId(name);

        // Build and persist user
        User user = User.builder()
                .name(name)
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                .upiId(upiId)
                .build();

        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Race condition: another request registered the same email between the
            // existsByEmail check and the save. Treat as duplicate.
            log.warn("[AUTH] Duplicate email on save (race condition): email={}", email);
            throw new IllegalArgumentException("Email is already registered: " + email);
        }

        log.info("[AUTH] User persisted: email={} upiId={}", email, upiId);
        return new RegistrationResult(user.getName(), upiId);
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail().toLowerCase().trim();

        // 1. Look up user — generic error message prevents user enumeration
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        // 2. Verify password (constant-time BCrypt compare)
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        log.info("[AUTH] Login successful: email={}", email);

        // 3. Issue JWT
        String token = jwtService.generateToken(email);

        return AuthResponse.builder()
                .token(token)
                .email(email)
                .name(user.getName())
                .upiId(user.getUpiId())
                .message("Login successful")
                .build();
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Generate a unique UPI ID derived from the user's name.
     *
     * Algorithm:
     *   1. Strip non-alphanumeric characters from name, lowercase
     *   2. Append "@upi" → "johnsmith@upi"
     *   3. If taken, append a numeric suffix → "johnsmith1@upi", "johnsmith2@upi"
     *
     * @param name raw user-provided name (already trimmed)
     * @return unique UPI ID
     */
    private String generateUniqueUpiId(String name) {
        String base = name.toLowerCase()
                          .replaceAll("[^a-z0-9]", "")
                          .trim();

        if (base.isEmpty()) {
            base = "user";
        }

        String candidate = base + "@upi";
        int suffix = 1;

        while (userRepository.existsByUpiId(candidate)) {
            candidate = base + suffix + "@upi";
            suffix++;
        }

        return candidate;
    }

    // ─── Internal DTO ────────────────────────────────────────────────────────

    /**
     * Internal value carrier for data returned from the @Transactional method.
     * Avoids passing the full User entity across the transaction boundary.
     */
    public record RegistrationResult(String name, String upiId) {}
}
