package com.quipmarket.auction;

import java.time.Instant;

/**
 * The authoritative state of one auction lot (one row in `auctions`).
 * Immutable: the engine returns a new instance instead of mutating, which makes it
 * trivial to test and mirrors "write the new row version inside one transaction".
 */
public record AuctionState(
        String id,
        String equipmentId,
        long startingPriceCents,
        Long reservePriceCents,
        long depositCents,
        Instant startsAt,
        Instant endsAt,
        int softCloseSeconds,
        long currentPriceCents,
        String leaderId,
        Long leaderMaxCents,          // SECRET proxy max of the leader
        int extensions) {

    public enum Status { UPCOMING, LIVE, ENDED }

    AuctionState withLeader(String leader, long max, long price) {
        return new AuctionState(id, equipmentId, startingPriceCents, reservePriceCents, depositCents, startsAt, endsAt,
                softCloseSeconds, price, leader, max, extensions);
    }

    AuctionState withPrice(long price) {
        return withLeader(leaderId, leaderMaxCents, price);
    }

    AuctionState withEnd(Instant newEnd, int newExtensions) {
        return new AuctionState(id, equipmentId, startingPriceCents, reservePriceCents, depositCents, startsAt, newEnd,
                softCloseSeconds, currentPriceCents, leaderId, leaderMaxCents, newExtensions);
    }
}
