package com.quipmarket.auction;

import com.quipmarket.auction.AuctionState.Status;
import java.time.Instant;

/**
 * What the API returns for an auction at a moment in time (status and result depend on "now").
 * leaderMaxCents is kept for the `myMaxCents` resolver but is NOT a schema field,
 * so GraphQL can never serialize it to other bidders.
 */
public record AuctionView(
        String id,
        String equipmentId,
        Status status,
        long startingPriceCents,
        long depositCents,
        boolean hasReserve,
        boolean reserveMet,
        Instant startsAt,
        Instant endsAt,
        int softCloseSeconds,
        long currentPriceCents,
        long minimumNextBidCents,
        String leaderId,
        Long leaderMaxCents,
        int extensions,
        int bidCount,
        AuctionEngine.Result result) {

    public static AuctionView of(AuctionState s, int bidCount, Instant now) {
        return new AuctionView(s.id(), s.equipmentId(), AuctionEngine.status(s, now), s.startingPriceCents(), s.depositCents(),
                s.reservePriceCents() != null, AuctionEngine.reserveMet(s), s.startsAt(), s.endsAt(), s.softCloseSeconds(),
                s.currentPriceCents(), AuctionEngine.minimumNext(s), s.leaderId(), s.leaderMaxCents(), s.extensions(), bidCount,
                AuctionEngine.result(s, now));
    }
}
