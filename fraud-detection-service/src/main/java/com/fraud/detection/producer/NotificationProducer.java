package com.fraud.detection.producer;

import com.fraud.common.dto.NotificationEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class NotificationProducer {

    // FIX: Changed from KafkaTemplate<String, NotificationEvent> to KafkaTemplate<String, Object>.
    //
    // Root cause of the original error:
    //   "Can't convert value of class com.fraud.common.dto.NotificationEvent
    //    to class org.apache.kafka.common.serialization.StringSerializer"
    //
    //   Spring Boot's auto-configured KafkaTemplate used StringSerializer because:
    //     - application-local.yml had no spring.kafka.producer block
    //     - Only application-docker.yml set value-serializer=JsonSerializer
    //   In local mode the auto-configured template received a NotificationEvent it
    //   could not serialize → SerializationException at runtime.
    //
    // Fix approach:
    //   KafkaNotificationProducerConfig.java now provides an explicit ProducerFactory
    //   with JsonSerializer that is profile-independent (works in local AND docker).
    //   The bean is named "notificationKafkaTemplate" and typed as KafkaTemplate<String, Object>
    //   (same type as the DLQ template in KafkaConsumerConfig) to avoid bean type conflicts.
    //   @Qualifier ensures Spring injects exactly this bean here.
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // Resolved from kafka.topic.notifications in application.yml so the topic name
    // matches KAFKA_TOPIC_NOTIFICATIONS from .env — prevents hardcoded "notifications" drift.
    @Value("${kafka.topic.notifications:notifications}")
    private String notificationTopic;

    public NotificationProducer(@Qualifier("notificationKafkaTemplate")
                                KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void send(NotificationEvent event) {
        kafkaTemplate.send(notificationTopic, event);
    }
}
