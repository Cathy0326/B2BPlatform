package com.quipmarket.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class AuditTrailIT {

    @Autowired AuditTrail audit;
    @Autowired JdbcClient jdbc;

    @Test
    void chainVerifiesAndLinksEachRecordToThePrevious() {
        audit.record("TEST_EVENT", "s1", "tester", Map.of("n", 1));
        audit.record("TEST_EVENT", "s2", "tester", Map.of("n", 2));
        var latest = audit.latest(2);
        assertThat(latest.get(0).prevHash()).isEqualTo(latest.get(1).hash());
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    void updatesAndDeletesAreBlocked() {
        audit.record("TEST_EVENT", "s3", "tester", Map.of());
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_log SET actor = 'mallory'").update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_log").update()).rootCause().hasMessageContaining("append-only");
    }

    /**
     * Even a database admin who disables the trigger cannot edit history silently:
     * the recomputed hash no longer matches, and verify() names the exact record.
     */
    @Test
    void tamperingByAnAdminIsDetected() {
        audit.record("PAYMENT_CAPTURED", "pay_test", "system", Map.of("amountCents", 100_000));
        long seq = audit.latest(1).getFirst().seq();
        String original = audit.latest(1).getFirst().payload();
        try {
            jdbc.sql("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only").update();
            jdbc.sql("UPDATE audit_log SET payload = '{\"amountCents\":1}' WHERE seq = :s").param("s", seq).update();

            var v = audit.verify();
            assertThat(v.valid()).isFalse();
            assertThat(v.firstBrokenSeq()).isEqualTo(seq);
            assertThat(v.reason()).contains("modified");
        } finally {
            jdbc.sql("UPDATE audit_log SET payload = :p WHERE seq = :s").param("p", original).param("s", seq).update();
            jdbc.sql("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only").update();
        }
        assertThat(audit.verify().valid()).isTrue();
    }
}
