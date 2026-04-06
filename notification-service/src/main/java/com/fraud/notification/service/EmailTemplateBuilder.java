package com.fraud.notification.service;

import org.springframework.stereotype.Component;

@Component
public class EmailTemplateBuilder {

    public String build(String status, String amount, String payee) {

        String color = switch (status) {
            case "SUCCESS" -> "#2ecc71";
            case "FAILED" -> "#e74c3c";
            case "FRAUD" -> "#f39c12";
            default -> "#3498db";
        };

        return """
        <html>
        <body style="font-family: Arial; background:#f4f6f8; padding:20px;">
            <div style="max-width:400px; margin:auto; background:white; padding:20px; border-radius:10px; text-align:center;">
                
                <h2 style="color:%s;">%s</h2>

                <p style="font-size:18px;"><b>Amount:</b> ₹%s</p>
                <p><b>To:</b> %s</p>

                <hr/>

                <p style="font-size:12px; color:gray;">
                    This is an automated message from Fraud Detection System
                </p>
            </div>
        </body>
        </html>
        """.formatted(color, status, amount, payee);
    }
}