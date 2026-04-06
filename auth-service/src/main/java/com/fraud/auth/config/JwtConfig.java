package com.fraud.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * JwtConfig — startup validation for JWT configuration.
 *
 * Fails fast at startup if:
 *   - jwt.secret is blank (JWT_SECRET env var not set)
 *   - jwt.secret is shorter than 32 characters (HS256 minimum for security)
 *
 * This prevents the service from running in a misconfigured state where
 * all tokens would share a weak or empty key, allowing token forgery.
 *
 * Production checklist:
 *   ✓ Set JWT_SECRET env var to at least 32 random characters
 *   ✓ Same value must be set in api-gateway JWT_SECRET
 *   ✓ Rotate every 90 days; issue new tokens before rotation
 *   ✓ Never commit the value to source control
 */
@Slf4j
@Configuration
public class JwtConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    private static final int MIN_SECRET_LENGTH = 32;

    @PostConstruct
    public void validateJwtConfig() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "[JWT] STARTUP FAILED: jwt.secret is blank. "
                + "Set the JWT_SECRET environment variable to a random string "
                + "of at least " + MIN_SECRET_LENGTH + " characters.");
        }
        if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                String.format("[JWT] STARTUP FAILED: jwt.secret is too short (%d chars). "
                    + "Minimum required for HS256: %d characters. "
                    + "Update the JWT_SECRET environment variable.",
                    jwtSecret.length(), MIN_SECRET_LENGTH));
        }
        log.info("[JWT] Configuration valid — secret length={}  expiry={}ms ({}h)",
                jwtSecret.length(), jwtExpirationMs, jwtExpirationMs / 3_600_000);
    }
}
