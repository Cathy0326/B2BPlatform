package com.quipmarket.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.auction.AuctionEngine.Accepted;
import com.quipmarket.auction.AuctionEngine.Rejected;
import com.quipmarket.auction.AuctionEngine.Rejection;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Random bidding wars against the proxy-bidding engine. AuctionEngineTest pins down hand-picked cases;
 * these properties must hold after every accepted bid of every generated sequence, and jqwik shrinks any
 * failure to the shortest sequence that still breaks the rule.
 */
class AuctionEngineProperties {

    static final Instant START = Instant.parse("2027-01-15T12:00:00Z");
    static final long STARTING_PRICE = 5_000_000;

    record BidCmd(String bidder, long maxCents, int atSecond) {}

    /**
     * What each bidder has committed to so far: their highest accepted max and when it arrived. Arrival order,
     * not the clock, breaks ties: two bids in the same second are still processed one after the other.
     */
    record Commitment(long maxCents, int arrival) {}

    @Provide
    Arbitrary<List<BidCmd>> bidSequences() {
        Arbitrary<BidCmd> bid = Combinators.combine(
                Arbitraries.of("ann", "bob", "cy", "dee"),
                Arbitraries.longs().between(1, 30_000_000),
                Arbitraries.integers().between(1, 3_599)).as(BidCmd::new);
        return bid.list().ofMinSize(1).ofMaxSize(30)
                .map(l -> l.stream().sorted(Comparator.comparingInt(BidCmd::atSecond)).toList());
    }

    @Provide
    Arbitrary<Long> reserves() {
        return Arbitraries.longs().between(STARTING_PRICE, 20_000_000).injectNull(0.3);
    }

    static AuctionState lot(Long reserve) {
        return new AuctionState("a1", "eq-1", STARTING_PRICE, reserve, 500_000, START, START.plus(Duration.ofHours(1)),
                120, STARTING_PRICE, null, null, 0);
    }

    @Property(tries = 500)
    void everyAcceptedBidKeepsTheAuctionFairAndConsistent(@ForAll("bidSequences") List<BidCmd> bids,
                                                          @ForAll("reserves") Long reserve) {
        AuctionState state = lot(reserve);
        Map<String, Commitment> committed = new HashMap<>();

        for (int arrival = 0; arrival < bids.size(); arrival++) {
            BidCmd b = bids.get(arrival);
            Instant at = START.plusSeconds(b.atSecond());
            long minimumBefore = AuctionEngine.minimumNext(state);
            var outcome = AuctionEngine.placeBid(state, b.bidder(), b.maxCents(), at);

            if (outcome instanceof Rejected r) {
                // A bid is only refused for a reason that is actually true.
                if (r.reason() == Rejection.TOO_LOW) assertThat(b.maxCents()).isLessThan(minimumBefore);
                if (r.reason() == Rejection.NOT_ABOVE_OWN_MAX) {
                    assertThat(b.bidder()).isEqualTo(state.leaderId());
                    assertThat(b.maxCents()).isLessThanOrEqualTo(state.leaderMaxCents());
                }
                continue;
            }

            AuctionState before = state;
            state = ((Accepted) outcome).state();
            committed.merge(b.bidder(), new Commitment(b.maxCents(), arrival),
                    (old, now) -> now.maxCents() > old.maxCents() ? now : old);

            // The leader is whoever offered the most; on a tie, whoever offered it first (price, then time priority).
            String expectedLeader = committed.entrySet().stream()
                    .min(Comparator.<Map.Entry<String, Commitment>>comparingLong(e -> -e.getValue().maxCents())
                            .thenComparingInt(e -> e.getValue().arrival()))
                    .orElseThrow().getKey();
            assertThat(state.leaderId()).isEqualTo(expectedLeader);

            // The winner never pays more than their own secret max...
            assertThat(state.currentPriceCents()).isLessThanOrEqualTo(state.leaderMaxCents());
            // ...nor less than any rival offered (second-price auction) or the starting price.
            String leader = state.leaderId();
            long runnerUp = committed.entrySet().stream().filter(e -> !e.getKey().equals(leader))
                    .mapToLong(e -> e.getValue().maxCents()).max().orElse(STARTING_PRICE);
            assertThat(state.currentPriceCents()).isGreaterThanOrEqualTo(Math.max(runnerUp, STARTING_PRICE));
            // A max that covers the reserve shows at least the reserve.
            if (reserve != null && state.leaderMaxCents() >= reserve) {
                assertThat(state.currentPriceCents()).isGreaterThanOrEqualTo(reserve);
            }

            // Prices only go up, and the end only moves later (soft close), never earlier.
            assertThat(state.currentPriceCents()).isGreaterThanOrEqualTo(before.currentPriceCents());
            assertThat(state.endsAt()).isAfterOrEqualTo(before.endsAt());
            boolean insideSoftClose = Duration.between(at, before.endsAt()).compareTo(Duration.ofSeconds(120)) < 0;
            assertThat(((Accepted) outcome).extended()).isEqualTo(insideSoftClose);
        }

        // After the end: sold exactly when someone leads and the reserve is met, at the final price.
        var result = AuctionEngine.result(state, state.endsAt());
        boolean shouldSell = state.leaderId() != null && (reserve == null || state.currentPriceCents() >= reserve);
        assertThat(result.sold()).isEqualTo(shouldSell);
        if (shouldSell) {
            assertThat(result.winnerId()).isEqualTo(state.leaderId());
            assertThat(result.hammerPriceCents()).isEqualTo(state.currentPriceCents());
        }
    }

    @Property
    void incrementNeverShrinksAsThePriceGrows(@ForAll("prices") long a, @ForAll("prices") long b) {
        if (a <= b) assertThat(AuctionEngine.increment(a)).isLessThanOrEqualTo(AuctionEngine.increment(b));
    }

    @Provide
    Arbitrary<Long> prices() {
        return Arbitraries.longs().between(0, 200_000_000);
    }
}
