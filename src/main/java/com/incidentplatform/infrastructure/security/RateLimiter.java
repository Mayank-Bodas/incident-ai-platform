package com.incidentplatform.infrastructure.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * RateLimiter — Implements a Redis-based Token Bucket rate limiting algorithm.
 *
 * WHY Lua Script?
 * Redis commands are single-threaded but multiple commands are not atomic by default.
 * If we execute GET -> calculate -> SET from Java: race conditions occur in high concurrency.
 * A Lua script executes ATOMICALLY inside the Redis server. No other commands can run
 * until it finishes.
 *
 * WHY Token Bucket?
 * Token Bucket allows:
 * - Bursts up to bucket capacity (e.g. 10 requests at once).
 * - Refills smoothly at a constant rate (e.g. 1 token per second).
 * Perfect for alert ingestion where occasional spikes are expected, but sustained spam must be limited.
 *
 * Interview: "How do you implement rate limiting in a distributed system?"
 * → Redis Lua script running a Token Bucket algorithm, tracking limits per IP or client identifier.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.rate-limit.capacity}")
    private double capacity;

    @Value("${app.rate-limit.refill-rate}")
    private double refillRate; // tokens per second

    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "local refill_rate_per_ms = tonumber(ARGV[2])\n" +
            "local cost = tonumber(ARGV[3])\n" +
            "local now = tonumber(ARGV[4])\n" +
            "\n" +
            "local state = redis.call('HMGET', key, 'tokens', 'last_updated')\n" +
            "local tokens = tonumber(state[1])\n" +
            "local last_updated = tonumber(state[2])\n" +
            "\n" +
            "if not tokens then\n" +
            "    tokens = capacity\n" +
            "    last_updated = now\n" +
            "else\n" +
            "    local elapsed = now - last_updated\n" +
            "    if elapsed > 0 then\n" +
            "        local refill = elapsed * refill_rate_per_ms\n" +
            "        tokens = math.min(capacity, tokens + refill)\n" +
            "        last_updated = now\n" +
            "    end\n" +
            "end\n" +
            "\n" +
            "if tokens >= cost then\n" +
            "    tokens = tokens - cost\n" +
            "    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)\n" +
            "    redis.call('EXPIRE', key, 3600) -- Expire key after 1 hour of inactivity\n" +
            "    return 1\n" +
            "else\n" +
            "    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', last_updated)\n" +
            "    return 0\n" +
            "end";

    private final DefaultRedisScript<Long> rateLimitScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    /**
     * Checks if a request is allowed under the rate limit.
     *
     * @param key unique identifier (e.g. client IP)
     * @return true if request is allowed, false if rate limited
     */
    public boolean isAllowed(String key) {
        try {
            double refillRatePerMs = refillRate / 1000.0;
            long nowMs = Instant.now().toEpochMilli();
            String limitKey = "ratelimit:" + key;

            Long result = redisTemplate.execute(
                    rateLimitScript,
                    List.of(limitKey),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerMs),
                    "1", // cost of 1 token per request
                    String.valueOf(nowMs)
            );

            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("Failed to execute rate limiter script for key: {}", key, e);
            // FALLBACK: Fail-open to avoid losing critical system alert notifications.
            // Interview: "What happens if your rate limiting DB fails?"
            // → We fail-open because uptime/monitoring is more critical than rate limiting.
            return true;
        }
    }
}
