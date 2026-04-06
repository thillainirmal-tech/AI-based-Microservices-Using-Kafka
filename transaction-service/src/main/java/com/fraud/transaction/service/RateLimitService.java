package com.fraud.transaction.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * RateLimitService — per-user payment rate limiting via Redis.
 *
 * Uses the Redis INCR + EXPIRE pattern:
 *   1. INCR ratelimit:{userId}  — atomically increment counter
 *   2. If counter == 1 → first request in this window → EXPIRE after windowSeconds
 *   3. If counter > maxRequests → rate limit exceeded → return false
 *
 * This pattern is atomic and does not require a Lua script for basic cases.
 * The window resets naturally when the Redis key expires.
 *
 * Configuration (application.yml):
 *   rate-limit.max-requests: 5       (default: 5 requests per window)
 *   rate-limit.window-seconds: 60    (default: 60-second sliding window)
 *
 * The Redis key "ratelimit:{userId}" is a short-lived counter scoped to one user.
 * It expires automatically after windowSeconds and requires no explicit cleanup.
 */
@Slf4j
@Service
public class RateLimitService {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final int maxRequests;
    private final long windowSeconds;

    public RateLimitService(
            StringRedisTemplate redisTemplate,
            @Value("${rate-limit.max-requests:5}") int maxRequests,
            @Value("${rate-limit.window-seconds:60}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxRequests   = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Check whether the given user is allowed to initiate another payment.
     *
     * @param userId email of the payer (from JWT via X-User-Email header)
     * @return true if the request is within the rate limit, false if it exceeds it
     */
    public boolean isAllowed(String userId) {
        String key = KEY_PREFIX + userId;

        Long count = redisTemplate.opsForValue().increment(key);

        if (count == null) {
            // Redis error — fail open to avoid blocking all payments
            log.warn("[RATE-LIMIT] Redis returned null for key={} — failing open", key);
            return true;
        }

        if (count == 1L) {
            // First request in this window — set the expiry
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        boolean allowed = count <= maxRequests;

        if (!allowed) {
            log.warn("[RATE-LIMIT] EXCEEDED — userId={} count={} max={} windowSeconds={}",
                    userId, count, maxRequests, windowSeconds);
        } else {
            log.debug("[RATE-LIMIT] OK — userId={} count={}/{} windowSeconds={}",
                    userId, count, maxRequests, windowSeconds);
        }

        return allowed;
    }
}
