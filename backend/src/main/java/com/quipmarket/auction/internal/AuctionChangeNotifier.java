package com.quipmarket.auction.internal;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Cross-replica auction events via PostgreSQL LISTEN/NOTIFY.
 *
 * Problem: with 3 backend replicas, a bid handled by replica A must reach browsers subscribed on B and C.
 * An in-memory event bus only reaches A's own clients.
 *
 * Solution, using the database we already have:
 *   - publish(): `SELECT pg_notify('auction_changes', id)` INSIDE the bid transaction.
 *     PostgreSQL delivers notifications only when that transaction COMMITS (never on rollback),
 *     so subscribers can never see a bid that did not happen.
 *   - every replica runs one listener thread on a dedicated connection (`LISTEN auction_changes`)
 *     and forwards ids to its local Reactor sink, which feeds its GraphQL subscriptions.
 * No Redis or Kafka needed at this scale. (NOTIFY payloads are small and not durable: fine for
 * "something changed, re-read it" signals, not for business events that must never be lost.)
 */
@Component
class AuctionChangeNotifier implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AuctionChangeNotifier.class);
    static final String CHANNEL = "auction_changes";

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
    private volatile boolean running;
    private Thread thread;

    AuctionChangeNotifier(DataSource dataSource, JdbcClient jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** Call inside the business transaction. Delivered to all replicas on commit. */
    void publish(String auctionId) {
        jdbc.sql("SELECT pg_notify(:ch, :id)").param("ch", CHANNEL).param("id", auctionId).query((rs, i) -> 1).list();
    }

    Flux<String> changes() {
        return sink.asFlux();
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("auction-listen").daemon().start(this::listenLoop);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listenLoop() {
        Duration backoff = Duration.ofMillis(500);
        while (running) {
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                st.execute("LISTEN " + CHANNEL);
                PGConnection pg = conn.unwrap(PGConnection.class);
                backoff = Duration.ofMillis(500);
                while (running) {
                    PGNotification[] batch = pg.getNotifications(5_000); // blocks up to 5 s
                    if (batch == null) continue;
                    for (PGNotification n : batch) {
                        sink.emitNext(n.getParameter(), Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
                    }
                }
            } catch (Exception e) {
                if (!running) return;
                log.warn("LISTEN connection lost ({}), reconnecting in {} ms", e.getMessage(), backoff.toMillis());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : backoff.multipliedBy(2);
            }
        }
    }
}
