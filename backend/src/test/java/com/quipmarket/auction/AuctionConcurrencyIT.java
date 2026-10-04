package com.quipmarket.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class AuctionConcurrencyIT {

    @Autowired AuctionService auctions;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    private String newLot(String id) {
        Instant now = clock.instant();
        auctions.create(new AuctionState(id, "eq-1002", 15_000_000, null, 1_500_000, now.minusSeconds(60),
                now.plus(Duration.ofHours(1)), 120, 15_000_000, null, null, 0));
        return id;
    }

    /**
     * 40 bidders submit different maxes at the same instant. With the per-lot row lock the result
     * must be exactly what a serial run would produce: the leader is whoever submitted the highest
     * max, and nothing is lost. Without the lock, two transactions could read the same old leader
     * and the later write would silently erase the earlier one (lost update).
     */
    @Test
    void concurrentBidsAreSerializedPerLot() throws Exception {
        String lot = newLot("it-race-" + System.nanoTime());
        int threads = 40;
        var maxes = new ArrayList<Long>();
        for (int i = 0; i < threads; i++) maxes.add(15_000_000L + i * 100_000L);
        Collections.shuffle(maxes);
        for (int i = 0; i < threads; i++) auctions.register(lot, "bidder-" + i, null);

        var go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<AuctionService.BidResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String bidder = "bidder-" + i;
                long max = maxes.get(i);
                futures.add(pool.submit(() -> {
                    go.await();
                    return auctions.placeBid(lot, bidder, max);
                }));
            }
            go.countDown();
            for (var f : futures) f.get();
        }

        long highest = Collections.max(maxes);
        String expectedLeader = "bidder-" + maxes.indexOf(highest);
        var view = auctions.find(lot).orElseThrow();
        assertThat(view.leaderId()).isEqualTo(expectedLeader);
        assertThat(view.leaderMaxCents()).isEqualTo(highest);
        assertThat(view.currentPriceCents()).isLessThanOrEqualTo(highest);

        int submissions = jdbc.sql("SELECT count(*) FROM bid_submissions WHERE auction_id = :a").param("a", lot).query(Integer.class).single();
        assertThat(submissions).isEqualTo(threads); // every attempt is audited, accepted or not
    }

    @Test
    void biddingRequiresADepositHoldAndRegistrationIsIdempotent() {
        String lot = newLot("it-reg-" + System.nanoTime());
        assertThatThrownBy(() -> auctions.placeBid(lot, "carol", 16_000_000)).isInstanceOf(NotRegisteredException.class);

        var first = auctions.register(lot, "carol", null);
        var second = auctions.register(lot, "carol", null);
        assertThat(second).isEqualTo(first);
        int holds = jdbc.sql("SELECT count(*) FROM auction_registrations WHERE auction_id = :a").param("a", lot).query(Integer.class).single();
        assertThat(holds).isEqualTo(1);

        assertThat(auctions.placeBid(lot, "carol", 16_000_000).accepted()).isTrue();
    }

    @Test
    void softCloseExtendsAndEndedLotsRejectBids() {
        Instant now = clock.instant();
        String lot = "it-soft-" + System.nanoTime();
        auctions.create(new AuctionState(lot, "eq-1002", 15_000_000, null, 1_500_000, now.minusSeconds(3600),
                now.plusSeconds(30), 120, 15_000_000, null, null, 0));
        auctions.register(lot, "dave", null);

        var r = auctions.placeBid(lot, "dave", 15_500_000);
        assertThat(r.extended()).isTrue();
        assertThat(r.auction().endsAt()).isEqualTo(now.plusSeconds(120));

        clock.advance(Duration.ofMinutes(5));
        try {
            var late = auctions.placeBid(lot, "dave", 20_000_000);
            assertThat(late.accepted()).isFalse();
            assertThat(late.reason()).isEqualTo(AuctionEngine.Rejection.ENDED);
            assertThat(auctions.find(lot).orElseThrow().result().sold()).isTrue();
        } finally {
            clock.advance(Duration.ofMinutes(-5));
        }
    }
}
