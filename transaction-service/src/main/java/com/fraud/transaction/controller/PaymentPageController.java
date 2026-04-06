package com.fraud.transaction.controller;

import com.fraud.transaction.client.FraudServiceClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@Controller
public class PaymentPageController {

    @Value("${razorpay.key-id}")
    private String razorpayKey;

    @Autowired
    private FraudServiceClient fraudServiceClient;

    @GetMapping("/start-payment")
    public String startPayment(
            @RequestParam String transactionId) {

        // redirect to payment page with orderId
        return "redirect:/pay?transactionId=" + transactionId;
    }

    @GetMapping("/pay")
    public String paymentPage(
            @RequestParam String transactionId,
            Model model) {

        // Call fraud-service via existing client
        Map<String, Object> paymentRecord =
                fraudServiceClient.getPaymentRecord(transactionId);

        if (paymentRecord == null ||
                paymentRecord.get("razorpayOrderId") == null) {

            model.addAttribute("message", "Preparing payment... Please wait ⏳");
            model.addAttribute("transactionId", transactionId);

            return "loading";
        }

        String orderId = paymentRecord.get("razorpayOrderId").toString();

        model.addAttribute("amount", 10);
        model.addAttribute("currency", "INR");
        model.addAttribute("merchantName", "Fraud System");

        model.addAttribute("orderId", orderId); //  dynamic now
        model.addAttribute("txnId", transactionId);

        model.addAttribute("rzpKey", razorpayKey);

        return "payment";
    }
}