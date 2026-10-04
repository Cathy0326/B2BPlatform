package com.quipmarket.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    final AtomicLong now = new AtomicLong();
    static final long SECOND = 1_000_000_000L;

    @Test
    void allowsABurstUpToCapacityThenRefillsOverTime() {
        var bucket = new TokenBucket(3, 1, now::get); // burst 3, 1 token per second
        assertThat(bucket.tryConsume(1)).isZero();
        assertThat(bucket.tryConsume(1)).isZero();
        assertThat(bucket.tryConsume(1)).isZero();
        assertThat(bucket.tryConsume(1)).isEqualTo(1); // empty: retry after ~1 s

        now.addAndGet(SECOND);
        assertThat(bucket.tryConsume(1)).isZero();
    }

    @Test
    void neverExceedsCapacityAfterLongIdle() {
        var bucket = new TokenBucket(2, 10, now::get);
        now.addAndGet(3600 * SECOND); // an hour idle does not bank 36,000 tokens
        assertThat(bucket.tryConsume(2)).isZero();
        assertThat(bucket.tryConsume(1)).isPositive();
    }

    @Test
    void expensiveRequestsWaitLonger() {
        var bucket = new TokenBucket(5, 1, now::get);
        bucket.tryConsume(5);
        assertThat(bucket.tryConsume(5)).isEqualTo(5);
    }
}
