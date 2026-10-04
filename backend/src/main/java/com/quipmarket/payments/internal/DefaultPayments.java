package com.quipmarket.payments.internal;

import com.quipmarket.audit.AuditTrail;
import com.quipmarket.payments.Payment;
import com.quipmarket.payments.PaymentDeclinedException;
import com.quipmarket.payments.PaymentGateway;
import com.quipmarket.payments.PaymentStatusChanged;
import com.quipmarket.payments.Payments;
import com.quipmarket.shared.DomainException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class DefaultPayments implements Payments {

    private static final Logger log = LoggerFactory.getLogger(DefaultPayments.class);

    private final PaymentRepository repo;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final TransactionTemplate tx;

    DefaultPayments(PaymentRepository repo, PaymentGateway gateway, ApplicationEventPublisher events, AuditTrail audit,
                    PlatformTransactionManager txManager) {
        this.repo = repo;
        this.gateway = gateway;
        this.events = events;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public String gatewayName() {
        return gateway.name();
    }

    @Override
    @Transactional
    public Payment prepare(Payment.Purpose purpose, String auctionId, String payerId, long amountCents) {
        if (amountCents <= 0) throw new DomainException.InvalidInput("Amount must be positive.");
        if (amountCents > MAX_CARD_AMOUNT_CENTS) {
            throw new DomainException.InvalidInput("Amounts above $999,999.99 cannot be paid by card. Please pay by wire transfer.");
        }
        Instant now = Instant.now();
        var p = new Payment("pay_" + UUID.randomUUID().toString().replace("-", ""), gateway.name(), null, purpose, auctionId,
                payerId, amountCents, "usd", Payment.Status.PROCESSING, null, now, now);
        repo.insert(p);
        return p;
    }

    /** Never call inside a DB transaction: the provider round-trip must not hold locks or connections. */
    @Override
    public Payment execute(String paymentId, String paymentMethodId, boolean captureLater, String description) {
        Payment p = find(paymentId).orElseThrow(() -> new DomainException.NotFound("Payment", paymentId));
        if (p.status() != Payment.Status.PROCESSING) return p; // already executed (a retry)
        var request = new PaymentGateway.ChargeRequest(p.amountCents(), p.currency(), paymentMethodId, captureLater, description,
                Map.of("payment_id", p.id(), "auction_id", p.auctionId(), "purpose", p.purpose().name()));
        try {
            var result = gateway.create(request, "payment:" + p.id());
            return apply(p.id(), result);
        } catch (PaymentDeclinedException e) {
            apply(p.id(), new PaymentGateway.Result(null, Payment.Status.FAILED, null));
            throw e;
        }
    }

    @Override
    public Payment capture(String paymentId) {
        Payment p = find(paymentId).orElseThrow(() -> new DomainException.NotFound("Payment", paymentId));
        if (p.status() == Payment.Status.CAPTURED) return p;
        if (p.status() != Payment.Status.AUTHORIZED) throw new IllegalStateException("Cannot capture payment in status " + p.status());
        return apply(p.id(), gateway.capture(p.providerRef(), "capture:" + p.id()));
    }

    @Override
    public Payment cancel(String paymentId) {
        Payment p = find(paymentId).orElseThrow(() -> new DomainException.NotFound("Payment", paymentId));
        if (p.status() == Payment.Status.CANCELED || p.status() == Payment.Status.FAILED) return p;
        if (p.providerRef() == null) return apply(p.id(), new PaymentGateway.Result(null, Payment.Status.CANCELED, null));
        return apply(p.id(), gateway.cancel(p.providerRef(), "cancel:" + p.id()));
    }

    @Override
    public Payment refresh(String paymentId) {
        Payment p = find(paymentId).orElseThrow(() -> new DomainException.NotFound("Payment", paymentId));
        if (p.providerRef() == null) return p;
        return apply(p.id(), gateway.retrieve(p.providerRef()));
    }

    @Override
    public void applyProviderStatus(String providerRef, Payment.Status status) {
        tx.executeWithoutResult(s -> repo.lockByProviderRef(providerRef).ifPresentOrElse(
                p -> transition(p, providerRef, status, null),
                () -> log.warn("Webhook for unknown payment {}", providerRef)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> find(String paymentId) {
        return repo.find(paymentId);
    }

    /** Store the provider's answer: lock the row, move forward only, audit, publish. */
    private Payment apply(String paymentId, PaymentGateway.Result r) {
        return tx.execute(s -> {
            Payment p = repo.lock(paymentId).orElseThrow();
            transition(p, r.providerRef(), r.status(), r.clientSecret());
            return repo.find(paymentId).orElseThrow();
        });
    }

    private void transition(Payment p, String providerRef, Payment.Status next, String clientSecret) {
        if (p.status() == next) {
            if (providerRef != null && p.providerRef() == null) repo.update(p.id(), providerRef, next, clientSecret);
            return;
        }
        if (!p.status().canMoveTo(next)) {
            log.info("Ignoring {} -> {} for {} (late or out-of-order event)", p.status(), next, p.id());
            return;
        }
        repo.update(p.id(), providerRef, next, next == Payment.Status.REQUIRES_ACTION ? clientSecret : null);
        audit.record("PAYMENT_" + next.name(), p.id(), p.payerId(),
                Map.of("purpose", p.purpose().name(), "auctionId", p.auctionId(), "amountCents", p.amountCents(),
                        "provider", p.provider(), "from", p.status().name()));
        events.publishEvent(new PaymentStatusChanged(p.id(), p.purpose(), p.auctionId(), p.payerId(), p.status(), next));
    }
}
