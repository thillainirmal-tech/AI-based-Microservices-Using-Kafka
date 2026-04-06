package com.fraud.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ApiGatewayApplication — Spring Boot Entry Point
 *
 * This service acts as the single front door for all client requests.
 * It uses Spring Cloud Gateway (reactive/WebFlux) to route incoming
 * HTTP calls to the appropriate downstream microservice.
 *
 * Routing rules are declared in application.yml (no Java config needed
 * for simple URL-based routing).
 *
 * Port: 8080
 */
@SpringBootApplication
public class ApiGatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(ApiGatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
        log.info("========================================");
        log.info("  API Gateway started on port 8080     ");
        log.info("  Routes → transaction-service :8081   ");
        log.info("========================================");
    }
}
