package com.quipmarket.auction.internal;

import com.quipmarket.auction.AuctionEngine;
import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState;
import com.quipmarket.auction.AuctionState.Status;
import com.quipmarket.auction.AuctionView;
import com.quipmarket.auction.NotRegisteredException;
import com.quipmarket.auction.Registration;
import com.quipmarket.auction.VisibleBid;
import com.quipmarket.audit.AuditTrail;
import com.quipmarket.payments.Payment;
import com.quipmarket.payments.PaymentStatusChanged;
import com.quipmarket.payments.Payments;
import com.quipmarket.shared.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

@Service
class DefaultAuctionService implements AuctionService {

    private final AuctionRepository repo;
    private final Clock clock;
    private final Payments payments;
    private final AuditTrail audit;
    private final TransactionTemplate tx;
    private final AuctionChangeNotifier notifier;

    DefaultAuctionService(AuctionRepository repo, Clock clock, Payments payments, AuditTrail audit, PlatformTransactionManager txManager,
                          AuctionChangeNotifier notifier) {
        this.notifier = notifier;
        this.repo = repo;
        this.clock = clock;
        this.payments = payments;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuctionView> list(Status status) {
        Instant now = clock.instant();
        return repo.findAll().stream()
                .map(r -> AuctionView.of(r.state(), r.bidCount(), now))
                .filter(v -> status == null || v.status() == status)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuctionView> find(String auctionId) {
        return repo.findById(auctionId).map(r -> AuctionView.of(r.state(), r.bidCount(), clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, AuctionView> latestByEquipment(Collection<String> equipmentIds) {
        Instant now = clock.instant();
        var out = new LinkedHashMap<String, AuctionView>();
        repo.findLatestByEquipmentIds(equipmentIds).forEach((eq, r) -> out.put(eq, AuctionView.of(r.state(), r.bidCount(), now)));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisibleBid> bids(String auctionId, int last) {
        return repo.lastBids(auctionId, Math.max(1, Math.min(last, 200)));
    }

    /**
     * 1. (tx) lock the lot, create the PENDING registration + a PROCESSING payment row   - record intent
     * 2. (no tx) ask the provider to AUTHORIZE the deposit (capture later)              - external call
     * 3. the payments module stores the answer and publishes PaymentStatusChanged;
     *    {@link #onPaymentStatusChanged} flips the registration to HELD (or deletes it if declined).
     * Concurrent double-clicks: the row lock + primary key mean only one call creates a payment.
     */
    @Override
    public Registration register(String auctionId, String bidderId, String paymentMethodId) {
        Registration reg = tx.execute(s -> {
            AuctionState a = repo.lockById(auctionId).orElseThrow(() -> new DomainException.NotFound("Auction", auctionId));
            if (AuctionEngine.status(a, clock.instant()) == Status.ENDED) throw new DomainException.InvalidInput("This auction has ended.");
            var existing = repo.findRegistration(auctionId, bidderId);
            if (existing.isPresent()) return existing.get();
            Payment p = payments.prepare(Payment.Purpose.DEPOSIT, auctionId, bidderId, a.depositCents());
            repo.insertRegistration(auctionId, bidderId, a.depositCents(), p.id());
            audit.record("REGISTRATION_REQUESTED", auctionId, bidderId, java.util.Map.of("depositCents", a.depositCents(), "paymentId", p.id()));
            return repo.findRegistration(auctionId, bidderId).orElseThrow();
        });

        if (reg.status() == Registration.Status.PENDING && reg.paymentId() != null
                && payments.find(reg.paymentId()).map(p -> p.status() == Payment.Status.PROCESSING).orElse(false)) {
            payments.execute(reg.paymentId(), paymentMethodId, true, "Refundable bidding deposit for auction " + auctionId);
        }
        return repo.findRegistration(auctionId, bidderId)
                .orElseThrow(() -> new DomainException.InvalidInput("The deposit authorization failed. Please try another card."));
    }

    @Override
    public Registration confirmRegistration(String auctionId, String bidderId) {
        Registration reg = repo.findRegistration(auctionId, bidderId).orElseThrow(NotRegisteredException::new);
        if (reg.status() == Registration.Status.PENDING && reg.paymentId() != null) payments.refresh(reg.paymentId());
        return repo.findRegistration(auctionId, bidderId).orElseThrow(NotRegisteredException::new);
    }

    /**
     * Reacts to payment status changes for deposits. Runs synchronously INSIDE the payments
     * transaction, so the payment and the registration always change together.
     */
    @EventListener
    void onPaymentStatusChanged(PaymentStatusChanged e) {
        if (e.purpose() != Payment.Purpose.DEPOSIT) return;
        String event;
        switch (e.current()) {
            case AUTHORIZED -> { repo.setRegistrationStatusByPayment(e.paymentId(), Registration.Status.HELD); event = "REGISTRATION_HELD"; }
            case CANCELED -> { repo.setRegistrationStatusByPayment(e.paymentId(), Registration.Status.RELEASED); event = "REGISTRATION_RELEASED"; }
            case CAPTURED -> { repo.setRegistrationStatusByPayment(e.paymentId(), Registration.Status.APPLIED); event = "REGISTRATION_APPLIED"; }
            case FAILED -> { repo.deleteRegistrationByPayment(e.paymentId()); event = "REGISTRATION_DECLINED"; } // bidder may retry
            default -> { return; }
        }
        audit.record(event, e.auctionId(), e.payerId(), java.util.Map.of("paymentId", e.paymentId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Registration> registrations(String auctionId) {
        return repo.registrations(auctionId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> endedUnsettledAuctionIds() {
        return repo.endedUnsettled(clock.instant());
    }

    @Override
    @Transactional
    public Optional<AuctionView> claimForSettlement(String auctionId) {
        Instant now = clock.instant();
        if (!repo.claimForSettlement(auctionId, now)) return Optional.empty();
        audit.record("AUCTION_SETTLEMENT_STARTED", auctionId, "system", java.util.Map.of());
        return Optional.of(view(auctionId, now));
    }

    @Override
    @Transactional
    public void markRegistration(String auctionId, String bidderId, Registration.Status status) {
        repo.setRegistrationStatus(auctionId, bidderId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Registration> heldRegistrationsInSettledAuctions() {
        return repo.heldInSettledAuctions();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Registration> registration(String auctionId, String bidderId) {
        return repo.findRegistration(auctionId, bidderId);
    }

    /**
     * One bid = one transaction:
     *   1. lock the auction row (FOR UPDATE)  - concurrent bids on this lot wait here
     *   2. check the deposit hold
     *   3. run the pure engine
     *   4. persist new state + visible bids + the raw submission (audit)
     *   5. pg_notify, delivered to every replica's subscribers only when this transaction COMMITS
     */
    @Override
    @Transactional
    public BidResult placeBid(String auctionId, String bidderId, long maxCents) {
        AuctionState current = repo.lockById(auctionId).orElseThrow(() -> new DomainException.NotFound("Auction", auctionId));
        var registration = repo.findRegistration(auctionId, bidderId).orElseThrow(NotRegisteredException::new);
        if (registration.status() == Registration.Status.PENDING) {
            throw new NotRegisteredException("Your deposit authorization is not complete yet.");
        }
        if (registration.status() != Registration.Status.HELD) throw new NotRegisteredException();

        Instant now = clock.instant();
        AuctionEngine.Outcome outcome = AuctionEngine.placeBid(current, bidderId, maxCents, now);

        if (outcome instanceof AuctionEngine.Rejected r) {
            repo.recordSubmission(auctionId, bidderId, maxCents, r.reason().name(), now);
            return new BidResult(false, r.reason(), r.minimumCents(), bidderId.equals(current.leaderId()), false, view(auctionId, now));
        }

        var a = (AuctionEngine.Accepted) outcome;
        repo.update(a.state());
        repo.insertBids(auctionId, a.newBids());
        repo.recordSubmission(auctionId, bidderId, maxCents, "ACCEPTED", now);
        audit.record("BID_ACCEPTED", auctionId, bidderId,
                java.util.Map.of("priceCents", a.state().currentPriceCents(), "leading", a.leading(), "extended", a.extended()));
        publishAfterCommit(auctionId);
        return new BidResult(true, null, null, a.leading(), a.extended(), view(auctionId, now));
    }

    @Override
    @Transactional
    public void create(AuctionState initial) {
        repo.insert(initial);
    }

    @Override
    public Flux<String> changes() {
        return notifier.changes();
    }

    private AuctionView view(String auctionId, Instant now) {
        var row = repo.findById(auctionId).orElseThrow();
        return AuctionView.of(row.state(), row.bidCount(), now);
    }

    /** pg_notify inside the transaction: PostgreSQL delivers it to every replica only if we COMMIT. */
    private void publishAfterCommit(String auctionId) {
        notifier.publish(auctionId);
    }
}
