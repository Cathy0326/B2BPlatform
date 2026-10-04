package com.quipmarket.escrow;

import java.time.Instant;

/**
 * Money from a won auction, held by the platform until the buyer accepts the machine.
 *
 *   AWAITING_DEPOSIT_CAPTURE --capture winner's deposit--> AWAITING_BALANCE
 *   AWAITING_BALANCE         --buyer pays the rest------> FUNDED
 *   FUNDED                   --buyer confirms delivery--> RELEASED   (seller payable - platform fee)
 *   RELEASED                 --bank payout to seller----> PAID_OUT
 */
public record EscrowDeal(
        String id,
        String auctionId,
        String equipmentId,
        String buyerId,
        String sellerId,
        long hammerCents,
        long depositCents,
        long feeCents,
        String depositPaymentId,
        String balancePaymentId,
        State state,
        Instant createdAt,
        Instant updatedAt) {

    public enum State {
        AWAITING_DEPOSIT_CAPTURE, AWAITING_BALANCE, FUNDED, RELEASED, PAID_OUT;

        public boolean canMoveTo(State next) {
            return next.ordinal() == ordinal() + 1; // strictly forward, one step at a time
        }
    }

    public long balanceDueCents() {
        return hammerCents - depositCents;
    }

    public long sellerProceedsCents() {
        return hammerCents - feeCents;
    }

    public String escrowAccount() {
        return "escrow:" + id;
    }

    public String sellerAccount() {
        return "seller_payable:" + sellerId;
    }
}
