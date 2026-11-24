package dev.cdb.driver.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Token-bucket rate limiter per principal. */
public class RateLimiter {
    private final double ratePerSecond;
    private final double burst;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(double ratePerSecond, double burst) {
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
    }

    public boolean tryAcquire(String key) {
        if (ratePerSecond <= 0) return true;
        return buckets.computeIfAbsent(key, k -> new Bucket(burst)).tryAcquire(ratePerSecond, burst);
    }

    private static final class Bucket {
        private double tokens;
        private long last = System.nanoTime();

        Bucket(double tokens) {
            this.tokens = tokens;
        }

        synchronized boolean tryAcquire(double rate, double burst) {
            long now = System.nanoTime();
            tokens = Math.min(burst, tokens + (now - last) / 1e9 * rate);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
