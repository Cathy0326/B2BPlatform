package com.quipmarket.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A Clock tests can set and advance, e.g. to jump past an auction's end. */
public class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    public MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void set(Instant instant) { now.set(instant); }
    public void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }

    @Override public Instant instant() { return now.get(); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
