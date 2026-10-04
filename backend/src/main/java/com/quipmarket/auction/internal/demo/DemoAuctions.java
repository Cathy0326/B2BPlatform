package com.quipmarket.auction.internal.demo;

import com.quipmarket.auction.AuctionEngine;
import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Demo profile only. Keeps a public demo alive:
 *  - on startup and every 5 minutes, if no auction is LIVE, re-creates the 5 demo lots relative to "now"
 *    (same seeds as frontend/app/data/auctions.ts), replaying their history through the real engine;
 *  - every few seconds, a rival "bot" may bid on a live lot, which exercises row locks and subscriptions.
 */
@Component
@Profile("demo")
class DemoAuctions implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAuctions.class);
    private static final List<String> BOTS = List.of("b-4821", "b-1377", "b-9054", "b-2210", "b-7788", "b-3141", "b-6060");

    record Seed(String id, String equipmentId, long startCents, Long reserveCents, long depositCents,
                Duration startOffset, Duration endOffset, List<long[]> history, List<String> bidders) {}

    private static final List<Seed> SEEDS = List.of(
            new Seed("au-2001", "eq-1002", 15_000_000, 17_500_000L, 1_500_000, Duration.ofHours(-26), Duration.ofMinutes(134),
                    List.of(new long[] {0, 15_500_000}, new long[] {1, 16_200_000}, new long[] {0, 17_000_000}, new long[] {2, 16_800_000}),
                    List.of("b-4821", "b-1377", "b-9054")),
            new Seed("au-2002", "eq-1006", 9_000_000, null, 900_000, Duration.ofHours(-47), Duration.ofMinutes(6),
                    List.of(new long[] {0, 9_200_000}, new long[] {1, 9_800_000}, new long[] {0, 10_400_000}, new long[] {2, 10_100_000}),
                    List.of("b-2210", "b-7788", "b-3141")),
            new Seed("au-2003", "eq-1010", 65_000_000, 78_000_000L, 2_500_000, Duration.ofHours(-10), Duration.ofHours(29),
                    List.<long[]>of(new long[] {0, 66_000_000}), List.of("b-6060")),
            new Seed("au-2004", "eq-1011", 6_500_000, null, 650_000, Duration.ofHours(3), Duration.ofHours(51), List.of(), List.of()),
            new Seed("au-2005", "eq-1005", 11_000_000, 12_500_000L, 1_100_000, Duration.ofHours(-72), Duration.ofHours(-2),
                    List.of(new long[] {0, 12_000_000}, new long[] {1, 13_400_000}, new long[] {0, 13_100_000}),
                    List.of("b-5150", "b-8008")));

    private final AuctionService auctions;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final boolean botsEnabled;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final Random random;

    @org.springframework.beans.factory.annotation.Autowired
    DemoAuctions(AuctionService auctions, JdbcClient jdbc, Clock clock,
                 @Value("${quipmarket.demo.bots-enabled:false}") boolean botsEnabled,
                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this(auctions, jdbc, clock, botsEnabled, txManager, new Random());
    }

    /** Tests pass a predictable Random so bot behaviour is reproducible. */
    DemoAuctions(AuctionService auctions, JdbcClient jdbc, Clock clock, boolean botsEnabled,
                 org.springframework.transaction.PlatformTransactionManager txManager, Random random) {
        this.random = random;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.auctions = auctions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.botsEnabled = botsEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        reseedIfIdle();
    }

    /**
     * NOT annotated @Transactional on purpose: run() calls this method on `this`, which bypasses Spring's
     * proxy (self-invocation), so the annotation would silently do nothing. An explicit TransactionTemplate
     * works no matter who calls.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void reseedIfIdle() {
        tx.executeWithoutResult(status -> reseedInTransaction());
    }

    private void reseedInTransaction() {
        // With several replicas, only the one holding this transaction-scoped advisory lock seeds.
        boolean leader = Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(7002)").query(Boolean.class).single());
        if (!leader || !auctions.list(AuctionState.Status.LIVE).isEmpty()) return;
        log.info("No live auctions: seeding demo lots relative to now");
        // Remove only lots that are fully finished: ended + settled (holds released) and without an escrow deal,
        // plus upcoming lots nobody registered for. Lots with money in escrow are kept for the ledger history.
        List<String> stale = jdbc.sql("""
                        SELECT a.id FROM auctions a
                        WHERE (a.ends_at <= now() AND a.settled_at IS NOT NULL
                               AND NOT EXISTS (SELECT 1 FROM escrow_deals d WHERE d.auction_id = a.id))
                           OR (a.starts_at > now()
                               AND NOT EXISTS (SELECT 1 FROM auction_registrations r WHERE r.auction_id = a.id))
                        """).query(String.class).list();
        if (!stale.isEmpty()) {
            for (String table : List.of("auction_registrations", "bid_submissions", "auction_bids")) {
                jdbc.sql("DELETE FROM " + table + " WHERE auction_id IN (:ids)").param("ids", stale).update();
            }
            jdbc.sql("DELETE FROM payments WHERE auction_id IN (:ids)").param("ids", stale).update();
            jdbc.sql("DELETE FROM auctions WHERE id IN (:ids)").param("ids", stale).update();
        }

        Instant now = clock.instant();
        for (Seed s : SEEDS) {
            // First seed keeps the friendly ids (au-2001...); later reseeds get a unique suffix.
            boolean taken = jdbc.sql("SELECT count(*) FROM auctions WHERE id = :id").param("id", s.id()).query(Integer.class).single() > 0;
            String id = taken ? s.id() + "-" + (now.getEpochSecond() / 60) : s.id();
            Instant startsAt = now.plus(s.startOffset());
            Instant endsAt = now.plus(s.endOffset());
            AuctionState state = new AuctionState(id, s.equipmentId(), s.startCents(), s.reserveCents(), s.depositCents(),
                    startsAt, endsAt, 120, s.startCents(), null, null, 0);
            // Replay history at evenly spaced past instants. The engine needs "now" inside the window,
            // so we insert the lot with its real times and apply each historic bid via the engine directly.
            Instant last = (endsAt.isBefore(now) ? endsAt : now).minusSeconds(60);
            var bids = new java.util.ArrayList<com.quipmarket.auction.VisibleBid>();
            for (int i = 0; i < s.history().size(); i++) {
                long[] h = s.history().get(i);
                Instant at = startsAt.plus(Duration.between(startsAt, last).multipliedBy(i + 1).dividedBy(s.history().size() + 1));
                if (AuctionEngine.placeBid(state, s.bidders().get((int) h[0]), h[1], at) instanceof AuctionEngine.Accepted a) {
                    state = a.state();
                    bids.addAll(a.newBids());
                }
            }
            auctions.create(state);
            for (var b : bids) {
                jdbc.sql("INSERT INTO auction_bids (auction_id, bidder_id, amount_cents, auto, created_at) VALUES (:a, :b, :amt, :auto, :at)")
                        .param("a", id).param("b", b.bidderId()).param("amt", b.amountCents()).param("auto", b.auto())
                        .param("at", java.sql.Timestamp.from(b.at())).update();
            }
            for (String bidder : s.bidders()) registerBot(id, bidder, s.depositCents());
        }
    }

    /**
     * Demo bidders get a HELD registration WITHOUT a payment: bots must never create charges at a real
     * provider (with the Stripe gateway that would mean a PaymentIntent every few seconds).
     */
    private void registerBot(String auctionId, String bot, long depositCents) {
        jdbc.sql("""
                        INSERT INTO auction_registrations (auction_id, bidder_id, deposit_cents, status)
                        VALUES (:a, :b, :d, 'HELD') ON CONFLICT (auction_id, bidder_id) DO NOTHING
                        """)
                .param("a", auctionId).param("b", bot).param("d", depositCents).update();
    }

    /** A rival may bid on a random live lot, making the demo feel alive. */
    @Scheduled(fixedDelay = 6_000, initialDelay = 10_000)
    public void botTick() {
        if (!botsEnabled) return;
        var live = auctions.list(AuctionState.Status.LIVE);
        if (live.isEmpty() || random.nextDouble() > 0.35) return;
        var lot = live.get(random.nextInt(live.size()));
        var candidates = BOTS.stream().filter(b -> !b.equals(lot.leaderId())).toList();
        String bot = candidates.get(random.nextInt(candidates.size()));
        long min = lot.minimumNextBidCents();
        long max = min + AuctionEngine.increment(min) * random.nextInt(4);
        try {
            registerBot(lot.id(), bot, lot.depositCents());
            auctions.placeBid(lot.id(), bot, max);
        } catch (RuntimeException e) {
            log.debug("Bot bid skipped: {}", e.getMessage());
        }
    }
}
