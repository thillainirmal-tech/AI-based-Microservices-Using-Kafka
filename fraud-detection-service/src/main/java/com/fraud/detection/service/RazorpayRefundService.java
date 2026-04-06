package com.fraud.detection.service;

import com.razorpay.RazorpayClient;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RazorpayRefundService {

    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    public void refund(String paymentId, int amount) {

        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);

            JSONObject req = new JSONObject();
            req.put("amount", amount * 100);

            client.payments.refund(paymentId, req);

        } catch (Exception e) {
            throw new RuntimeException("Refund failed", e);
        }
    }
}

