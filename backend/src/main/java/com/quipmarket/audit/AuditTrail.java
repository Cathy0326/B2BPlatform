package com.quipmarket.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Tamper-evident audit trail. Each record stores the hash of the previous record, so changing,
 * inserting or deleting any row breaks every hash after it, and {@link #verify()} pinpoints where.
 *
 * Call {@link #record} inside the business transaction: the audit row commits or rolls back
 * together with the change it describes.
 */
public interface AuditTrail {

    String GENESIS = "0".repeat(64);

    record AuditRecord(long seq, String eventType, String subject, String actor, String payload,
                       Instant createdAt, String prevHash, String hash) {}

    record Verification(boolean valid, long entries, Long firstBrokenSeq, String reason) {}

    void record(String eventType, String subject, String actor, Map<String, ?> payload);

    List<AuditRecord> latest(int limit);

    Verification verify();
}
