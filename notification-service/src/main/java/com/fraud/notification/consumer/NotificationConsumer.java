package com.fraud.notification.consumer;

import com.fraud.common.dto.NotificationEvent;
import com.fraud.notification.service.EmailService;
import com.fraud.notification.service.EmailTemplateBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final EmailService emailService;
    private final EmailTemplateBuilder templateBuilder;

    @KafkaListener(
            topics = "${kafka.topic.notifications:notifications}",
            groupId = "${kafka.consumer.group-id:notification-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(NotificationEvent event) {

        // TraceId (for log correlation)
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = "NO_TRACE-" + event.getTransactionId();
        }

        log.info("[NOTIFICATION] traceId={} Received → {}", traceId, event);

        try {
            // Null-safe handling
            String status = event.getStatus() != null ? event.getStatus() : "INFO";
            String amount = event.getAmount() != null ? String.valueOf(event.getAmount()) : "0";
            String message = event.getMessage() != null ? event.getMessage() : "Transaction update";
            String email = event.getUserEmail();

            if (email == null || email.isBlank()) {
                log.warn("[NOTIFICATION] traceId={} Missing email, skipping notification. txId={}",
                        traceId, event.getTransactionId());
                return;
            }

            // Build HTML email
            String html = templateBuilder.build(status, amount, message);

            // Send email
            emailService.sendHtml(
                    email,
                    " UPI Transaction Notification",
                    html
            );

            log.info("[NOTIFICATION] traceId={} Email sent successfully to {}", traceId, email);

        } catch (Exception e) {
            log.error("[NOTIFICATION] traceId={} Email sending failed for txId={} error={}",
                    traceId,
                    event.getTransactionId(),
                    e.getMessage()
            );
        }
    }
}