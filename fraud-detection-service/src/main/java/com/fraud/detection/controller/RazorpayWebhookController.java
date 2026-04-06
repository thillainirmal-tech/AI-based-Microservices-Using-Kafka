package com.fraud.detection.controller;

import com.fraud.detection.producer.NotificationProducer;
import com.fraud.detection.service.RedisService;
import com.fraud.common.dto.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private final RedisService redisService;
    private final NotificationProducer notificationProducer;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${razorpay.key-secret}")
    private String razorpaySecret;

    @PostMapping("/webhook")
    public String handleWebhook(@RequestBody String payload,
                                @RequestHeader(value = "X-Razorpay-Signature",required = false) String signature) {

        log.info("[RAZORPAY] Webhook received");

        if (signature == null || signature.isEmpty()) {
            log.warn("[RAZORPAY] Missing signature → ignoring request");
            return "IGNORED";
        }

        try {
            JSONObject json = new JSONObject(payload);

            String event = json.optString("event", "UNKNOWN");

            JSONObject entity = json
                    .getJSONObject("payload")
                    .getJSONObject("payment")
                    .getJSONObject("entity");

            String orderId = entity.optString("order_id", null);

            if (orderId == null) {
                log.error("[RAZORPAY] orderId missing in payload");
                return "ERROR";
            }

            String txId = redisService.getTxIdByOrderId(orderId);

            if (txId == null) {
                log.error("[RAZORPAY] txId not found for orderId={}", orderId);
                return "ERROR";
            }

            switch (event) {

                case "payment.captured" -> {
                    redisService.markPaymentSuccess(txId);
                    redisTemplate.delete("razorpay:order:" + orderId);
                    notificationProducer.send(
                            buildNotification(txId, "SUCCESS", " Payment successful")
                    );

                    log.info("[TX-TRACE] txId={} stage=RAZORPAY_SUCCESS", txId);
                }

                case "payment.failed" -> {
                    redisService.markPaymentFailed(txId);

                    notificationProducer.send(
                            buildNotification(txId, "FAILED", " Payment failed")
                    );

                    log.warn("[TX-TRACE] txId={} stage=RAZORPAY_FAILED", txId);
                }

                default -> log.info("[RAZORPAY] Unhandled event={}", event);
            }

        } catch (Exception e) {
            log.error("[RAZORPAY] Webhook error: {}", e.getMessage(), e);
            return "ERROR";
        }

        return "OK";
    }

    @PostMapping("/verify")
    public String verifyPayment(
            @RequestParam String razorpay_payment_id,
            @RequestParam String razorpay_order_id,
            @RequestParam String razorpay_signature,
            @RequestParam String transactionId) {

        log.info("[RAZORPAY] Verifying payment for txId={}", transactionId);

        try {
            String payload = razorpay_order_id + "|" + razorpay_payment_id;

            boolean isValid = verifySignature(payload, razorpay_signature);

            if (!isValid) {
                throw new RuntimeException("Invalid signature");
            }
            redisService.savePaymentId(transactionId, razorpay_payment_id);
            redisService.markPaymentSuccess(transactionId);

            log.info("[TX-TRACE] txId={} stage=PAYMENT_SUCCESS", transactionId);

            return "SUCCESS";

        } catch (Exception e) {

            redisService.markPaymentFailed(transactionId);

            log.error("[TX-TRACE] txId={} stage=PAYMENT_FAILED reason={}",
                    transactionId, e.getMessage());

            return "FAILED";
        }
    }

    private boolean verifySignature(String payload, String actualSignature) {

        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey =
                    new javax.crypto.spec.SecretKeySpec(razorpaySecret.getBytes(), "HmacSHA256");

            mac.init(secretKey);

            byte[] hash = mac.doFinal(payload.getBytes());

            String generatedSignature = new String(org.apache.commons.codec.binary.Hex.encodeHex(hash));

            return generatedSignature.equals(actualSignature);

        } catch (Exception e) {
            throw new RuntimeException("Signature verification failed", e);
        }
    }

    private NotificationEvent buildNotification(String txId, String status, String msg) {

        NotificationEvent n = new NotificationEvent();

        n.setTransactionId(txId);
        n.setStatus(status);
        n.setMessage(msg);


        var record = redisService.getPaymentRecord(txId);
        if (record != null) {
            n.setUserEmail(record.getPayerEmail());
            n.setPayeeEmail(record.getPayeeEmail());
            n.setPayeeUpiId(record.getPayeeUpiId());
            n.setAmount(record.getAmount());
        }

        return n;
    }
}