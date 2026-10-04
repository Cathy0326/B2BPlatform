package com.quipmarket.escrow.internal;

import com.quipmarket.escrow.EscrowDeal;
import com.quipmarket.escrow.EscrowDeal.State;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EscrowRepository {

    private final JdbcClient jdbc;

    EscrowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(EscrowDeal d) {
        jdbc.sql("""
                        INSERT INTO escrow_deals (id, auction_id, equipment_id, buyer_id, seller_id, hammer_cents, deposit_cents,
                                                  fee_cents, deposit_payment_id, state)
                        VALUES (:id, :a, :eq, :buyer, :seller, :hammer, :deposit, :fee, :dp, :state)
                        ON CONFLICT (auction_id) DO NOTHING
                        """)
                .param("id", d.id()).param("a", d.auctionId()).param("eq", d.equipmentId()).param("buyer", d.buyerId())
                .param("seller", d.sellerId()).param("hammer", d.hammerCents()).param("deposit", d.depositCents())
                .param("fee", d.feeCents()).param("dp", d.depositPaymentId()).param("state", d.state().name())
                .update();
    }

    Optional<EscrowDeal> find(String id) {
        return jdbc.sql("SELECT * FROM escrow_deals WHERE id = :id").param("id", id).query(this::map).optional();
    }

    Optional<EscrowDeal> lock(String id) {
        return jdbc.sql("SELECT * FROM escrow_deals WHERE id = :id FOR UPDATE").param("id", id).query(this::map).optional();
    }

    List<EscrowDeal> byBuyer(String buyerId) {
        return jdbc.sql("SELECT * FROM escrow_deals WHERE buyer_id = :b ORDER BY created_at DESC").param("b", buyerId).query(this::map).list();
    }

    List<EscrowDeal> inStates(List<State> states) {
        return jdbc.sql("SELECT * FROM escrow_deals WHERE state IN (:s) ORDER BY created_at")
                .param("s", states.stream().map(Enum::name).toList()).query(this::map).list();
    }

    /**
     * Optimistic compare-and-set: succeeds only if the deal is still in `from`.
     * Two concurrent requests trying the same step -> exactly one gets 1 row, the other 0.
     */
    boolean transition(String id, State from, State to) {
        return jdbc.sql("UPDATE escrow_deals SET state = :to, updated_at = now() WHERE id = :id AND state = :from")
                .param("to", to.name()).param("id", id).param("from", from.name()).update() == 1;
    }

    void setBalancePayment(String id, String paymentId) {
        jdbc.sql("UPDATE escrow_deals SET balance_payment_id = :p, updated_at = now() WHERE id = :id").param("p", paymentId).param("id", id).update();
    }

    private EscrowDeal map(ResultSet rs, int i) throws SQLException {
        return new EscrowDeal(rs.getString("id"), rs.getString("auction_id"), rs.getString("equipment_id"), rs.getString("buyer_id"),
                rs.getString("seller_id"), rs.getLong("hammer_cents"), rs.getLong("deposit_cents"), rs.getLong("fee_cents"),
                rs.getString("deposit_payment_id"), rs.getString("balance_payment_id"), State.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
