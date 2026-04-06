package com.fraud.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * GatewayConfig — Cross-cutting concerns for the API Gateway
 *
 * Responsibilities:
 *  1. CORS configuration — allows the React frontend (port 3000) to call the gateway
 *  2. Global request/response logging filter

 */
@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    /**
     * CORS filter — permissive for development.
     * Tighten allowed origins in production.
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));           // Allow any origin in dev
        config.setAllowedMethods(Arrays.asList(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);                        // Set true if using cookies/auth headers

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);         // Apply to all paths
        return new CorsWebFilter(source);
    }

    /**
     * Global logging filter — logs every request that passes through the gateway.
     * Useful for distributed tracing and debugging.
     */
    @Bean
    public GlobalFilter globalLoggingFilter() {
        return (exchange, chain) -> {
            // Pre-filter: log incoming request
            log.info("[GATEWAY] Incoming request → Method: {} | Path: {}",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath());

            long startTime = System.currentTimeMillis();

            // Post-filter: log response status and elapsed time
            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("[GATEWAY] Response ← Status: {} | Elapsed: {}ms",
                        exchange.getResponse().getStatusCode(),
                        elapsed);
            }));
        };
    }
}
