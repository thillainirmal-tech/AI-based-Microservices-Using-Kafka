package com.fraud.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * TransactionServiceApplication — Spring Boot Entry Point
 *
 * Responsibilities:
 *  - Expose POST /api/transactions endpoint
 *  - Validate incoming transaction request
 *  - Publish transaction event to Kafka topic "transactions"
 *
 * Port: 8081
 * Kafka topic produced: "transactions"
 */
@SpringBootApplication
public class TransactionServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
        log.info("==============================================");
        log.info("  Transaction Service started on port 8081  ");
        log.info("  Kafka Producer → topic: transactions      ");
        log.info("==============================================");
    }
}
