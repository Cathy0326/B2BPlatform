package com.quipmarket.audit.internal;

import com.quipmarket.audit.AuditTrail;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class JdbcAuditTrail implements AuditTrail {

    /** Arbitrary constant: the advisory-lock id that serializes appends to the chain. */
    private static final long CHAIN_LOCK = 7_001L;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    JdbcAuditTrail(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(String eventType, String subject, String actor, Map<String, ?> payload) {
        // Two concurrent appends must not both link to the same previous hash (that would fork the chain).
        // A transaction-scoped advisory lock serializes appends; it is released at COMMIT/ROLLBACK.
        jdbc.sql("SELECT pg_advisory_xact_lock(:k)").param("k", CHAIN_LOCK).query((rs, i) -> 1).list();

        String prev = jdbc.sql("SELECT hash FROM audit_log ORDER BY seq DESC LIMIT 1").query(String.class).optional().orElse(GENESIS);
        // PostgreSQL stores microseconds; truncate BEFORE hashing so the stored value re-hashes identically.
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String canonicalPayload = json.writeValueAsString(new TreeMap<>(payload)); // sorted keys = canonical
        String hash = hash(prev, eventType, subject, actor, at, canonicalPayload);

        jdbc.sql("""
                        INSERT INTO audit_log (event_type, subject, actor, payload, created_at, prev_hash, hash)
                        VALUES (:t, :s, :a, :p, :at, :prev, :h)
                        """)
                .param("t", eventType).param("s", subject).param("a", actor).param("p", canonicalPayload)
                .param("at", Timestamp.from(at)).param("prev", prev).param("h", hash)
                .update();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditRecord> latest(int limit) {
        return jdbc.sql("SELECT * FROM audit_log ORDER BY seq DESC LIMIT :n").param("n", Math.max(1, Math.min(limit, 500)))
                .query((rs, i) -> new AuditRecord(rs.getLong("seq"), rs.getString("event_type"), rs.getString("subject"),
                        rs.getString("actor"), rs.getString("payload"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("prev_hash"), rs.getString("hash")))
                .list();
    }

    /** Walk the chain from the start and recompute every hash. O(n); fine for a demo, batch in production. */
    @Override
    @Transactional(readOnly = true)
    public Verification verify() {
        var rows = jdbc.sql("SELECT * FROM audit_log ORDER BY seq")
                .query((rs, i) -> new AuditRecord(rs.getLong("seq"), rs.getString("event_type"), rs.getString("subject"),
                        rs.getString("actor"), rs.getString("payload"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("prev_hash"), rs.getString("hash")))
                .list();
        String expectedPrev = GENESIS;
        for (AuditRecord r : rows) {
            if (!r.prevHash().equals(expectedPrev)) {
                return new Verification(false, rows.size(), r.seq(), "prev_hash does not match the previous record (row inserted or deleted)");
            }
            String recomputed = hash(r.prevHash(), r.eventType(), r.subject(), r.actor(), r.createdAt(), r.payload());
            if (!recomputed.equals(r.hash())) {
                return new Verification(false, rows.size(), r.seq(), "content does not match its hash (row modified)");
            }
            expectedPrev = r.hash();
        }
        return new Verification(true, rows.size(), null, null);
    }

    /**
     * SHA-256 over a JSON ARRAY of the fields. An array is unambiguous; naive "a|b|c" joining is not
     * (subject "x|y" + actor "z" would collide with subject "x" + actor "y|z").
     */
    private String hash(String prev, String type, String subject, String actor, Instant at, String payload) {
        String material = json.writeValueAsString(List.of(prev, type, subject, actor, at.toString(), payload));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
