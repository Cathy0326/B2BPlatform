package com.quipmarket.escrow;

import java.util.List;
import java.util.Optional;

/** Public API of the escrow module. */
public interface Escrow {

    /** Platform fee charged to the seller: 5.00% of the hammer price. */
    int FEE_BPS = 500;

    /** Integer-cent fee, rounded half-up. */
    static long platformFee(long hammerCents) {
        return Math.addExact(Math.multiplyExact(hammerCents, FEE_BPS), 5_000) / 10_000;
    }

    /** Settle one ended auction: create the deal for the winner, release losing deposit holds. */
    Optional<EscrowDeal> settle(String auctionId);

    /** Job entry point: settle all ended auctions, then retry any unfinished steps (saga recovery). */
    void runPendingWork();

    EscrowDeal payBalance(String dealId, String buyerId, String paymentMethodId);

    EscrowDeal confirmDelivery(String dealId, String buyerId);

    Optional<EscrowDeal> find(String dealId);

    List<EscrowDeal> dealsForBuyer(String buyerId);
}
