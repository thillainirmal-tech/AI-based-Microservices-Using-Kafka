package com.fraud.detection.payment;

import com.fraud.common.dto.TransactionEvent;
import com.razorpay.RazorpayClient;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RazorpayService — server-side Razorpay Order creation.
 *
 * Called ONLY when:
 *   1. Fraud verdict = SAFE
 *   2. paymentMode = "RAZORPAY"
 *
 * This creates a Razorpay Order on the server. The client then completes
 * the payment using Razorpay's frontend SDK with the returned orderId.
 *
 * ─── Disabled Behaviour ──────────────────────────────────────────────────
 *
 * When {@code razorpay.enabled=false} (default), {@code createOrder()} returns
 * {@code null}. PaymentProcessorService treats a null orderId as a Razorpay
 * failure and falls back to BANK mode automatically.
 *
 * This means paymentMode=RAZORPAY in a non-Razorpay environment silently
 * processes via bank-service — safe, correct, and requires no client change.
 *
 * DO NOT return a simulated/fake order ID when disabled — that would persist
 * a misleading RAZORPAY_ORDER_CREATED status in Redis.
 *
 * ─── Production Enablement ───────────────────────────────────────────────
 *
 * Set all three env vars:
 *   RAZORPAY_ENABLED=true
 *   RAZORPAY_KEY_ID=rzp_live_xxxxx
 *   RAZORPAY_KEY_SECRET=your_secret
 *
 * Also add the Razorpay SDK to fraud-detection-service pom.xml:
 *   <dependency>
 *     <groupId>com.razorpay</groupId>
 *     <artifactId>razorpay-java</artifactId>
 *     <version>1.4.3</version>
 *   </dependency>
 */
@Slf4j
@Service
public class RazorpayService {

    @Value("${razorpay.key-id:}")
    private String razorpayKeyId;

    @Value("${razorpay.key-secret:}")
    private String razorpayKeySecret;

    @Value("${razorpay.enabled:false}")
    private boolean razorpayEnabled;

    private static final String CURRENCY = "INR";

    /**
     * Create a Razorpay order for the given SAFE-verified transaction.
     *
     * @param event SAFE-verified TransactionEvent from PaymentProcessorService
     * @return Razorpay order ID (e.g. "order_xxxxx") on success,
     *         {@code null} if disabled, misconfigured, or SDK call failed.
     *         A null return causes PaymentProcessorService to fall back to BANK mode.
     */
    public String createOrder(TransactionEvent event) {
        // ── Disabled: return null → PaymentProcessorService falls back to BANK ──
        if (!razorpayEnabled) {
            log.info("[RAZORPAY] Disabled (razorpay.enabled=false) — "
                    + "returning null to trigger BANK fallback. txId={}",
                    event.getTransactionId());
            return null;
        }

        // ── Misconfigured: keys absent ────────────────────────────────────────
        if (razorpayKeyId.isBlank() || razorpayKeySecret.isBlank()) {
            log.error("[RAZORPAY] API keys not configured. "
                    + "Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET env vars. txId={}",
                    event.getTransactionId());
            return null;
        }

        try {
            // Dynamic class loading — avoids compile-time dependency when SDK is absent.
            // If SDK is absent, ClassNotFoundException is caught and null is returned.
            Class<?> razorpayClientClass = Class.forName("com.razorpay.RazorpayClient");
            Object razorpayClient = razorpayClientClass
                    .getConstructor(String.class, String.class)
                    .newInstance(razorpayKeyId, razorpayKeySecret);

            // Amount in paise (1 INR = 100 paise)
            int amountInPaise = event.getAmount()
                    .multiply(java.math.BigDecimal.valueOf(100)).intValue();

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount",   amountInPaise);
            orderRequest.put("currency", CURRENCY);
            orderRequest.put("receipt",  event.getTransactionId());

            Object orders = razorpayClientClass
                    .getField("orders")   // FIELD not method
                    .get(razorpayClient);
            Object order  = orders.getClass()
                    .getMethod("create", JSONObject.class)
                    .invoke(orders, orderRequest);

            String orderId = order.getClass()
                    .getMethod("get", String.class)
                    .invoke(order, "id")
                    .toString();

            log.info("[RAZORPAY] ✓ Order created: orderId={} txId={} amount={} INR",
                    orderId, event.getTransactionId(), event.getAmount());
            return orderId;

        } catch (ClassNotFoundException e) {
            log.error("[RAZORPAY] SDK not on classpath — add com.razorpay:razorpay-java:1.4.3 "
                    + "to fraud-detection-service pom.xml. txId={}", event.getTransactionId());
            return null;
        } catch (Exception e) {
            log.error("[RAZORPAY] ✗ Order creation failed for txId={}: {}",
                    event.getTransactionId(), e.getMessage(), e);
            return null;
        }
    }
    public void refund(String paymentId) {
        try {
            RazorpayClient client = new RazorpayClient(
                    razorpayKeyId,
                    razorpayKeySecret
            );

            JSONObject request = new JSONObject();
            request.put("amount", 100); // optional

            client.payments.refund(paymentId, request);

            log.info("[RAZORPAY] Refund triggered for paymentId={}", paymentId);

        } catch (Exception e) {
            log.error("[RAZORPAY] Refund failed {}", e.getMessage());
        }
    }
}
