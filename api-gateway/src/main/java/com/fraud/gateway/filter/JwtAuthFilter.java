package com.fraud.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * JwtAuthFilter — Spring Cloud Gateway GlobalFilter (Polish v3)
 *
 * Runs at HIGHEST_PRECEDENCE on every inbound request. Applies:
 *
 *   1. Generate X-Trace-Id (UUID) if not already present.
 *      Forwarded to ALL downstream services for end-to-end trace correlation.
 *
 *   2. Allow public paths without JWT:
 *      path.startsWith("/auth/") || path.startsWith("/actuator/")
 *
 *   3. For all protected paths:
 *      a. Require "Authorization: Bearer <token>" header.
 *      b. Validate JWT signature (HS256) + expiry.
 *      c. Extract email from JWT subject.
 *      d. Inject "X-User-Email" header (downstream identity source of truth).
 *      e. Inject "X-Trace-Id" header (distributed trace).
 *      f. Reject with structured JSON 401 on any failure.
 *
 * ─── Identity Contract ────────────────────────────────────────────────────────
 *   All downstream services MUST read payer identity from X-User-Email ONLY.
 *   Request body userId fields are NEVER trusted for identity.
 *
 * ─── Trace Contract ──────────────────────────────────────────────────────────
 *   X-Trace-Id is always present for every request (generated here if absent).
 *   Downstream services set it in SLF4J MDC so logs are fully traceable.
 */
@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret}")
    private String jwtSecret;

    // ── Path Allow-list ───────────────────────────────────────────────────────

    private boolean isPublicPath(String path) {
        // /auth/** — login, register, health (no JWT required)
        // /actuator/** — Spring Boot management endpoints
        return path.startsWith("/auth/") || path.startsWith("/actuator/");
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path    = exchange.getRequest().getURI().getPath();
        String traceId = resolveTraceId(exchange);

        // 1. Always forward X-Trace-Id downstream
        ServerHttpRequest requestWithTrace = exchange.getRequest().mutate()
                .header("X-Trace-Id", traceId)
                .build();
        exchange = exchange.mutate().request(requestWithTrace).build();

        // 2. Public paths — skip JWT check
        if (isPublicPath(path)) {
            log.debug("[JWT-FILTER] Public path — traceId={} path={}", traceId, path);
            return chain.filter(exchange);
        }

        // 3. Extract Authorization header
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("[JWT-FILTER] UNAUTHORIZED — Missing Authorization header. traceId={} path={}",
                    traceId, path);
            return rejectUnauthorized(exchange, traceId,
                    "Authorization header missing or malformed. Expected: Bearer <token>", path);
        }

        String token = authHeader.substring(7);

        // 4. Validate JWT, extract email
        String email;
        try {
            email = extractEmailFromToken(token);
        } catch (JwtException e) {
            log.warn("[JWT-FILTER] UNAUTHORIZED — Invalid JWT. traceId={} path={} error={}",
                    traceId, path, e.getMessage());
            return rejectUnauthorized(exchange, traceId,
                    "Invalid or expired JWT token: " + e.getMessage(), path);
        }

        log.debug("[JWT-FILTER] ✓ Authenticated — email={} traceId={} path={}", email, traceId, path);

        // 5. Inject X-User-Email + X-Trace-Id into downstream request
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Email", email)
                .header("X-Trace-Id", traceId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Use existing X-Trace-Id if present (client or upstream already set it),
     * otherwise generate a new UUID v4. This ensures every request has a trace ID.
     */
    private String resolveTraceId(ServerWebExchange exchange) {
        String existing = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        return (existing != null && !existing.isBlank()) ? existing : UUID.randomUUID().toString();
    }

    private String extractEmailFromToken(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();

        if (claims.getExpiration().before(new Date())) {
            throw new JwtException("Token has expired");
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JwtException("JWT subject (email) is missing");
        }
        return subject;
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reject with HTTP 401 and a structured JSON error body.
     * Matches the standard error schema used across all services:
     * { traceId, timestamp, httpStatus, error, message, path }
     */
    private Mono<Void> rejectUnauthorized(ServerWebExchange exchange, String traceId,
                                           String message, String path) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"traceId\":\"%s\",\"timestamp\":\"%s\","
                + "\"httpStatus\":401,\"error\":\"Unauthorized\","
                + "\"message\":\"%s\",\"path\":\"%s\"}",
                traceId,
                LocalDateTime.now(),
                message.replace("\"", "'"),
                path);

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
