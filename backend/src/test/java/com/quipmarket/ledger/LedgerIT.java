package com.quipmarket.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.ledger.Ledger.Line;
import com.quipmarket.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class LedgerIT {

    @Autowired Ledger ledger;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private String acct(String suffix) {
        String code = "test:" + suffix + ":" + System.nanoTime();
        ledger.ensureAccount(code, Ledger.AccountType.LIABILITY, "test");
        return code;
    }

    @Test
    void postsBalancedEntriesAndComputesBalancesFromLines() {
        String escrow = acct("escrow");
        String ref = "ref-" + System.nanoTime();
        ledger.post("TEST_IN", ref, "in", List.of(Line.debit("platform_cash", 10_000), Line.credit(escrow, 10_000)));
        assertThat(ledger.balanceOf(escrow)).isEqualTo(10_000);
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void samePostingTwiceIsANoOp() {
        String escrow = acct("dup");
        String ref = "ref-" + System.nanoTime();
        var first = ledger.post("TEST_DUP", ref, "x", List.of(Line.debit("platform_cash", 500), Line.credit(escrow, 500)));
        var again = ledger.post("TEST_DUP", ref, "x", List.of(Line.debit("platform_cash", 500), Line.credit(escrow, 500)));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(ledger.balanceOf(escrow)).isEqualTo(500); // not 1,000
    }

    @Test
    void javaRejectsUnbalancedOrTwoSidedLines() {
        String a = acct("bad");
        assertThatThrownBy(() -> ledger.post("BAD", "r1-" + System.nanoTime(), "x", List.of(Line.debit("platform_cash", 100), Line.credit(a, 99))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unbalanced");
        assertThatThrownBy(() -> ledger.post("BAD", "r2-" + System.nanoTime(), "x", List.of(new Line(a, 5, 5), Line.credit(a, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void databaseRejectsAnUnbalancedEntryEvenIfJavaIsBypassed() {
        String a = acct("raw");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Long id = jdbc.sql("INSERT INTO journal_entries (kind, reference, description) VALUES ('RAW', :r, 'bypass') RETURNING id")
                    .param("r", "raw-" + System.nanoTime()).query(Long.class).single();
            jdbc.sql("INSERT INTO journal_lines (entry_id, account_code, debit_cents) VALUES (:e, 'platform_cash', 100)").param("e", id).update();
            jdbc.sql("INSERT INTO journal_lines (entry_id, account_code, credit_cents) VALUES (:e, :a, 90)").param("e", id).param("a", a).update();
        })).hasMessageContaining("unbalanced"); // deferred constraint trigger fires at COMMIT
    }

    @Test
    void journalIsAppendOnly() {
        String a = acct("ro");
        String ref = "ro-" + System.nanoTime();
        var e = ledger.post("TEST_RO", ref, "x", List.of(Line.debit("platform_cash", 7), Line.credit(a, 7)));
        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_lines SET debit_cents = 1 WHERE entry_id = :e").param("e", e.id()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries WHERE id = :e").param("e", e.id()).update())
                .rootCause().hasMessageContaining("append-only");
    }
}
