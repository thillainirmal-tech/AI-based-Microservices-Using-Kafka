package com.fraud.common.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class NotificationEvent {

    private String transactionId;

    private String userEmail;     // payer
    private String payeeEmail;    // receiver (NEW)
    private String payeeUpiId;    // receiver UPI (NEW)

    private BigDecimal amount;    //  FIXED (was Double)

    private String status;        // SUCCESS / FAILED / FRAUD
    private String message;
}