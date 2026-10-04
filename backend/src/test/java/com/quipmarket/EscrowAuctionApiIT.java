package com.quipmarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.Lots;
import com.quipmarket.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

/** Escrow and auction operations over HTTP, as the Nuxt app calls them, including who may see what. */
@IntegrationTest
class EscrowAuctionApiIT {

    static final String DEAL_FIELDS = "id auctionId state buyerId equipment { title } depositPayment { status } balancePayment { status } journal { kind }";

    @Autowired HttpGraphQlTester graphQl;
    @Autowired AuctionService auctions;
    @Autowired MutableClock clock;

    private Duration moved = Duration.ZERO;

    @AfterEach
    void resetClock() {
        clock.advance(moved.negated());
        moved = Duration.ZERO;
    }

    HttpGraphQlTester as(String userId, String... roles) {
        var b = graphQl.mutate().header("X-User-Id", userId);
        if (roles.length > 0) b.header("X-User-Roles", String.join(",", roles));
        return b.build();
    }

    static String user(String prefix) {
        return prefix + "-" + ThreadLocalRandom.current().nextInt(1_000_000);
    }

    static String code(List<ResponseError> errors) {
        assertThat(errors).isNotEmpty();
        return String.valueOf(errors.getFirst().getExtensions().get("code"));
    }

    /** A lot the buyer has won; the clock is past its end. */
    String wonLot(String buyer) {
        String lot = Lots.live(auctions, clock, "api-esc", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        auctions.register(lot, buyer, null);
        auctions.placeBid(lot, buyer, 16_000_000);
        clock.advance(Duration.ofHours(1));
        moved = moved.plus(Duration.ofHours(1));
        return lot;
    }

    @Test
    void onlyAdminsCanSettleAndSettlingTwiceReturnsTheSameDeal() {
        String buyer = user("buyer");
        String lot = wonLot(buyer);

        assertThat(code(as(buyer).document("mutation($a: ID!) { settleAuction(auctionId: $a) { id } }").variable("a", lot)
                .execute().returnResponse().getErrors())).isEqualTo("FORBIDDEN");

        String first = as("ops", "admin").document("mutation($a: ID!) { settleAuction(auctionId: $a) { id } }").variable("a", lot)
                .execute().path("settleAuction.id").entity(String.class).get();
        String again = as("ops", "admin").document("mutation($a: ID!) { settleAuction(auctionId: $a) { id } }").variable("a", lot)
                .execute().path("settleAuction.id").entity(String.class).get();
        assertThat(again).isEqualTo(first);
    }

    @Test
    void buyersSeeOnlyTheirOwnDealsAndCanCompleteThemOverTheApi() {
        String buyer = user("buyer");
        String lot = wonLot(buyer);
        String dealId = as("ops", "admin").document("mutation($a: ID!) { settleAuction(auctionId: $a) { id } }").variable("a", lot)
                .execute().path("settleAuction.id").entity(String.class).get();

        var mine = as(buyer).document("{ myDeals { " + DEAL_FIELDS + " } }").execute();
        mine.path("myDeals[0].id").entity(String.class).isEqualTo(dealId);
        mine.path("myDeals[0].equipment.title").entity(String.class).satisfies(t -> assertThat(t).isNotBlank());
        mine.path("myDeals[0].depositPayment.status").entity(String.class).isEqualTo("CAPTURED");
        mine.path("myDeals[0].balancePayment").valueIsNull();
        mine.path("myDeals[0].journal[*].kind").entityList(String.class).contains("DEPOSIT_CAPTURED");

        graphQl.document("{ myDeals { id } }").execute().path("myDeals").entityList(Object.class).hasSize(0); // anonymous
        as(user("stranger")).document("query($id: ID!) { deal(id: $id) { id } }").variable("id", dealId)
                .execute().path("deal").valueIsNull(); // someone else's deal looks like no deal at all

        as(buyer).mutate().header("Idempotency-Key", "pay-" + dealId).build()
                .document("mutation($d: ID!) { payBalance(dealId: $d) { state } }").variable("d", dealId)
                .execute().path("payBalance.state").entity(String.class).isEqualTo("FUNDED");
        as(buyer).document("mutation($d: ID!) { confirmDelivery(dealId: $d) { state balancePayment { status } } }").variable("d", dealId)
                .execute().path("confirmDelivery.state").entity(String.class).isEqualTo("PAID_OUT")
                .path("confirmDelivery.balancePayment.status").entity(String.class).isEqualTo("CAPTURED");
        as(buyer).document("query($id: ID!) { deal(id: $id) { state } }").variable("id", dealId)
                .execute().path("deal.state").entity(String.class).isEqualTo("PAID_OUT");
    }

    @Test
    void registrationShowsPaymentProgressAndHonoursTheIdempotencyKey() {
        String lot = Lots.live(auctions, clock, "api-reg", "eq-1010", 65_000_000, null, 2_500_000, Duration.ofHours(2));
        String bidder = user("bidder");
        String query = "query($a: ID!) { myRegistration(auctionId: $a) { status paymentStatus clientSecret createdAt } }";
        String register = "mutation($a: ID!, $pm: String) { registerToBid(auctionId: $a, paymentMethodId: $pm) { status paymentStatus clientSecret createdAt } }";

        graphQl.document(query).variable("a", lot).execute().path("myRegistration").valueIsNull(); // anonymous

        var withKey = as(bidder).mutate().header("Idempotency-Key", "reg-" + bidder).build();
        var first = withKey.document(register).variable("a", lot).variable("pm", "pm_card_authenticationRequired").execute();
        first.path("registerToBid.status").entity(String.class).isEqualTo("PENDING");
        first.path("registerToBid.paymentStatus").entity(String.class).isEqualTo("REQUIRES_ACTION");
        first.path("registerToBid.clientSecret").entity(String.class).satisfies(s -> assertThat(s).isNotBlank()); // for Stripe.js 3-D Secure
        String createdAt = first.path("registerToBid.createdAt").entity(String.class).get();
        withKey.document(register).variable("a", lot).variable("pm", "pm_card_authenticationRequired").execute()
                .path("registerToBid.createdAt").entity(String.class).isEqualTo(createdAt); // replayed, not re-created

        as(bidder).document("mutation($a: ID!) { confirmRegistration(auctionId: $a) { status } }").variable("a", lot)
                .execute().path("confirmRegistration.status").entity(String.class).isEqualTo("PENDING"); // bank has not confirmed yet

        String other = user("bidder");
        as(other).document(register).variable("a", lot).variable("pm", "pm_card_visa").execute()
                .path("registerToBid.paymentStatus").entity(String.class).isEqualTo("AUTHORIZED")
                .path("registerToBid.clientSecret").valueIsNull();
        as(other).document(query).variable("a", lot).execute().path("myRegistration.status").entity(String.class).isEqualTo("HELD");
    }

    @Test
    void auctionQueriesAndBidValidation() {
        String lot = Lots.live(auctions, clock, "api-q", "eq-1010", 65_000_000, null, 2_500_000, Duration.ofHours(2));
        String bidder = user("bidder");
        auctions.register(lot, bidder, null);
        auctions.placeBid(lot, bidder, 70_000_000);

        graphQl.document("{ auctions(status: LIVE) { id status } }").execute()
                .path("auctions[*].status").entityList(String.class).satisfies(statuses -> assertThat(statuses).containsOnly("LIVE"))
                .path("auctions[*].id").entityList(String.class).contains(lot);
        graphQl.document("query($a: ID!) { auction(id: $a) { bids(last: 1) { amountCents } } }").variable("a", lot)
                .execute().path("auction.bids").entityList(Map.class).hasSize(1);

        assertThat(code(as(bidder).document("mutation($a: ID!) { placeBid(auctionId: $a, maxCents: 0) { accepted } }").variable("a", lot)
                .execute().returnResponse().getErrors())).isEqualTo("INVALID_INPUT");
        assertThat(code(as(bidder).document("mutation { placeBid(auctionId: \"no-such-lot\", maxCents: 100) { accepted } }")
                .execute().returnResponse().getErrors())).isEqualTo("NOT_FOUND");
        assertThat(code(graphQl.document("mutation($a: ID!) { confirmRegistration(auctionId: $a) { status } }").variable("a", lot)
                .execute().returnResponse().getErrors())).isEqualTo("UNAUTHENTICATED");
    }
}
