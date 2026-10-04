package com.quipmarket.shared;

import java.util.function.LongSupplier;

/**
 * Classic token bucket (the algorithm behind most API rate limits, including Stripe's):
 *   - holds at most `capacity` tokens (= the allowed burst)
 *   - refills continuously at `refillPerSecond`
 *   - a request costing c tokens passes only if c tokens are available
 * Lazy refill: instead of a timer, tokens are topped up from the elapsed time on each call. O(1).
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;
    private volatile long lastUsed;

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefill = nanoClock.getAsLong();
        this.lastUsed = lastRefill;
    }

    /** @return 0 if allowed, otherwise the number of seconds until enough tokens will exist. */
    public synchronized long tryConsume(double cost) {
        long now = nanoClock.getAsLong();
        tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
        lastRefill = now;
        lastUsed = now;
        if (tokens >= cost) {
            tokens -= cost;
            return 0;
        }
        double missing = cost - tokens;
        return Math.max(1, (long) Math.ceil(missing / refillPerNano / 1_000_000_000d));
    }

    long lastUsedNanos() {
        return lastUsed;
    }
}
