package com.fraud.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * JwtService — JWT token generation and validation.
 *
 * Uses HS256 with a shared secret (must match the api-gateway secret).
 * Email is stored as the JWT subject — used by the gateway to inject X-User-Email.
 *
 * Token expiry: 24 hours (configurable via jwt.expiration-ms).
 */
@Slf4j
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration-ms:86400000}")  // default: 24 hours
    private long jwtExpirationMs;

    /**
     * Generate a signed JWT token for the given email.
     *
     * @param email the authenticated user's email (becomes JWT subject)
     * @return signed JWT string
     */
    public String generateToken(String email) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + jwtExpirationMs);

        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Extract the email (subject) from a JWT token.
     *
     * @param token the raw JWT string (without "Bearer " prefix)
     * @return email stored in the token subject
     * @throws JwtException if the token is invalid or expired
     */
    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Validate a JWT token — checks signature and expiry.
     *
     * @param token the raw JWT string
     * @return true if valid, false if invalid or expired
     */
    public boolean isTokenValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return !claims.getExpiration().before(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Key getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
