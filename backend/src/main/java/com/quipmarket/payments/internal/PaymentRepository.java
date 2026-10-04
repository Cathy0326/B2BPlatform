package com.quipmarket.payments.internal;

import com.quipmarket.payments.Payment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Payment p) {
        jdbc.sql("""
                        INSERT INTO payments (id, provider, purpose, auction_id, payer_id, amount_cents, currency, status)
                        VALUES (:id, :prov, :purpose, :a, :payer, :amt, :cur, :st)
                        """)
                .param("id", p.id()).param("prov", p.provider()).param("purpose", p.purpose().name())
                .param("a", p.auctionId()).param("payer", p.payerId()).param("amt", p.amountCents())
                .param("cur", p.currency()).param("st", p.status().name())
                .update();
    }

    Optional<Payment> find(String id) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id").param("id", id).query(this::map).optional();
    }

    Optional<Payment> lockByProviderRef(String ref) {
        return jdbc.sql("SELECT * FROM payments WHERE provider_ref = :r FOR UPDATE").param("r", ref).query(this::map).optional();
    }

    Optional<Payment> lock(String id) {
        return jdbc.sql("SELECT * FROM payments WHERE id = :id FOR UPDATE").param("id", id).query(this::map).optional();
    }

    void update(String id, String providerRef, Payment.Status status, String clientSecret) {
        jdbc.sql("""
                        UPDATE payments SET provider_ref = COALESCE(:ref, provider_ref), status = :st,
                               client_secret = :cs, updated_at = now()
                        WHERE id = :id
                        """)
                .param("ref", providerRef).param("st", status.name()).param("cs", clientSecret).param("id", id)
                .update();
    }

    private Payment map(ResultSet rs, int i) throws SQLException {
        return new Payment(rs.getString("id"), rs.getString("provider"), rs.getString("provider_ref"),
                Payment.Purpose.valueOf(rs.getString("purpose")), rs.getString("auction_id"), rs.getString("payer_id"),
                rs.getLong("amount_cents"), rs.getString("currency"), Payment.Status.valueOf(rs.getString("status")),
                rs.getString("client_secret"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
