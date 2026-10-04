package com.quipmarket.auction;

import com.quipmarket.auction.AuctionState.Status;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Flux;

/** Public API of the auction module. */
public interface AuctionService {

    record BidResult(boolean accepted, AuctionEngine.Rejection reason, Long minimumCents, boolean leading, boolean extended,
                     AuctionView auction) {}

    List<AuctionView> list(Status status);

    Optional<AuctionView> find(String auctionId);

    Map<String, AuctionView> latestByEquipment(Collection<String> equipmentIds);

    List<VisibleBid> bids(String auctionId, int last);

    /**
     * Places the refundable deposit hold (via the payments module) and registers the bidder.
     * paymentMethodId: Stripe "pm_..." from Stripe Elements, a Stripe test token, or null (= test card).
     */
    Registration register(String auctionId, String bidderId, String paymentMethodId);

    /** Re-check the deposit authorization with the provider (e.g. after 3-D Secure). */
    Registration confirmRegistration(String auctionId, String bidderId);

    List<Registration> registrations(String auctionId);

    // ---- settlement hooks (used by the escrow module) ----

    /** Ended auctions not yet claimed for settlement. */
    List<String> endedUnsettledAuctionIds();

    /** Atomically claims the auction for settlement. Empty if another worker already did, or it has not ended. */
    Optional<AuctionView> claimForSettlement(String auctionId);

    /** For seeded bidders without a payment: mark the registration directly. */
    void markRegistration(String auctionId, String bidderId, Registration.Status status);

    /** Registrations still HELD in auctions that were already settled (holds to release, retried by the escrow job). */
    List<Registration> heldRegistrationsInSettledAuctions();

    Optional<Registration> registration(String auctionId, String bidderId);

    BidResult placeBid(String auctionId, String bidderId, long maxCents);

    void create(AuctionState initial);

    /** Ids of auctions whose state changed, emitted after the change is committed. */
    Flux<String> changes();
}
