package com.quipmarket.payments;

import java.time.Instant;

/** One attempt to move money through a payment provider. */
public record Payment(
        String id,
        String provider,
        String providerRef,
        Purpose purpose,
        String auctionId,
        String payerId,
        long amountCents,
        String currency,
        Status status,
        String clientSecret,
        Instant createdAt,
        Instant updatedAt) {

    public enum Purpose { DEPOSIT, BALANCE }

    /**
     * PROCESSING      row written, provider not answered yet
     * REQUIRES_ACTION the payer must finish authentication (3-D Secure) using clientSecret
     * AUTHORIZED      funds held on the card, not taken (deposit holds)
     * CAPTURED        money taken
     * CANCELED        hold released / payment voided
     * FAILED          declined
     */
    public enum Status {
        PROCESSING, REQUIRES_ACTION, AUTHORIZED, CAPTURED, CANCELED, FAILED;

        /** Allowed transitions. Webhooks can arrive late or out of order; never move backwards. */
        public boolean canMoveTo(Status next) {
            return switch (this) {
                case PROCESSING, REQUIRES_ACTION -> next != PROCESSING && next != this;
                case AUTHORIZED -> next == CAPTURED || next == CANCELED;
                case CAPTURED, CANCELED, FAILED -> false;
            };
        }
    }
}
