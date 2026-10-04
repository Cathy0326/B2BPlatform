package com.quipmarket.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.Registration;
import com.quipmarket.audit.AuditTrail;
import com.quipmarket.ledger.Ledger;
import com.quipmarket.payments.Payment;
import com.quipmarket.payments.PaymentDeclinedException;
import com.quipmarket.payments.Payments;
import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.Lots;
import com.quipmarket.support.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class EscrowFlowIT {

    @Autowired AuctionService auctions;
    @Autowired Escrow escrow;
    @Autowired Payments payments;
    @Autowired Ledger ledger;
    @Autowired AuditTrail audit;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    private Duration moved = Duration.ZERO;

    private void advance(Duration d) {
        clock.advance(d);
        moved = moved.plus(d);
    }

    @AfterEach
    void resetClock() {
        clock.advance(moved.negated());
        moved = Duration.ZERO;
    }

    /** $150,000 lot, $15,000 deposit. alice wins at $170,500 against bob (max $170,000); carol bids low. */
    private String wonLot() {
        String lot = Lots.live(auctions, clock, "esc", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        for (String b : List.of("alice", "bob", "carol")) {
            assertThat(auctions.register(lot, b, null).status()).isEqualTo(Registration.Status.HELD);
        }
        auctions.placeBid(lot, "carol", 15_500_000);
        auctions.placeBid(lot, "bob", 17_000_000);
        auctions.placeBid(lot, "alice", 20_000_000);
        advance(Duration.ofHours(1)); // auction is over
        return lot;
    }

    @Test
    void happyPathMovesEveryDollarThroughBalancedEntries() {
        String lot = wonLot();

        EscrowDeal deal = escrow.settle(lot).orElseThrow();
        assertThat(deal.buyerId()).isEqualTo("alice");
        assertThat(deal.hammerCents()).isEqualTo(17_100_000);       // bob's max $170,000 + $1,000 increment
        assertThat(deal.feeCents()).isEqualTo(855_000);             // 5%
        assertThat(deal.state()).isEqualTo(EscrowDeal.State.AWAITING_BALANCE); // deposit already captured

        // Losers' holds were released (voided at the provider), winner's deposit was captured.
        var regs = auctions.registrations(lot);
        assertThat(regs).filteredOn(r -> !r.bidderId().equals("alice")).allMatch(r -> r.status() == Registration.Status.RELEASED);
        assertThat(regs).filteredOn(r -> r.bidderId().equals("alice")).allMatch(r -> r.status() == Registration.Status.APPLIED);
        assertThat(payments.find(deal.depositPaymentId()).orElseThrow().status()).isEqualTo(Payment.Status.CAPTURED);
        assertThat(ledger.balanceOf(deal.escrowAccount())).isEqualTo(1_500_000);

        deal = escrow.payBalance(deal.id(), "alice", null);
        assertThat(deal.state()).isEqualTo(EscrowDeal.State.FUNDED);
        assertThat(ledger.balanceOf(deal.escrowAccount())).isEqualTo(17_100_000); // escrow holds the full hammer price

        deal = escrow.confirmDelivery(deal.id(), "alice");
        assertThat(deal.state()).isEqualTo(EscrowDeal.State.PAID_OUT);

        // After payout nothing is left in escrow or owed to the seller.
        assertThat(ledger.balanceOf(deal.escrowAccount())).isZero();
        assertThat(ledger.balanceOf(deal.sellerAccount())).isZero();
        var kinds = ledger.entries(deal.id(), 10).stream().map(Ledger.Entry::kind).toList();
        assertThat(kinds).containsExactly("SELLER_PAYOUT", "ESCROW_RELEASED", "BALANCE_RECEIVED", "DEPOSIT_CAPTURED");
        assertThat(ledger.trialBalance().balanced()).isTrue();
        assertThat(audit.verify().valid()).isTrue();
    }

    @Test
    void confirmingTwiceOrPayingTwiceDoesNotMoveMoneyTwice() {
        EscrowDeal deal = escrow.settle(wonLot()).orElseThrow();
        escrow.payBalance(deal.id(), "alice", null);
        assertThatThrownBy(() -> escrow.payBalance(deal.id(), "alice", null)).hasMessageContaining("no balance");
        escrow.confirmDelivery(deal.id(), "alice");
        escrow.confirmDelivery(deal.id(), "alice"); // no-op
        assertThat(ledger.entries(deal.id(), 10)).hasSize(4);
    }

    @Test
    void otherUsersCannotSeeOrTouchTheDeal() {
        EscrowDeal deal = escrow.settle(wonLot()).orElseThrow();
        assertThatThrownBy(() -> escrow.payBalance(deal.id(), "bob", null)).hasMessageContaining("not found");
        assertThatThrownBy(() -> escrow.confirmDelivery(deal.id(), "bob")).hasMessageContaining("not found");
    }

    @Test
    void concurrentSettlementCreatesExactlyOneDealAndCapturesOnce() throws Exception {
        String lot = wonLot();
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) fs.add(pool.submit(() -> { go.await(); return escrow.settle(lot); }));
            go.countDown();
            for (var f : fs) f.get();
        }
        int deals = jdbc.sql("SELECT count(*) FROM escrow_deals WHERE auction_id = :a").param("a", lot).query(Integer.class).single();
        assertThat(deals).isEqualTo(1);
        assertThat(ledger.entries("deal-" + lot, 10)).extracting(Ledger.Entry::kind).containsExactly("DEPOSIT_CAPTURED");
    }

    @Test
    void unsoldAuctionReleasesEveryHoldAndCreatesNoDeal() {
        String lot = Lots.live(auctions, clock, "unsold", "eq-1002", 15_000_000, 30_000_000L, 1_500_000, Duration.ofMinutes(30));
        auctions.register(lot, "dan", null);
        auctions.placeBid(lot, "dan", 16_000_000); // below the $300k reserve
        advance(Duration.ofHours(1));

        assertThat(escrow.settle(lot)).isEmpty();
        assertThat(escrow.find("deal-" + lot)).isEmpty();
        assertThat(auctions.registrations(lot)).allMatch(r -> r.status() == Registration.Status.RELEASED);
    }

    @Test
    void declinedCardLeavesNoRegistrationSoTheBidderCanRetry() {
        String lot = Lots.live(auctions, clock, "decl", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        assertThatThrownBy(() -> auctions.register(lot, "erin", "pm_card_chargeDeclined"))
                .isInstanceOf(PaymentDeclinedException.class);
        assertThat(auctions.registration(lot, "erin")).isEmpty();
        assertThat(auctions.register(lot, "erin", "pm_card_visa").status()).isEqualTo(Registration.Status.HELD);
    }

    @Test
    void threeDSecureLeavesRegistrationPendingUntilConfirmed() {
        String lot = Lots.live(auctions, clock, "3ds", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        var reg = auctions.register(lot, "fay", "pm_card_authenticationRequired");
        assertThat(reg.status()).isEqualTo(Registration.Status.PENDING);
        assertThat(payments.find(reg.paymentId()).orElseThrow().clientSecret()).isNotBlank();
        assertThatThrownBy(() -> auctions.placeBid(lot, "fay", 16_000_000)).hasMessageContaining("not complete");
    }

    @Test
    void backgroundJobSettlesEndedAuctionsAndIsSafeToRunTwice() {
        String lot = wonLot();

        escrow.runPendingWork();
        escrow.runPendingWork(); // the scheduler may fire again before anything changed

        var deals = escrow.dealsForBuyer("alice").stream().filter(d -> d.auctionId().equals(lot)).toList();
        assertThat(deals).hasSize(1);
        assertThat(deals.getFirst().state()).isEqualTo(EscrowDeal.State.AWAITING_BALANCE);
        assertThat(ledger.balanceOf(deals.getFirst().escrowAccount())).isEqualTo(1_500_000); // deposit captured once
    }

    @Test
    void aDeclinedBalanceCanBeRetriedWithAnotherCard() {
        EscrowDeal deal = escrow.settle(wonLot()).orElseThrow();

        assertThatThrownBy(() -> escrow.payBalance(deal.id(), "alice", "pm_card_chargeDeclined")).isInstanceOf(PaymentDeclinedException.class);
        EscrowDeal afterDecline = escrow.find(deal.id()).orElseThrow();
        assertThat(afterDecline.state()).isEqualTo(EscrowDeal.State.AWAITING_BALANCE);
        assertThat(ledger.balanceOf(deal.escrowAccount())).isEqualTo(1_500_000); // nothing moved

        EscrowDeal paid = escrow.payBalance(deal.id(), "alice", "pm_card_visa");

        assertThat(paid.state()).isEqualTo(EscrowDeal.State.FUNDED);
        assertThat(paid.balancePaymentId()).isNotEqualTo(afterDecline.balancePaymentId()); // a NEW charge, because the first failed
        assertThat(ledger.balanceOf(deal.escrowAccount())).isEqualTo(deal.hammerCents());
    }

    @Test
    void payingAgainDuringThreeDSecureReusesThePendingChargeInsteadOfStartingASecondOne() {
        EscrowDeal deal = escrow.settle(wonLot()).orElseThrow();

        EscrowDeal first = escrow.payBalance(deal.id(), "alice", "pm_card_authenticationRequired");
        EscrowDeal second = escrow.payBalance(deal.id(), "alice", "pm_card_visa");

        assertThat(first.state()).isEqualTo(EscrowDeal.State.AWAITING_BALANCE); // waiting for the buyer's bank
        assertThat(payments.find(first.balancePaymentId()).orElseThrow().status()).isEqualTo(Payment.Status.REQUIRES_ACTION);
        assertThat(second.balancePaymentId()).isEqualTo(first.balancePaymentId());
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE auction_id = :a AND purpose = 'BALANCE'")
                .param("a", deal.auctionId()).query(Integer.class).single()).isEqualTo(1);
    }
}
