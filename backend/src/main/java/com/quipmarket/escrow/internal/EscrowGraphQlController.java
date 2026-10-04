package com.quipmarket.escrow.internal;

import com.quipmarket.catalog.Catalog;
import com.quipmarket.catalog.Equipment;
import com.quipmarket.escrow.Escrow;
import com.quipmarket.escrow.EscrowDeal;
import com.quipmarket.ledger.Ledger;
import com.quipmarket.payments.Payment;
import com.quipmarket.payments.Payments;
import com.quipmarket.shared.CurrentUser;
import com.quipmarket.shared.Idempotency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
class EscrowGraphQlController {

    private final Escrow escrow;
    private final Catalog catalog;
    private final Ledger ledger;
    private final Payments payments;
    private final Idempotency idempotency;

    EscrowGraphQlController(Escrow escrow, Catalog catalog, Ledger ledger, Payments payments, Idempotency idempotency) {
        this.escrow = escrow;
        this.catalog = catalog;
        this.ledger = ledger;
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @QueryMapping
    List<EscrowDeal> myDeals(@ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return userId == null ? List.of() : escrow.dealsForBuyer(userId);
    }

    @QueryMapping
    EscrowDeal deal(@Argument String id, @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return escrow.find(id).filter(d -> d.buyerId().equals(userId)).orElse(null);
    }

    /** Moves money, so an Idempotency-Key is REQUIRED (a retried click must never charge twice). */
    @MutationMapping
    EscrowDeal payBalance(@Argument String dealId, @Argument String paymentMethodId,
                          @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
                          @ContextValue(name = Idempotency.CONTEXT_KEY, required = false) String key) {
        String user = CurrentUser.require(userId);
        return idempotency.run(user, key, "payBalance", Map.of("dealId", dealId), EscrowDeal.class,
                () -> escrow.payBalance(dealId, user, paymentMethodId));
    }

    @MutationMapping
    EscrowDeal confirmDelivery(@Argument String dealId,
                               @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return escrow.confirmDelivery(dealId, CurrentUser.require(userId));
    }

    /** Ops trigger: settle an ended auction now instead of waiting for the job. ADMIN only. */
    @MutationMapping
    EscrowDeal settleAuction(@Argument String auctionId, @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId,
            @ContextValue(name = CurrentUser.ROLES_KEY, required = false) List<String> roles) {
        CurrentUser.requireAdmin(userId, roles);
        return escrow.settle(auctionId).orElseGet(() -> escrow.find("deal-" + auctionId).orElse(null));
    }

    @BatchMapping(typeName = "EscrowDeal")
    Map<EscrowDeal, Equipment> equipment(List<EscrowDeal> deals) {
        var byId = catalog.findByIds(deals.stream().map(EscrowDeal::equipmentId).distinct().toList());
        var out = new LinkedHashMap<EscrowDeal, Equipment>();
        deals.forEach(d -> out.put(d, byId.get(d.equipmentId())));
        return out;
    }

    @SchemaMapping(typeName = "EscrowDeal")
    List<Ledger.Entry> journal(EscrowDeal deal) {
        return ledger.entries(deal.id(), 20);
    }

    @SchemaMapping(typeName = "EscrowDeal")
    Payment depositPayment(EscrowDeal deal) {
        return deal.depositPaymentId() == null ? null : payments.find(deal.depositPaymentId()).orElse(null);
    }

    @SchemaMapping(typeName = "EscrowDeal")
    Payment balancePayment(EscrowDeal deal) {
        return deal.balancePaymentId() == null ? null : payments.find(deal.balancePaymentId()).orElse(null);
    }
}
