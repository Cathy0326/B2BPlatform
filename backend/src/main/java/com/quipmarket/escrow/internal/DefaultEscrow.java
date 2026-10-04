package com.quipmarket.escrow.internal;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionView;
import com.quipmarket.auction.Registration;
import com.quipmarket.audit.AuditTrail;
import com.quipmarket.catalog.Catalog;
import com.quipmarket.escrow.Escrow;
import com.quipmarket.escrow.EscrowDeal;
import com.quipmarket.escrow.EscrowDeal.State;
import com.quipmarket.ledger.Ledger;
import com.quipmarket.ledger.Ledger.Line;
import com.quipmarket.payments.Payment;
import com.quipmarket.payments.PaymentStatusChanged;
import com.quipmarket.payments.Payments;
import com.quipmarket.shared.DomainException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Escrow settlement as a SAGA: a sequence of local transactions, each one safe to retry.
 *
 * Why not one big transaction? Because the steps call a payment provider over the network.
 * A DB transaction cannot roll back a card capture, and holding row locks during a slow HTTP call
 * would block everyone else. So every step follows the same recipe:
 *   (a) decide + record intent in a short transaction
 *   (b) call the provider outside any transaction, with a deterministic idempotency key
 *   (c) record the outcome (ledger entry + state transition) in another short transaction
 * If the process dies between (b) and (c), {@link #runPendingWork()} repeats (b): the provider sees the
 * same idempotency key and returns the original result, so money never moves twice.
 */
@Service
class DefaultEscrow implements Escrow {

    private static final Logger log = LoggerFactory.getLogger(DefaultEscrow.class);
    static final String PLATFORM_CASH = "platform_cash";
    static final String PLATFORM_REVENUE = "platform_revenue";

    private final EscrowRepository repo;
    private final AuctionService auctions;
    private final Payments payments;
    private final Ledger ledger;
    private final Catalog catalog;
    private final AuditTrail audit;
    private final TransactionTemplate tx;

    DefaultEscrow(EscrowRepository repo, AuctionService auctions, Payments payments, Ledger ledger, Catalog catalog,
                  AuditTrail audit, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.auctions = auctions;
        this.payments = payments;
        this.ledger = ledger;
        this.catalog = catalog;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ settlement

    @Override
    public Optional<EscrowDeal> settle(String auctionId) {
        Optional<EscrowDeal> created = tx.execute(s -> {
            Optional<AuctionView> claimed = auctions.claimForSettlement(auctionId);
            if (claimed.isEmpty()) return Optional.<EscrowDeal>empty(); // not ended, or another worker got it
            AuctionView a = claimed.get();
            if (a.result() == null || !a.result().sold()) {
                audit.record("AUCTION_UNSOLD", auctionId, "system", Map.of("reserveMet", a.reserveMet()));
                return Optional.<EscrowDeal>empty();
            }
            String winner = a.result().winnerId();
            Optional<Registration> winnerReg = auctions.registrations(auctionId).stream()
                    .filter(r -> r.bidderId().equals(winner)).findFirst();
            long hammer = a.result().hammerPriceCents();
            Instant now = Instant.now();
            var deal = new EscrowDeal("deal-" + auctionId, auctionId, a.equipmentId(), winner, catalog.sellerOf(a.equipmentId()),
                    hammer, Math.min(winnerReg.map(Registration::depositCents).orElse(0L), hammer), Escrow.platformFee(hammer),
                    winnerReg.map(Registration::paymentId).orElse(null), null, State.AWAITING_DEPOSIT_CAPTURE, now, now);
            ledger.ensureAccount(deal.escrowAccount(), Ledger.AccountType.LIABILITY, "Buyer funds held in escrow for " + auctionId);
            ledger.ensureAccount(deal.sellerAccount(), Ledger.AccountType.LIABILITY, "Owed to seller " + deal.sellerId());
            repo.insert(deal);
            audit.record("DEAL_CREATED", deal.id(), "system", Map.of("auctionId", auctionId, "buyerId", winner,
                    "hammerCents", hammer, "depositCents", deal.depositCents(), "feeCents", deal.feeCents()));
            return Optional.of(deal);
        });
        releaseLosingHolds();
        created.ifPresent(d -> advance(d.id()));
        return created.flatMap(d -> repo.find(d.id()));
    }

    @Override
    public void runPendingWork() {
        for (String auctionId : auctions.endedUnsettledAuctionIds()) {
            try {
                settle(auctionId);
            } catch (RuntimeException e) {
                log.warn("Settlement of {} failed, will retry: {}", auctionId, e.getMessage());
            }
        }
        releaseLosingHolds();
        for (EscrowDeal d : repo.inStates(List.of(State.AWAITING_DEPOSIT_CAPTURE, State.RELEASED))) {
            try {
                advance(d.id());
            } catch (RuntimeException e) {
                log.warn("Deal {} step failed, will retry: {}", d.id(), e.getMessage());
            }
        }
    }

    /** Release every deposit hold still HELD in a settled auction, except the winner's (it gets captured). */
    private void releaseLosingHolds() {
        for (Registration r : auctions.heldRegistrationsInSettledAuctions()) {
            boolean isWinner = repo.find("deal-" + r.auctionId()).map(d -> d.buyerId().equals(r.bidderId())).orElse(false);
            if (isWinner) continue;
            try {
                if (r.paymentId() == null) auctions.markRegistration(r.auctionId(), r.bidderId(), Registration.Status.RELEASED);
                else payments.cancel(r.paymentId()); // listener in the auction module marks it RELEASED
            } catch (RuntimeException e) {
                log.warn("Releasing hold {}/{} failed, will retry: {}", r.auctionId(), r.bidderId(), e.getMessage());
            }
        }
    }

    /** Run the automatic steps of the state machine (deposit capture, seller payout). */
    private void advance(String dealId) {
        EscrowDeal d = repo.find(dealId).orElseThrow();
        if (d.state() == State.AWAITING_DEPOSIT_CAPTURE) {
            if (d.depositPaymentId() != null) {
                payments.capture(d.depositPaymentId()); // -> PaymentStatusChanged(CAPTURED) -> onPaymentStatusChanged
            } else {
                // Seeded demo bidder without a provider payment: deposit treated as received offline.
                tx.executeWithoutResult(s -> recordDepositCaptured(repo.lock(dealId).orElseThrow()));
            }
            d = repo.find(dealId).orElseThrow();
        }
        if (d.state() == State.RELEASED) {
            tx.executeWithoutResult(s -> payout(repo.lock(dealId).orElseThrow()));
        }
    }

    // ------------------------------------------------------------------ buyer actions

    @Override
    public EscrowDeal payBalance(String dealId, String buyerId, String paymentMethodId) {
        Payment p = tx.execute(s -> {
            EscrowDeal d = ownDeal(repo.lock(dealId), buyerId, dealId);
            if (d.state() != State.AWAITING_BALANCE) throw new DomainException.InvalidInput("There is no balance to pay on this deal.");
            if (d.balancePaymentId() != null) {
                Payment existing = payments.find(d.balancePaymentId()).orElseThrow();
                // An earlier attempt is still in flight or succeeded: never start a second charge.
                if (existing.status() != Payment.Status.FAILED && existing.status() != Payment.Status.CANCELED) return existing;
            }
            Payment created = payments.prepare(Payment.Purpose.BALANCE, d.auctionId(), buyerId, d.balanceDueCents());
            repo.setBalancePayment(dealId, created.id());
            return created;
        });
        if (p.status() == Payment.Status.PROCESSING) {
            payments.execute(p.id(), paymentMethodId, false, "Balance for escrow " + dealId);
        }
        return repo.find(dealId).orElseThrow();
    }

    @Override
    public EscrowDeal confirmDelivery(String dealId, String buyerId) {
        tx.executeWithoutResult(s -> {
            EscrowDeal d = ownDeal(repo.lock(dealId), buyerId, dealId);
            if (d.state().ordinal() > State.FUNDED.ordinal()) return; // already released: a double click is a no-op
            if (d.state() != State.FUNDED) throw new DomainException.InvalidInput("Pay the balance before confirming delivery.");

            // Reconciliation guard: the escrow account must hold exactly the hammer price before release.
            long held = ledger.balanceOf(d.escrowAccount());
            if (held != d.hammerCents()) {
                throw new IllegalStateException("Escrow " + d.id() + " holds " + held + " but hammer is " + d.hammerCents());
            }
            ledger.post("ESCROW_RELEASED", d.id(), "Buyer accepted delivery; release escrow minus 5% fee", List.of(
                    Line.debit(d.escrowAccount(), d.hammerCents()),
                    Line.credit(d.sellerAccount(), d.sellerProceedsCents()),
                    Line.credit(PLATFORM_REVENUE, d.feeCents())));
            move(d, State.RELEASED, buyerId);
        });
        advance(dealId); // payout
        return repo.find(dealId).orElseThrow();
    }

    // ------------------------------------------------------------------ reactions to payments

    /**
     * Runs INSIDE the payments transaction that marked a payment CAPTURED (from our own call or from a
     * Stripe webhook). Ledger entry + deal transition + payment status therefore commit atomically.
     */
    @EventListener
    void onPaymentStatusChanged(PaymentStatusChanged e) {
        if (e.current() != Payment.Status.CAPTURED) return;
        EscrowDeal d = repo.lock("deal-" + e.auctionId()).orElse(null);
        if (d == null) return;
        if (e.purpose() == Payment.Purpose.DEPOSIT && e.paymentId().equals(d.depositPaymentId())) recordDepositCaptured(d);
        if (e.purpose() == Payment.Purpose.BALANCE && e.paymentId().equals(d.balancePaymentId())) recordBalanceReceived(d);
    }

    private void recordDepositCaptured(EscrowDeal d) {
        if (d.state() != State.AWAITING_DEPOSIT_CAPTURE) return;
        if (d.depositCents() > 0) {
            ledger.post("DEPOSIT_CAPTURED", d.id(), "Winner's deposit captured into escrow", List.of(
                    Line.debit(PLATFORM_CASH, d.depositCents()),
                    Line.credit(d.escrowAccount(), d.depositCents())));
        }
        if (d.depositPaymentId() == null) auctions.markRegistration(d.auctionId(), d.buyerId(), Registration.Status.APPLIED);
        move(d, State.AWAITING_BALANCE, "system");
        if (d.balanceDueCents() == 0) move(repo.find(d.id()).orElseThrow(), State.FUNDED, "system");
    }

    private void recordBalanceReceived(EscrowDeal d) {
        if (d.state() != State.AWAITING_BALANCE) return;
        ledger.post("BALANCE_RECEIVED", d.id(), "Buyer paid the remaining balance into escrow", List.of(
                Line.debit(PLATFORM_CASH, d.balanceDueCents()),
                Line.credit(d.escrowAccount(), d.balanceDueCents())));
        move(d, State.FUNDED, d.buyerId());
    }

    /**
     * Pay the seller. Simulated as a bank transfer here; with Stripe Connect this would be a Transfer to the
     * seller's connected account (exactly how marketplaces like WooPayments pay merchants).
     */
    private void payout(EscrowDeal d) {
        if (d.state() != State.RELEASED) return;
        ledger.post("SELLER_PAYOUT", d.id(), "Payout to seller " + d.sellerId(), List.of(
                Line.debit(d.sellerAccount(), d.sellerProceedsCents()),
                Line.credit(PLATFORM_CASH, d.sellerProceedsCents())));
        move(d, State.PAID_OUT, "system");
    }

    private void move(EscrowDeal d, State to, String actor) {
        if (!d.state().canMoveTo(to)) throw new IllegalStateException("Illegal transition " + d.state() + " -> " + to);
        if (!repo.transition(d.id(), d.state(), to)) throw new IllegalStateException("Concurrent change on " + d.id());
        audit.record("DEAL_" + to.name(), d.id(), actor, Map.of("from", d.state().name()));
    }

    private static EscrowDeal ownDeal(Optional<EscrowDeal> found, String buyerId, String dealId) {
        // Someone else's deal looks exactly like a missing one: never confirm that an id exists.
        return found.filter(d -> d.buyerId().equals(buyerId)).orElseThrow(() -> new DomainException.NotFound("Deal", dealId));
    }

    // ------------------------------------------------------------------ queries

    @Override
    public Optional<EscrowDeal> find(String dealId) {
        return repo.find(dealId);
    }

    @Override
    public List<EscrowDeal> dealsForBuyer(String buyerId) {
        return repo.byBuyer(buyerId);
    }
}
