package com.quipmarket.auction.internal;

import com.quipmarket.auction.AuctionState;
import com.quipmarket.auction.Registration;
import com.quipmarket.auction.VisibleBid;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AuctionRepository {

    private static final String COLUMNS = """
            a.id, a.equipment_id, a.starting_price_cents, a.reserve_price_cents, a.deposit_cents,
            a.starts_at, a.ends_at, a.soft_close_seconds, a.current_price_cents, a.leader_id,
            a.leader_max_cents, a.extensions
            """;

    /** State + the public bid count (for list views). */
    record Row(AuctionState state, int bidCount) {}

    private final JdbcClient jdbc;

    AuctionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Pessimistic row lock: other transactions calling this for the SAME auction wait here until
     * we commit. That serializes bids per lot, so two bids can never both read the old leader and
     * overwrite each other (the "lost update" anomaly).
     */
    Optional<AuctionState> lockById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM auctions a WHERE a.id = :id FOR UPDATE")
                .param("id", id).query(this::mapState).optional();
    }

    Optional<Row> findById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + ", (SELECT count(*) FROM auction_bids b WHERE b.auction_id = a.id) AS bid_count FROM auctions a WHERE a.id = :id")
                .param("id", id).query(this::mapRow).optional();
    }

    List<Row> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + ", (SELECT count(*) FROM auction_bids b WHERE b.auction_id = a.id) AS bid_count FROM auctions a ORDER BY a.ends_at")
                .query(this::mapRow).list();
    }

    /** Latest auction per equipment (one query for a whole list). */
    Map<String, Row> findLatestByEquipmentIds(Collection<String> equipmentIds) {
        if (equipmentIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                        SELECT DISTINCT ON (a.equipment_id) %s,
                               (SELECT count(*) FROM auction_bids b WHERE b.auction_id = a.id) AS bid_count
                        FROM auctions a WHERE a.equipment_id IN (:ids)
                        ORDER BY a.equipment_id, a.ends_at DESC
                        """.formatted(COLUMNS))
                .param("ids", equipmentIds).query(this::mapRow).list().stream()
                .collect(Collectors.toMap(r -> r.state().equipmentId(), Function.identity()));
    }

    void update(AuctionState s) {
        jdbc.sql("""
                        UPDATE auctions SET current_price_cents = :price, leader_id = :leader, leader_max_cents = :max,
                               ends_at = :endsAt, extensions = :ext
                        WHERE id = :id
                        """)
                .param("price", s.currentPriceCents())
                .param("leader", s.leaderId())
                .param("max", s.leaderMaxCents())
                .param("endsAt", Timestamp.from(s.endsAt()))
                .param("ext", s.extensions())
                .param("id", s.id())
                .update();
    }

    void insert(AuctionState s) {
        jdbc.sql("""
                        INSERT INTO auctions (id, equipment_id, starting_price_cents, reserve_price_cents, deposit_cents, starts_at,
                                              ends_at, soft_close_seconds, current_price_cents, leader_id, leader_max_cents, extensions)
                        VALUES (:id, :eq, :start, :reserve, :deposit, :startsAt, :endsAt, :soft, :price, :leader, :max, :ext)
                        """)
                .param("id", s.id())
                .param("eq", s.equipmentId())
                .param("start", s.startingPriceCents())
                .param("reserve", s.reservePriceCents())
                .param("deposit", s.depositCents())
                .param("startsAt", Timestamp.from(s.startsAt()))
                .param("endsAt", Timestamp.from(s.endsAt()))
                .param("soft", s.softCloseSeconds())
                .param("price", s.currentPriceCents())
                .param("leader", s.leaderId())
                .param("max", s.leaderMaxCents())
                .param("ext", s.extensions())
                .update();
    }

    void insertBids(String auctionId, List<VisibleBid> bids) {
        for (VisibleBid b : bids) {
            jdbc.sql("INSERT INTO auction_bids (auction_id, bidder_id, amount_cents, auto, created_at) VALUES (:a, :b, :amt, :auto, :at)")
                    .param("a", auctionId).param("b", b.bidderId()).param("amt", b.amountCents())
                    .param("auto", b.auto()).param("at", Timestamp.from(b.at()))
                    .update();
        }
    }

    void recordSubmission(String auctionId, String bidderId, long maxCents, String outcome, Instant at) {
        jdbc.sql("INSERT INTO bid_submissions (auction_id, bidder_id, max_cents, outcome, created_at) VALUES (:a, :b, :m, :o, :at)")
                .param("a", auctionId).param("b", bidderId).param("m", maxCents).param("o", outcome).param("at", Timestamp.from(at))
                .update();
    }

    /** Newest first. */
    List<VisibleBid> lastBids(String auctionId, int limit) {
        return jdbc.sql("SELECT bidder_id, amount_cents, auto, created_at FROM auction_bids WHERE auction_id = :a ORDER BY id DESC LIMIT :n")
                .param("a", auctionId).param("n", limit)
                .query((rs, i) -> new VisibleBid(rs.getString("bidder_id"), rs.getLong("amount_cents"), rs.getBoolean("auto"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /** Idempotent: ON CONFLICT DO NOTHING, then read back whatever row exists. */
    Registration register(String auctionId, String bidderId, long depositCents) {
        jdbc.sql("""
                        INSERT INTO auction_registrations (auction_id, bidder_id, deposit_cents)
                        VALUES (:a, :b, :d) ON CONFLICT (auction_id, bidder_id) DO NOTHING
                        """)
                .param("a", auctionId).param("b", bidderId).param("d", depositCents).update();
        return findRegistration(auctionId, bidderId).orElseThrow();
    }

    Optional<Registration> findRegistration(String auctionId, String bidderId) {
        return jdbc.sql("SELECT auction_id, bidder_id, deposit_cents, status, created_at FROM auction_registrations WHERE auction_id = :a AND bidder_id = :b")
                .param("a", auctionId).param("b", bidderId)
                .query((rs, i) -> new Registration(rs.getString("auction_id"), rs.getString("bidder_id"), rs.getLong("deposit_cents"),
                        Registration.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    void deleteAll() {
        jdbc.sql("DELETE FROM auction_registrations").update();
        jdbc.sql("DELETE FROM bid_submissions").update();
        jdbc.sql("DELETE FROM auction_bids").update();
        jdbc.sql("DELETE FROM auctions").update();
    }

    private Row mapRow(ResultSet rs, int i) throws SQLException {
        return new Row(mapState(rs, i), rs.getInt("bid_count"));
    }

    private AuctionState mapState(ResultSet rs, int i) throws SQLException {
        return new AuctionState(
                rs.getString("id"),
                rs.getString("equipment_id"),
                rs.getLong("starting_price_cents"),
                (Long) rs.getObject("reserve_price_cents"),
                rs.getLong("deposit_cents"),
                rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("ends_at").toInstant(),
                rs.getInt("soft_close_seconds"),
                rs.getLong("current_price_cents"),
                rs.getString("leader_id"),
                (Long) rs.getObject("leader_max_cents"),
                rs.getInt("extensions"));
    }
}
