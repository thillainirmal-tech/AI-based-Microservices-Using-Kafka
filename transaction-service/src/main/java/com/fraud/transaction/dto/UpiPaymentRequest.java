package com.fraud.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * UpiPaymentRequest — payload for POST /api/upi/pay
 *
 * SECURITY NOTE: payerUserId is NOT accepted from the request body.
 * It is injected by the API Gateway from the validated JWT token
 * via the X-User-Email header. This prevents identity spoofing.
 *
 * paymentMode defaults to "BANK" (simulated bank-service).
 * Set to "RAZORPAY" to route through real Razorpay gateway (SAFE verdict only).
 */
@Data
public class UpiPaymentRequest {

    /**
     * UPI ID of the payee (e.g. "merchant@ybl", "alice@upi").
     * The bank-service will resolve this to a userId for crediting.
     */
    @NotBlank(message = "payeeUpiId is required")
    private String payeeUpiId;

    /**
     * Payment amount in primary currency unit (INR).
     * Must be > 0.
     */
    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0.01")
    private BigDecimal amount;

    /** Device identifier (e.g. "iPhone-14", "Android-Pixel-7") */
    private String device;

    /** Physical or IP-based location (e.g. "Mumbai", "192.168.1.1") */
    private String location;

    /** Merchant or payment category (e.g. "GROCERY", "FUEL", "ONLINE") — used by AI fraud analysis */
    private String merchantCategory;

    /**
     * Payment mode — "BANK" (simulated) or "RAZORPAY" (real).
     * Defaults to "BANK" if not specified.
     */
    private String paymentMode = "BANK";
}
