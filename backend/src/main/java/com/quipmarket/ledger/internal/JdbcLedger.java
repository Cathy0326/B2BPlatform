package com.quipmarket.ledger.internal;

import com.quipmarket.audit.AuditTrail;
import com.quipmarket.ledger.Ledger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JdbcLedger implements Ledger {

    private final JdbcClient jdbc;
    private final AuditTrail audit;

    JdbcLedger(JdbcClient jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    @Transactional
    public void ensureAccount(String code, AccountType type, String name) {
        jdbc.sql("INSERT INTO ledger_accounts (code, type, name) VALUES (:c, :t, :n) ON CONFLICT (code) DO NOTHING")
                .param("c", code).param("t", type.name()).param("n", name).update();
    }

    @Override
    @Transactional
    public Entry post(String kind, String reference, String description, List<Line> lines) {
        validate(lines); // fail fast in Java; the deferred DB trigger is the backstop

        var id = jdbc.sql("""
                        INSERT INTO journal_entries (kind, reference, description) VALUES (:k, :r, :d)
                        ON CONFLICT (kind, reference) DO NOTHING
                        RETURNING id
                        """)
                .param("k", kind).param("r", reference).param("d", description)
                .query(Long.class).optional();

        if (id.isEmpty()) {
            // Already posted (a retry). Return the original instead of posting twice.
            return entries(reference, 100).stream().filter(e -> e.kind().equals(kind)).findFirst().orElseThrow();
        }
        for (Line l : lines) {
            jdbc.sql("INSERT INTO journal_lines (entry_id, account_code, debit_cents, credit_cents) VALUES (:e, :a, :d, :c)")
                    .param("e", id.get()).param("a", l.accountCode()).param("d", l.debitCents()).param("c", l.creditCents())
                    .update();
        }
        long total = lines.stream().mapToLong(Line::debitCents).sum();
        audit.record("LEDGER_POSTED", reference, "system", Map.of("kind", kind, "entryId", id.get(), "amountCents", total));
        return entries(reference, 100).stream().filter(e -> e.id() == id.get()).findFirst().orElseThrow();
    }

    static void validate(List<Line> lines) {
        if (lines.size() < 2) throw new IllegalArgumentException("A journal entry needs at least two lines");
        long debits = 0, credits = 0;
        for (Line l : lines) {
            if (l.debitCents() < 0 || l.creditCents() < 0) throw new IllegalArgumentException("Negative amount in " + l);
            if ((l.debitCents() == 0) == (l.creditCents() == 0)) throw new IllegalArgumentException("Line must be a debit OR a credit: " + l);
            debits = Math.addExact(debits, l.debitCents());
            credits = Math.addExact(credits, l.creditCents());
        }
        if (debits != credits) throw new IllegalArgumentException("Unbalanced entry: debits " + debits + " != credits " + credits);
    }

    @Override
    @Transactional(readOnly = true)
    public TrialBalance trialBalance() {
        var accounts = jdbc.sql("""
                        SELECT a.code, a.type, a.name,
                               COALESCE(SUM(l.debit_cents), 0)  AS debits,
                               COALESCE(SUM(l.credit_cents), 0) AS credits
                        FROM ledger_accounts a LEFT JOIN journal_lines l ON l.account_code = a.code
                        GROUP BY a.code, a.type, a.name
                        ORDER BY a.type, a.code
                        """)
                .query((rs, i) -> new AccountBalance(rs.getString("code"), AccountType.valueOf(rs.getString("type")),
                        rs.getString("name"), rs.getLong("debits"), rs.getLong("credits")))
                .list();
        long d = accounts.stream().mapToLong(AccountBalance::debitsCents).sum();
        long c = accounts.stream().mapToLong(AccountBalance::creditsCents).sum();
        return new TrialBalance(accounts, d, c);
    }

    @Override
    @Transactional(readOnly = true)
    public long balanceOf(String accountCode) {
        return trialBalance().accounts().stream().filter(a -> a.code().equals(accountCode))
                .mapToLong(AccountBalance::balanceCents).findFirst().orElse(0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Entry> entries(String reference, int limit) {
        String where = reference == null ? "" : "WHERE e.reference = :ref ";
        var spec = jdbc.sql("""
                SELECT e.id, e.kind, e.reference, e.description, e.created_at, l.account_code, l.debit_cents, l.credit_cents
                FROM (SELECT * FROM journal_entries e %s ORDER BY id DESC LIMIT :n) e
                JOIN journal_lines l ON l.entry_id = e.id
                ORDER BY e.id DESC, l.id
                """.formatted(where)).param("n", Math.max(1, Math.min(limit, 500)));
        if (reference != null) spec = spec.param("ref", reference);

        // One row per journal LINE. Collect headers and lines separately, then build each immutable Entry once
        // (Entry copies its list, so appending to it after construction is impossible by design).
        record Header(long id, String kind, String reference, String description, Instant createdAt) {}
        Map<Long, Header> headers = new LinkedHashMap<>();
        Map<Long, List<Line>> lines = new HashMap<>();
        spec.query(rs -> {
            long id = rs.getLong("id");
            if (!headers.containsKey(id)) {
                headers.put(id, new Header(id, rs.getString("kind"), rs.getString("reference"), rs.getString("description"),
                        rs.getTimestamp("created_at").toInstant()));
            }
            lines.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(new Line(rs.getString("account_code"), rs.getLong("debit_cents"), rs.getLong("credit_cents")));
        });
        return headers.values().stream()
                .map(h -> new Entry(h.id(), h.kind(), h.reference(), h.description(), h.createdAt(), lines.get(h.id())))
                .toList();
    }
}
