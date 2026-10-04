package com.quipmarket.auction;

import java.time.Instant;

/**
 * A bidder's refundable deposit hold for one auction.
 *   PENDING   card authorization not finished (processing, or the payer must complete 3-D Secure)
 *   HELD      deposit authorized: the bidder may bid
 *   RELEASED  hold canceled (lost the auction, or it ended unsold)
 *   APPLIED   winner's deposit captured into escrow
 * paymentId is null for seeded demo bidders, who never touch a payment provider.
 */
public record Registration(String auctionId, String bidderId, long depositCents, Status status, Instant createdAt,
                           String paymentId) {
    public enum Status { PENDING, HELD, RELEASED, APPLIED }
}
