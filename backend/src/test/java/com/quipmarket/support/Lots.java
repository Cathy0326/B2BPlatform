package com.quipmarket.support;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState;
import java.time.Duration;
import java.time.Instant;

/** Test helper: create auction lots relative to the test clock. */
public final class Lots {
    private Lots() {}

    public static String live(AuctionService auctions, MutableClock clock, String prefix, String equipmentId,
                              long startCents, Long reserveCents, long depositCents, Duration remaining) {
        Instant now = clock.instant();
        String id = prefix + "-" + System.nanoTime();
        auctions.create(new AuctionState(id, equipmentId, startCents, reserveCents, depositCents, now.minusSeconds(60),
                now.plus(remaining), 120, startCents, null, null, 0));
        return id;
    }
}
