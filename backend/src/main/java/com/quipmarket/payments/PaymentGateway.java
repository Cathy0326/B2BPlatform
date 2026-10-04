package com.quipmarket.payments;

import java.util.Map;

/**
 * Strategy interface over a payment provider (Simulated or Stripe).
 * Every mutating call takes an idempotency key that the provider uses to de-duplicate retries,
 * so calling it again after a timeout can never charge twice.
 */
public interface PaymentGateway {

    String name();

    /**
     * @param paymentMethodId provider token, e.g. Stripe "pm_..." from Stripe Elements, or a test token like "pm_card_visa"
     * @param captureLater    true = authorize only (a hold); false = charge now
     */
    record ChargeRequest(long amountCents, String currency, String paymentMethodId, boolean captureLater,
                         String description, Map<String, String> metadata) {
        public ChargeRequest {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record Result(String providerRef, Payment.Status status, String clientSecret) {}

    Result create(ChargeRequest request, String idempotencyKey);

    Result capture(String providerRef, String idempotencyKey);

    Result cancel(String providerRef, String idempotencyKey);

    Result retrieve(String providerRef);
}
