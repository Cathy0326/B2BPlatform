package com.quipmarket.auction;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.auction.AuctionEngine.Accepted;
import com.quipmarket.auction.AuctionEngine.Rejected;
import com.quipmarket.auction.AuctionEngine.Rejection;
import com.quipmarket.auction.AuctionState.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Same cases as frontend/tests/auction.test.ts: one set of rules, two implementations. */
class AuctionEngineTest {

    static final Instant T0 = Instant.parse("2027-01-15T12:00:00Z");
    static final Instant DURING = T0.plusSeconds(60);

    static AuctionState lot(Long reserve) {
        return new AuctionState("a1", "eq-1", 5_000_000, reserve, 500_000, T0, T0.plus(Duration.ofHours(1)), 120,
                5_000_000, null, null, 0);
    }

    static AuctionState bid(AuctionState a, String who, long max, Instant at) {
        var out = AuctionEngine.placeBid(a, who, max, at);
        assertThat(out).isInstanceOf(Accepted.class);
        return ((Accepted) out).state();
    }

    static AuctionState bid(AuctionState a, String who, long max) {
        return bid(a, who, max, DURING);
    }

    @Test
    void incrementGrowsWithPrice() {
        assertThat(AuctionEngine.increment(500_000)).isEqualTo(10_000);
        assertThat(AuctionEngine.increment(5_000_000)).isEqualTo(50_000);
        assertThat(AuctionEngine.increment(80_000_000)).isEqualTo(250_000);
        // Every tier, at its exact boundary (the first price that belongs to the next tier):
        assertThat(AuctionEngine.increment(999_999)).isEqualTo(10_000);
        assertThat(AuctionEngine.increment(1_000_000)).isEqualTo(25_000);
        assertThat(AuctionEngine.increment(4_999_999)).isEqualTo(25_000);
        assertThat(AuctionEngine.increment(10_000_000)).isEqualTo(100_000);
        assertThat(AuctionEngine.increment(49_999_999)).isEqualTo(100_000);
        assertThat(AuctionEngine.increment(50_000_000)).isEqualTo(250_000);
    }

    @Test
    void statusFollowsTheClock() {
        var a = lot(null);
        assertThat(AuctionEngine.status(a, T0.minusMillis(1))).isEqualTo(Status.UPCOMING);
        assertThat(AuctionEngine.status(a, T0)).isEqualTo(Status.LIVE);
        assertThat(AuctionEngine.status(a, a.endsAt())).isEqualTo(Status.ENDED);
    }

    @Nested
    class ProxyBidding {
        @Test
        void firstBidOpensAtStartingPriceNotTheMax() {
            var a = bid(lot(null), "alice", 7_000_000);
            assertThat(a.leaderId()).isEqualTo("alice");
            assertThat(a.currentPriceCents()).isEqualTo(5_000_000);
        }

        @Test
        void lowerChallengerPushesPriceToTheirMaxPlusOneIncrement() {
            var a = bid(bid(lot(null), "alice", 7_000_000), "bob", 6_000_000);
            assertThat(a.leaderId()).isEqualTo("alice");
            assertThat(a.currentPriceCents()).isEqualTo(6_050_000);
        }

        @Test
        void higherChallengerTakesLeadAtOldMaxPlusOneIncrement() {
            var a = bid(bid(lot(null), "alice", 7_000_000), "bob", 9_000_000);
            assertThat(a.leaderId()).isEqualTo("bob");
            assertThat(a.currentPriceCents()).isEqualTo(7_050_000);
        }

        @Test
        void priceNeverExceedsLeaderMax() {
            var a = bid(bid(lot(null), "alice", 7_000_000), "bob", 7_020_000);
            assertThat(a.leaderId()).isEqualTo("bob");
            assertThat(a.currentPriceCents()).isEqualTo(7_020_000);
        }

        @Test
        void tieGoesToEarlierBidder() {
            var a = bid(bid(lot(null), "alice", 7_000_000), "bob", 7_000_000);
            assertThat(a.leaderId()).isEqualTo("alice");
            assertThat(a.currentPriceCents()).isEqualTo(7_000_000);
        }

        @Test
        void rejectsBelowMinimumAndReportsIt() {
            var a = bid(lot(null), "alice", 7_000_000);
            assertThat(AuctionEngine.placeBid(a, "bob", 5_010_000, DURING))
                    .isEqualTo(new Rejected(Rejection.TOO_LOW, AuctionEngine.minimumNext(a)));
        }

        @Test
        void leaderCanRaiseSecretMaxWithoutMovingPrice() {
            var a = bid(bid(lot(null), "alice", 6_000_000), "alice", 8_000_000);
            assertThat(a.currentPriceCents()).isEqualTo(5_000_000);
            assertThat(a.leaderMaxCents()).isEqualTo(8_000_000);
            assertThat(AuctionEngine.placeBid(a, "alice", 7_000_000, DURING))
                    .isEqualTo(new Rejected(Rejection.NOT_ABOVE_OWN_MAX, null));
        }
    }

    @Nested
    class Reserve {
        @Test
        void jumpsToReserveWhenLeaderMaxCoversIt() {
            var a = bid(lot(6_500_000L), "alice", 7_000_000);
            assertThat(a.currentPriceCents()).isEqualTo(6_500_000);
            assertThat(AuctionEngine.reserveMet(a)).isTrue();
        }

        @Test
        void endsUnsoldWhenReserveNotMet() {
            var a = bid(lot(9_000_000L), "alice", 7_000_000);
            assertThat(AuctionEngine.result(a, a.endsAt())).isEqualTo(new AuctionEngine.Result(false, null, null));
        }
    }

    @Nested
    class SoftClose {
        @Test
        void lateBidExtendsTheAuction() {
            var a0 = lot(null);
            Instant late = a0.endsAt().minusSeconds(30);
            var out = (Accepted) AuctionEngine.placeBid(a0, "alice", 6_000_000, late);
            assertThat(out.extended()).isTrue();
            assertThat(out.state().endsAt()).isEqualTo(late.plusSeconds(120));
            assertThat(out.state().extensions()).isEqualTo(1);
        }

        @Test
        void earlyBidDoesNotExtend() {
            assertThat(((Accepted) AuctionEngine.placeBid(lot(null), "alice", 6_000_000, DURING)).extended()).isFalse();
        }

        @Test
        void theWindowIsStrictlyTheLastTwoMinutes() {
            var a0 = lot(null);
            // Exactly 120 s left is outside the window; one millisecond later is inside.
            var atEdge = (Accepted) AuctionEngine.placeBid(a0, "alice", 6_000_000, a0.endsAt().minusSeconds(120));
            var inside = (Accepted) AuctionEngine.placeBid(a0, "alice", 6_000_000, a0.endsAt().minusSeconds(120).plusMillis(1));
            assertThat(atEdge.extended()).isFalse();
            assertThat(inside.extended()).isTrue();
        }

        @Test
        void rejectsBeforeStartAndAfterEnd() {
            var a = lot(null);
            assertThat(AuctionEngine.placeBid(a, "x", 9_000_000, T0.minusMillis(1))).isEqualTo(new Rejected(Rejection.NOT_STARTED, null));
            assertThat(AuctionEngine.placeBid(a, "x", 9_000_000, a.endsAt())).isEqualTo(new Rejected(Rejection.ENDED, null));
        }
    }

    /**
     * Exact boundaries, added after mutation testing (PIT) showed that flipping these comparisons
     * (for example "<" to "<=") left every other test green.
     */
    @Nested
    class Boundaries {
        @Test
        void aBidOfExactlyTheMinimumIsAccepted() {
            var a = bid(lot(null), "alice", 6_000_000);
            long minimum = AuctionEngine.minimumNext(a);
            assertThat(AuctionEngine.placeBid(a, "bob", minimum, DURING)).isInstanceOf(Accepted.class);
            assertThat(AuctionEngine.placeBid(a, "bob", minimum - 1, DURING))
                    .isEqualTo(new Rejected(Rejection.TOO_LOW, minimum));
        }

        @Test
        void leaderRepeatingTheSameMaxIsRejected() {
            var a = bid(lot(null), "alice", 6_000_000);
            assertThat(AuctionEngine.placeBid(a, "alice", 6_000_000, DURING))
                    .isEqualTo(new Rejected(Rejection.NOT_ABOVE_OWN_MAX, null));
        }

        @Test
        void zeroOrNegativeMaxIsTooLowEvenBeforeTheStart() {
            var before = T0.minusSeconds(1);
            assertThat(AuctionEngine.placeBid(lot(null), "alice", 0, before)).isInstanceOf(Rejected.class)
                    .extracting(o -> ((Rejected) o).reason()).isEqualTo(Rejection.TOO_LOW);
            assertThat(AuctionEngine.placeBid(lot(null), "alice", -1, DURING)).isInstanceOf(Rejected.class)
                    .extracting(o -> ((Rejected) o).reason()).isEqualTo(Rejection.TOO_LOW);
        }

        @Test
        void maxExactlyAtTheReserveShowsTheReserve() {
            var a = bid(lot(6_000_000L), "alice", 6_000_000);
            assertThat(a.currentPriceCents()).isEqualTo(6_000_000);
            assertThat(AuctionEngine.reserveMet(a)).isTrue();
        }

        @Test
        void noExtraReserveBidWhenThePriceAlreadyEqualsTheReserve() {
            // Reserve = starting price: the opening bid already meets it, so only one visible bid appears.
            var out = (Accepted) AuctionEngine.placeBid(lot(5_000_000L), "alice", 7_000_000, DURING);
            assertThat(out.newBids()).hasSize(1);
            assertThat(out.state().currentPriceCents()).isEqualTo(5_000_000);
        }
    }

    @Test
    void winnerPaysCurrentPriceNotTheirMax() {
        var a = bid(bid(lot(null), "alice", 9_000_000), "bob", 6_000_000);
        assertThat(AuctionEngine.result(a, a.endsAt())).isEqualTo(new AuctionEngine.Result(true, "alice", 6_050_000L));
    }

    @Test
    void randomSequencesKeepInvariants() {
        var a = lot(null);
        long highest = 0;
        var rnd = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            String who = "b" + rnd.nextInt(5);
            long max = 5_000_000 + rnd.nextInt(200) * 25_000L;
            if (!(AuctionEngine.placeBid(a, who, max, DURING) instanceof Accepted acc)) continue;
            a = acc.state();
            highest = Math.max(highest, max);
            assertThat(a.currentPriceCents()).isLessThanOrEqualTo(a.leaderMaxCents());
            assertThat(a.currentPriceCents()).isGreaterThanOrEqualTo(a.startingPriceCents());
            assertThat(a.leaderMaxCents()).isEqualTo(highest);
        }
    }
}
