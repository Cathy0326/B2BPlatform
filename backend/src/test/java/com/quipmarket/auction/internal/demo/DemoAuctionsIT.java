package com.quipmarket.auction.internal.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState;
import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo profile seeds auctions and runs rival bots. It creates and deletes auctions, so it gets its OWN
 * Spring context (different profile = different cached context = its own PostgreSQL container) and can never
 * disturb the other integration tests.
 */
@IntegrationTest
@ActiveProfiles("demo")
@TestPropertySource(properties = "quipmarket.demo.bots-enabled=false") // the scheduled bean stays quiet; tests drive bots explicitly
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoAuctionsIT {

    static final Instant START = Instant.parse("2026-10-01T12:00:00Z"); // the frozen test clock

    @Autowired DemoAuctions demo;
    @Autowired AuctionService auctions;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void resetClock() {
        clock.set(START);
    }

    int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    /** A Random whose answers are fixed, so the bot always (or never) bids, on the first lot, at the minimum. */
    static Random fixed(double decision) {
        return new Random() {
            @Override public double nextDouble() { return decision; }
            @Override public int nextInt(int bound) { return 0; }
        };
    }

    @Test
    @Order(1)
    void startupSeedsTheFiveDemoLotsWithReplayedHistory() {
        assertThat(jdbc.sql("SELECT id FROM auctions WHERE id LIKE 'au-200_' ORDER BY id").query(String.class).list())
                .containsExactly("au-2001", "au-2002", "au-2003", "au-2004", "au-2005");

        assertThat(auctions.find("au-2001").orElseThrow().leaderId()).isEqualTo("b-4821");                       // replayed through the real engine
        assertThat(count("SELECT count(*) FROM auction_bids WHERE auction_id = 'au-2001'")).isGreaterThanOrEqualTo(4);
        assertThat(auctions.list(AuctionState.Status.LIVE)).extracting(v -> v.id()).contains("au-2001", "au-2002", "au-2003");

        // Bots hold deposits WITHOUT a payment, so they can never create charges at a real provider.
        assertThat(count("SELECT count(*) FROM auction_registrations WHERE auction_id = 'au-2001' AND status = 'HELD'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM payments WHERE auction_id LIKE 'au-200%'")).isZero();
    }

    @Test
    @Order(2)
    void reseedDoesNothingWhileALotIsLive() {
        int before = count("SELECT count(*) FROM auctions");

        demo.reseedIfIdle();

        assertThat(count("SELECT count(*) FROM auctions")).isEqualTo(before);
    }

    @Test
    @Order(3)
    void whenEverythingHasEndedItCleansUpFinishedLotsAndSeedsFreshOnes() {
        // au-2005 is finished: ended and settled, with no escrow deal. That is exactly what cleanup may delete.
        jdbc.sql("UPDATE auctions SET settled_at = now() WHERE id = 'au-2005'").update();
        clock.advance(Duration.ofDays(30)); // nothing is live any more

        demo.reseedIfIdle();

        // The finished au-2005 was deleted, which freed its friendly id, so the fresh copy reuses it:
        // it now ends 2 hours before the NEW "now" instead of 2 hours before the start of the test.
        assertThat(auctions.find("au-2005").orElseThrow().endsAt()).isEqualTo(clock.instant().minus(Duration.ofHours(2)));
        assertThat(count("SELECT count(*) FROM auctions WHERE id = 'au-2005' AND settled_at IS NULL")).isEqualTo(1); // a fresh, unsettled lot
        long minute = clock.instant().getEpochSecond() / 60;
        assertThat(auctions.find("au-2001-" + minute)).isPresent(); // au-2001 was not finished, so its id is taken: unique suffix
        assertThat(auctions.list(AuctionState.Status.LIVE)).isNotEmpty();
    }

    @Test
    @Order(4)
    void anotherReplicaHoldingTheSeedLockMeansThisOneSkips() throws Exception {
        clock.advance(Duration.ofDays(60)); // idle again
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread otherReplica = Thread.ofPlatform().start(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(7002)").query(Object.class).single();
            locked.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        int before = count("SELECT count(*) FROM auctions");

        demo.reseedIfIdle();
        int whileLocked = count("SELECT count(*) FROM auctions");
        release.countDown();
        otherReplica.join();
        demo.reseedIfIdle();

        assertThat(whileLocked).isEqualTo(before);                              // skipped: not the leader
        assertThat(count("SELECT count(*) FROM auctions")).isGreaterThan(before); // seeded once the lock was free
    }

    @Test
    @Order(5)
    void botsBidOnALiveLotOnlyWhenEnabledAndWhenTheyDecideTo() {
        int before = count("SELECT count(*) FROM auction_bids");

        demo.botTick();                                                                   // bean: bots disabled
        new DemoAuctions(auctions, jdbc, clock, true, txManager, fixed(0.99)).botTick();  // enabled, decides not to bid
        assertThat(count("SELECT count(*) FROM auction_bids")).isEqualTo(before);

        new DemoAuctions(auctions, jdbc, clock, true, txManager, fixed(0.0)).botTick();   // enabled, bids
        assertThat(count("SELECT count(*) FROM auction_bids")).isGreaterThan(before);
        assertThat(jdbc.sql("SELECT bidder_id FROM auction_bids ORDER BY id DESC LIMIT 1").query(String.class).single()).startsWith("b-");
    }

    @Test
    @Order(6)
    void botsDoNothingWhenNoLotIsLive() {
        clock.advance(Duration.ofDays(5 * 365));
        int before = count("SELECT count(*) FROM auction_bids");

        new DemoAuctions(auctions, jdbc, clock, true, txManager, fixed(0.0)).botTick();

        assertThat(count("SELECT count(*) FROM auction_bids")).isEqualTo(before);
    }
}
