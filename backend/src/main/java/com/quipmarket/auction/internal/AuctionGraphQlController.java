package com.quipmarket.auction.internal;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState.Status;
import com.quipmarket.auction.AuctionView;
import com.quipmarket.auction.Registration;
import com.quipmarket.auction.VisibleBid;
import com.quipmarket.catalog.Catalog;
import com.quipmarket.catalog.Equipment;
import com.quipmarket.shared.CurrentUser;
import com.quipmarket.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Controller
class AuctionGraphQlController {

    private final AuctionService auctions;
    private final Catalog catalog;

    AuctionGraphQlController(AuctionService auctions, Catalog catalog) {
        this.auctions = auctions;
        this.catalog = catalog;
    }

    // ---------- queries ----------

    @QueryMapping
    List<AuctionView> auctions(@Argument Status status) {
        return auctions.list(status);
    }

    @QueryMapping
    AuctionView auction(@Argument String id) {
        return auctions.find(id).orElse(null);
    }

    @QueryMapping
    Registration myRegistration(@Argument String auctionId,
                                @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return userId == null ? null : auctions.registration(auctionId, userId).orElse(null);
    }

    // ---------- mutations ----------

    @MutationMapping
    Registration registerToBid(@Argument String auctionId,
                               @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return auctions.register(auctionId, CurrentUser.require(userId));
    }

    @MutationMapping
    AuctionService.BidResult placeBid(@Argument String auctionId, @Argument long maxCents,
                                      @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        if (maxCents <= 0) throw new DomainException.InvalidInput("maxCents must be positive");
        return auctions.placeBid(auctionId, CurrentUser.require(userId), maxCents);
    }

    // ---------- subscription ----------

    /**
     * Every committed change to this auction is pushed to subscribers.
     * The first element is the current state, so a client that subscribes late is never stale.
     * DB reads are blocking, so they run on boundedElastic, never on a Reactor event-loop thread.
     */
    @SubscriptionMapping
    Flux<AuctionView> auctionUpdated(@Argument String auctionId) {
        Flux<String> ticks = Flux.concat(Mono.just(auctionId), auctions.changes().filter(auctionId::equals));
        return ticks.concatMap(id -> Mono.fromCallable(() -> auctions.find(id).orElse(null)).subscribeOn(Schedulers.boundedElastic()));
    }

    // ---------- field resolvers ----------

    /** The secret max is only revealed to its owner. */
    @SchemaMapping(typeName = "Auction")
    Long myMaxCents(AuctionView auction, @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return userId != null && userId.equals(auction.leaderId()) ? auction.leaderMaxCents() : null;
    }

    @SchemaMapping(typeName = "Auction")
    List<VisibleBid> bids(AuctionView auction, @Argument Integer last) {
        return auctions.bids(auction.id(), last == null ? 20 : last);
    }

    /** N auctions -> 1 equipment query (DataLoader under the hood). */
    @BatchMapping(typeName = "Auction")
    Map<AuctionView, Equipment> equipment(List<AuctionView> list) {
        var byId = catalog.findByIds(list.stream().map(AuctionView::equipmentId).distinct().toList());
        var out = new LinkedHashMap<AuctionView, Equipment>();
        list.forEach(a -> out.put(a, byId.get(a.equipmentId())));
        return out;
    }

    /** N equipment -> 1 auction query. */
    @BatchMapping(typeName = "Equipment", field = "activeAuction")
    Map<Equipment, AuctionView> activeAuction(List<Equipment> equipment) {
        var byEq = auctions.latestByEquipment(equipment.stream().map(Equipment::id).toList());
        var out = new LinkedHashMap<Equipment, AuctionView>();
        equipment.forEach(e -> out.put(e, byEq.get(e.id())));
        return out;
    }
}
