package com.fraud.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig — Spring Security configuration for auth-service.
 *
 * auth-service itself does NOT validate JWTs — it ISSUES them.
 * All /auth/** endpoints are public (no token required).
 * Actuator endpoints are also open for monitoring.
 *
 * CSRF is disabled because this is a stateless REST API (no cookies/sessions).
 * Session creation is STATELESS — no HttpSession used.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * BCrypt password encoder — cost factor 12.
     * Used by AuthService to hash passwords on registration
     * and verify them on login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Security filter chain — permits all /auth/** and /actuator/** requests.
     * All other endpoints are denied (auth-service has no other public API).
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().denyAll()
            );

        return http.build();
    }
}
