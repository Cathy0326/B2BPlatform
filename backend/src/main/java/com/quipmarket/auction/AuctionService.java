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

    Registration register(String auctionId, String bidderId);

    Optional<Registration> registration(String auctionId, String bidderId);

    BidResult placeBid(String auctionId, String bidderId, long maxCents);

    void create(AuctionState initial);

    /** Ids of auctions whose state changed, emitted after the change is committed. */
    Flux<String> changes();
}
