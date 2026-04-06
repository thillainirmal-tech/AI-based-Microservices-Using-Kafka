package com.fraud.transaction.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplateConfig — RestTemplate bean for transaction-service inter-service calls.
 *
 * Used by FraudServiceClient to call fraud-detection-service.
 *
 * Timeouts:
 *   - Connect: 3 seconds  (fail fast if fraud-service is unreachable)
 *   - Read:    5 seconds  (fraud result lookups are fast Redis reads)
 */
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(3000); // 3 seconds
        factory.setReadTimeout(5000);    // 5 seconds

        return new RestTemplate(factory);
    }
}
