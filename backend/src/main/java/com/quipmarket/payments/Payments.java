package com.quipmarket.payments;

import java.util.Optional;

/**
 * Public API of the payments module. The pattern for every provider call is:
 *   1. {@link #prepare} - record the intent (status PROCESSING) inside the caller's DB transaction
 *   2. {@link #execute} - call the provider OUTSIDE any DB transaction, with idempotency key "payment:{id}"
 *   3. store the provider's answer in a short new transaction and publish {@link PaymentStatusChanged}
 * A crash between 2 and 3 is repaired by {@link #refresh}: calling the provider again with the same key
 * returns the same result instead of charging twice.
 */
public interface Payments {

    /** Stripe's maximum for a single USD card payment ($999,999.99). Larger B2B amounts go by wire. */
    long MAX_CARD_AMOUNT_CENTS = 99_999_999L;

    Payment prepare(Payment.Purpose purpose, String auctionId, String payerId, long amountCents);

    Payment execute(String paymentId, String paymentMethodId, boolean captureLater, String description);

    Payment capture(String paymentId);

    Payment cancel(String paymentId);

    /** Re-read status from the provider (e.g. after the payer finished 3-D Secure). */
    Payment refresh(String paymentId);

    /** Apply a status reported by a provider webhook (idempotent; ignores backwards moves). */
    void applyProviderStatus(String providerRef, Payment.Status status);

    Optional<Payment> find(String paymentId);

    String gatewayName();
}
