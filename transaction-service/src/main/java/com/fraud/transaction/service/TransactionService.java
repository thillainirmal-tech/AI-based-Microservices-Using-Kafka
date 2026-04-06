package com.fraud.transaction.service;

import com.fraud.common.dto.TransactionEvent;
import com.fraud.transaction.dto.TransactionRequest;
import com.fraud.transaction.dto.TransactionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * TransactionService — Business Logic Layer
 *
 * Sits between TransactionController and KafkaProducerService.
 * Responsibilities:
 *  - Map the REST request DTO → Kafka event DTO
 *  - Enrich event with server-side metadata (timestamp)
 *  - Delegate to Kafka producer
 *  - Return a meaningful response to the controller
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /**
     * Kafka producer service — injected via @Autowired per project convention.
     */
    @Autowired
    private KafkaProducerService kafkaProducerService;

    /**
     * Processes an incoming transaction request:
     *   1. Converts TransactionRequest → TransactionEvent
     *   2. Sets server-side timestamp
     *   3. Publishes event to Kafka
     *   4. Returns an acceptance response (fraud check is async)
     *
     * @param request  validated REST request from the controller
     * @return         TransactionResponse confirming the transaction was queued
     */
    public TransactionResponse processTransaction(TransactionRequest request) {
        log.info("[TRANSACTION SERVICE] Processing transaction ID: {} for User: {}",
                request.getTransactionId(), request.getUserId());

        // ─── Build Kafka Event ───────────────────────────────────────
        TransactionEvent event = TransactionEvent.builder()
                .transactionId(request.getTransactionId())
                .userId(request.getUserId())
                .amount(request.getAmount())
                .location(request.getLocation())
                .device(request.getDevice())
                .merchantCategory(request.getMerchantCategory())
                .timestamp(LocalDateTime.now())   // Server-assigned timestamp
                .build();

        // ─── Publish to Kafka ────────────────────────────────────────
        kafkaProducerService.publishTransaction(event);

        log.info("[TRANSACTION SERVICE] Transaction {} queued for fraud analysis",
                request.getTransactionId());

        // ─── Return Accepted Response ────────────────────────────────
        // HTTP 202 Accepted — the request was received but processing is async
        return TransactionResponse.accepted(request.getTransactionId());
    }
}
