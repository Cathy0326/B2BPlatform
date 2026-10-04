package com.quipmarket.auction.internal;

import com.quipmarket.auction.AuctionEngine;
import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState;
import com.quipmarket.auction.AuctionState.Status;
import com.quipmarket.auction.AuctionView;
import com.quipmarket.auction.NotRegisteredException;
import com.quipmarket.auction.Registration;
import com.quipmarket.auction.VisibleBid;
import com.quipmarket.shared.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
class DefaultAuctionService implements AuctionService {

    private final AuctionRepository repo;
    private final Clock clock;
    /** In-process event bus for subscriptions. (Multi-instance: swap for Postgres LISTEN/NOTIFY or Redis pub/sub.) */
    private final Sinks.Many<String> changes = Sinks.many().multicast().directBestEffort();

    DefaultAuctionService(AuctionRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuctionView> list(Status status) {
        Instant now = clock.instant();
        return repo.findAll().stream()
                .map(r -> AuctionView.of(r.state(), r.bidCount(), now))
                .filter(v -> status == null || v.status() == status)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuctionView> find(String auctionId) {
        return repo.findById(auctionId).map(r -> AuctionView.of(r.state(), r.bidCount(), clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, AuctionView> latestByEquipment(Collection<String> equipmentIds) {
        Instant now = clock.instant();
        var out = new LinkedHashMap<String, AuctionView>();
        repo.findLatestByEquipmentIds(equipmentIds).forEach((eq, r) -> out.put(eq, AuctionView.of(r.state(), r.bidCount(), now)));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VisibleBid> bids(String auctionId, int last) {
        return repo.lastBids(auctionId, Math.max(1, Math.min(last, 200)));
    }

    @Override
    @Transactional
    public Registration register(String auctionId, String bidderId) {
        AuctionState a = repo.lockById(auctionId).orElseThrow(() -> new DomainException.NotFound("Auction", auctionId));
        if (AuctionEngine.status(a, clock.instant()) == Status.ENDED) {
            throw new DomainException.InvalidInput("This auction has ended.");
        }
        return repo.register(auctionId, bidderId, a.depositCents());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Registration> registration(String auctionId, String bidderId) {
        return repo.findRegistration(auctionId, bidderId);
    }

    /**
     * One bid = one transaction:
     *   1. lock the auction row (FOR UPDATE)  - concurrent bids on this lot wait here
     *   2. check the deposit hold
     *   3. run the pure engine
     *   4. persist new state + visible bids + the raw submission (audit)
     *   5. after COMMIT, notify subscribers (never announce a bid that might still roll back)
     */
    @Override
    @Transactional
    public BidResult placeBid(String auctionId, String bidderId, long maxCents) {
        AuctionState current = repo.lockById(auctionId).orElseThrow(() -> new DomainException.NotFound("Auction", auctionId));
        if (repo.findRegistration(auctionId, bidderId).isEmpty()) throw new NotRegisteredException();

        Instant now = clock.instant();
        AuctionEngine.Outcome outcome = AuctionEngine.placeBid(current, bidderId, maxCents, now);

        if (outcome instanceof AuctionEngine.Rejected r) {
            repo.recordSubmission(auctionId, bidderId, maxCents, r.reason().name(), now);
            return new BidResult(false, r.reason(), r.minimumCents(), bidderId.equals(current.leaderId()), false, view(auctionId, now));
        }

        var a = (AuctionEngine.Accepted) outcome;
        repo.update(a.state());
        repo.insertBids(auctionId, a.newBids());
        repo.recordSubmission(auctionId, bidderId, maxCents, "ACCEPTED", now);
        publishAfterCommit(auctionId);
        return new BidResult(true, null, null, a.leading(), a.extended(), view(auctionId, now));
    }

    @Override
    @Transactional
    public void create(AuctionState initial) {
        repo.insert(initial);
    }

    @Override
    public Flux<String> changes() {
        return changes.asFlux();
    }

    private AuctionView view(String auctionId, Instant now) {
        var row = repo.findById(auctionId).orElseThrow();
        return AuctionView.of(row.state(), row.bidCount(), now);
    }

    private void publishAfterCommit(String auctionId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // busyLooping handles concurrent emitters (Sinks require serialized emission).
                changes.emitNext(auctionId, Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100)));
            }
        });
    }
}
