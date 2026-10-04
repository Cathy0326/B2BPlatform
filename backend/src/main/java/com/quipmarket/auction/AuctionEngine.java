package com.quipmarket.auction;

import com.quipmarket.auction.AuctionState.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Proxy-bidding English auction rules. Pure functions: no database, no clock, no Spring.
 * Port of frontend/app/utils/auction.ts with the same test cases (AuctionEngineTest).
 *
 * Priority = PRICE, then TIME: a tie goes to whoever reached that max first, the same
 * rule an exchange order book uses.
 *
 * Concurrency is NOT handled here on purpose. The caller (AuctionService) guarantees that only
 * one transaction at a time runs this for a given lot (SELECT ... FOR UPDATE).
 */
public final class AuctionEngine {

    private AuctionEngine() {}

    public enum Rejection { NOT_STARTED, ENDED, TOO_LOW, NOT_ABOVE_OWN_MAX }

    public sealed interface Outcome permits Accepted, Rejected {}

    /** @param newBids visible bids produced by this submission, in order */
    public record Accepted(AuctionState state, List<VisibleBid> newBids, boolean leading, boolean extended) implements Outcome {}

    public record Rejected(Rejection reason, Long minimumCents) implements Outcome {}

    public record Result(boolean sold, String winnerId, Long hammerPriceCents) {}

    /** Bid increment grows with price (cents). */
    public static long increment(long priceCents) {
        if (priceCents < 1_000_000) return 10_000;      // < $10k   -> $100
        if (priceCents < 5_000_000) return 25_000;      // < $50k   -> $250
        if (priceCents < 10_000_000) return 50_000;     // < $100k  -> $500
        if (priceCents < 50_000_000) return 100_000;    // < $500k  -> $1,000
        return 250_000;                                  //          -> $2,500
    }

    public static Status status(AuctionState a, Instant now) {
        if (now.isBefore(a.startsAt())) return Status.UPCOMING;
        if (!now.isBefore(a.endsAt())) return Status.ENDED;
        return Status.LIVE;
    }

    public static long minimumNext(AuctionState a) {
        return a.leaderId() == null ? a.startingPriceCents() : a.currentPriceCents() + increment(a.currentPriceCents());
    }

    public static boolean reserveMet(AuctionState a) {
        return a.reservePriceCents() == null || (a.leaderId() != null && a.currentPriceCents() >= a.reservePriceCents());
    }

    public static Outcome placeBid(AuctionState a, String bidderId, long maxCents, Instant now) {
        if (maxCents <= 0) return new Rejected(Rejection.TOO_LOW, minimumNext(a));
        switch (status(a, now)) {
            case UPCOMING -> { return new Rejected(Rejection.NOT_STARTED, null); }
            case ENDED -> { return new Rejected(Rejection.ENDED, null); }
            case LIVE -> { /* continue */ }
        }

        List<VisibleBid> bids = new ArrayList<>();
        AuctionState s;

        if (bidderId.equals(a.leaderId())) {
            // Leader raising their own secret max: nobody to outbid, price does not move.
            if (maxCents <= a.leaderMaxCents()) return new Rejected(Rejection.NOT_ABOVE_OWN_MAX, null);
            s = a.withLeader(bidderId, maxCents, a.currentPriceCents());
        } else {
            long minimum = minimumNext(a);
            if (maxCents < minimum) return new Rejected(Rejection.TOO_LOW, minimum);

            if (a.leaderId() == null) {
                s = a.withLeader(bidderId, maxCents, a.startingPriceCents());
                bids.add(new VisibleBid(bidderId, s.currentPriceCents(), false, now));
            } else if (maxCents > a.leaderMaxCents()) {
                long oldMax = a.leaderMaxCents();
                bids.add(new VisibleBid(a.leaderId(), oldMax, true, now));
                s = a.withLeader(bidderId, maxCents, Math.min(maxCents, oldMax + increment(oldMax)));
                bids.add(new VisibleBid(bidderId, s.currentPriceCents(), false, now));
            } else {
                // Leader's max >= challenger's: leader keeps it. Equal max -> earlier bidder wins.
                bids.add(new VisibleBid(bidderId, maxCents, false, now));
                s = a.withPrice(Math.min(a.leaderMaxCents(), maxCents + increment(maxCents)));
                bids.add(new VisibleBid(a.leaderId(), s.currentPriceCents(), true, now));
            }
        }

        // Reserve jump: if the leader's max covers the reserve, show the reserve price.
        if (s.reservePriceCents() != null && s.leaderMaxCents() >= s.reservePriceCents() && s.currentPriceCents() < s.reservePriceCents()) {
            s = s.withPrice(s.reservePriceCents());
            bids.add(new VisibleBid(s.leaderId(), s.currentPriceCents(), true, now));
        }

        // Soft close: a bid inside the last window pushes the end out (anti-sniping).
        boolean extended = false;
        Duration window = Duration.ofSeconds(s.softCloseSeconds());
        if (Duration.between(now, s.endsAt()).compareTo(window) < 0) {
            s = s.withEnd(now.plus(window), s.extensions() + 1);
            extended = true;
        }

        return new Accepted(s, List.copyOf(bids), bidderId.equals(s.leaderId()), extended);
    }

    /** Null while the auction is still running. */
    public static Result result(AuctionState a, Instant now) {
        if (status(a, now) != Status.ENDED) return null;
        if (a.leaderId() == null || !reserveMet(a)) return new Result(false, null, null);
        return new Result(true, a.leaderId(), a.currentPriceCents());
    }
}
