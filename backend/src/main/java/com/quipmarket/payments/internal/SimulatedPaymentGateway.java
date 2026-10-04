package com.quipmarket.payments.internal;

import com.quipmarket.payments.Payment.Status;
import com.quipmarket.payments.PaymentDeclinedException;
import com.quipmarket.payments.PaymentGateway;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory provider for tests, offline demos, and CI. It deliberately mimics Stripe's behaviour,
 * including Stripe's test payment-method tokens, so switching to Stripe changes nothing upstream:
 *   pm_card_visa (or null)           -> success
 *   pm_card_chargeDeclined           -> declined (generic_decline)
 *   pm_card_insufficientFunds        -> declined (insufficient_funds)
 *   pm_card_authenticationRequired   -> REQUIRES_ACTION (3-D Secure)
 * Idempotency keys are honoured exactly like Stripe: same key -> same result, no second charge.
 */
class SimulatedPaymentGateway implements PaymentGateway {

    private final Map<String, Status> intents = new ConcurrentHashMap<>();
    private final Map<String, Result> byIdempotencyKey = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "SIMULATED";
    }

    @Override
    public Result create(ChargeRequest r, String idempotencyKey) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, k -> {
            String pm = r.paymentMethodId() == null ? "pm_card_visa" : r.paymentMethodId();
            switch (pm) {
                case "pm_card_chargeDeclined" -> throw new PaymentDeclinedException("Your card was declined.", "generic_decline");
                case "pm_card_insufficientFunds" -> throw new PaymentDeclinedException("Your card has insufficient funds.", "insufficient_funds");
                default -> { }
            }
            String ref = "sim_pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            Status status = pm.equals("pm_card_authenticationRequired") ? Status.REQUIRES_ACTION
                    : r.captureLater() ? Status.AUTHORIZED : Status.CAPTURED;
            intents.put(ref, status);
            return new Result(ref, status, status == Status.REQUIRES_ACTION ? ref + "_secret_sim" : null);
        });
    }

    @Override
    public Result capture(String ref, String idempotencyKey) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, k -> {
            intents.computeIfPresent(ref, (id, s) -> {
                if (s != Status.AUTHORIZED && s != Status.CAPTURED) throw new IllegalStateException("Cannot capture " + s);
                return Status.CAPTURED;
            });
            return new Result(ref, intents.get(ref), null);
        });
    }

    @Override
    public Result cancel(String ref, String idempotencyKey) {
        return byIdempotencyKey.computeIfAbsent(idempotencyKey, k -> {
            intents.computeIfPresent(ref, (id, s) -> {
                if (s == Status.CAPTURED) throw new IllegalStateException("Cannot cancel a captured payment (refund instead)");
                return Status.CANCELED;
            });
            return new Result(ref, intents.get(ref), null);
        });
    }

    @Override
    public Result retrieve(String ref) {
        return new Result(ref, intents.getOrDefault(ref, Status.FAILED), null);
    }

    /** Test/demo hook: simulate the payer completing 3-D Secure. */
    void completeAuthentication(String ref) {
        intents.computeIfPresent(ref, (id, s) -> s == Status.REQUIRES_ACTION ? Status.AUTHORIZED : s);
    }
}
