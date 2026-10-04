package com.quipmarket.auction;

import java.time.Instant;

/** A bidder's refundable deposit hold for one auction. Phase 3 backs it with the ledger and Stripe. */
public record Registration(String auctionId, String bidderId, long depositCents, Status status, Instant createdAt) {
    public enum Status { HELD, RELEASED, APPLIED }
}
