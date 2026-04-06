package com.fraud.auth.controller;

import com.fraud.auth.dto.AuthResponse;
import com.fraud.auth.dto.LoginRequest;
import com.fraud.auth.dto.RegisterRequest;
import com.fraud.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AuthController — REST endpoints for user registration and login.
 *
 * All endpoints under /auth/** are permit-listed in SecurityConfig
 * and do NOT require a JWT token (they issue one instead).
 *
 * Endpoints:
 *   POST /auth/register — register a new user, create bank account, return JWT
 *   POST /auth/login    — authenticate user, return JWT
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ─── Register ────────────────────────────────────────────────────────────

    /**
     * POST /auth/register
     *
     * Request body:
     *   { "name": "John Smith", "email": "john@example.com", "password": "secret123" }
     *
     * Response (201 Created):
     *   { "token": "eyJ...", "email": "john@example.com", "name": "John Smith",
     *     "upiId": "johnsmith@upi", "message": "Registration successful" }
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        log.info("[AUTH-CTRL] Register request for email={}", request.getEmail());
        try {
            AuthResponse response = authService.register(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH-CTRL] Register failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    /**
     * POST /auth/login
     *
     * Request body:
     *   { "email": "john@example.com", "password": "secret123" }
     *
     * Response (200 OK):
     *   { "token": "eyJ...", "email": "john@example.com", "name": "John Smith",
     *     "upiId": "johnsmith@upi", "message": "Login successful" }
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        log.info("[AUTH-CTRL] Login request for email={}", request.getEmail());
        try {
            AuthResponse response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("[AUTH-CTRL] Login failed for email={}", request.getEmail());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid email or password"));
        }
    }

    // ─── Health ──────────────────────────────────────────────────────────────

    /**
     * GET /auth/health — quick liveness check (supplement to actuator)
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "auth-service"));
    }
}
