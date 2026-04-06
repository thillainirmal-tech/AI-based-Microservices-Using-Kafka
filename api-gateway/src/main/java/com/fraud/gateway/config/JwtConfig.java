package com.fraud.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * JwtConfig — startup validation for API Gateway JWT configuration.
 *
 * Mirrors the validation in auth-service JwtConfig.
 * The gateway is the ONLY service that validates incoming JWTs from clients.
 * If the secret is wrong here, all authenticated requests will be rejected.
 *
 * Fails fast at startup if jwt.secret is absent or too short.
 */
@Slf4j
@Configuration
public class JwtConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final int MIN_SECRET_LENGTH = 32;

    @PostConstruct
    public void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "[JWT-GATEWAY] STARTUP FAILED: jwt.secret is blank. "
                + "Set JWT_SECRET environment variable (must match auth-service).");
        }
        if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                String.format("[JWT-GATEWAY] STARTUP FAILED: jwt.secret is only %d chars. "
                    + "Minimum: %d. Update JWT_SECRET.", jwtSecret.length(), MIN_SECRET_LENGTH));
        }
        log.info("[JWT-GATEWAY] JWT secret validated — length={}", jwtSecret.length());
    }
}
