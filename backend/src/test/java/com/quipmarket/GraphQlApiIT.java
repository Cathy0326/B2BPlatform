package com.quipmarket;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.AuctionState;
import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.MutableClock;
import com.quipmarket.support.TestInfrastructure.QueryCounter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

/** The API contract, exercised over HTTP exactly as the Nuxt app calls it. */
@IntegrationTest
class GraphQlApiIT {

    @Autowired HttpGraphQlTester graphQl;
    @Autowired AuctionService auctions;
    @Autowired MutableClock clock;
    @Autowired QueryCounter queries;

    private HttpGraphQlTester as(String userId) {
        return graphQl.mutate().header("X-User-Id", userId).build();
    }

    private String newLot(String suffix) {
        Instant now = clock.instant();
        String id = "api-" + suffix + "-" + System.nanoTime();
        auctions.create(new AuctionState(id, "eq-1010", 65_000_000, 78_000_000L, 2_500_000, now.minusSeconds(60),
                now.plus(Duration.ofHours(2)), 120, 65_000_000, null, null, 0));
        return id;
    }

    private static String code(List<ResponseError> errors) {
        assertThat(errors).isNotEmpty();
        return String.valueOf(errors.getFirst().getExtensions().get("code"));
    }

    @Test
    void filtersEquipmentServerSide() {
        graphQl.document("{ equipment(filter: {categories: [EXCAVATOR], listing: RENT}) { id } }")
                .execute().path("equipment[*].id").entityList(String.class).containsExactly("eq-1001");

        // Word-prefix search: "cat loader" must not match the Bobcat.
        graphQl.document("{ equipment(filter: {search: \"cat loader\"}) { id } }")
                .execute().path("equipment[*].id").entityList(String.class).containsExactly("eq-1006");
    }

    @Test
    void nestedListsAreBatchedNotNPlusOne() {
        for (int i = 0; i < 3; i++) newLot("batch" + i);

        queries.reset();
        graphQl.document("{ auctions { id equipment { title } } }").execute().path("auctions").entityList(Object.class).hasSizeGreaterThan(2);
        // 1 query for auctions + 1 batched query for ALL their equipment, regardless of how many auctions.
        assertThat(queries.count()).isEqualTo(2);

        queries.reset();
        graphQl.document("{ equipment { id bookings { start } activeAuction { id } } }")
                .execute().path("equipment").entityList(Object.class).hasSize(12);
        // equipment + 1 batched bookings query + 1 batched auction query (not 1 + 12 + 12).
        assertThat(queries.count()).isEqualTo(3);
    }

    @Test
    void secretMaxIsOnlyVisibleToTheLeader() {
        String lot = newLot("secret");
        String bid = "mutation($a: ID!) { registerToBid(auctionId: $a) { status } placeBid(auctionId: $a, maxCents: 70000000) { accepted leading } }";
        as("alice").document(bid).variable("a", lot).execute().path("placeBid.accepted").entity(Boolean.class).isEqualTo(true);

        String read = "query($a: ID!) { auction(id: $a) { leaderId myMaxCents currentPriceCents } }";
        as("alice").document(read).variable("a", lot).execute().path("auction.myMaxCents").entity(Long.class).isEqualTo(70_000_000L);
        as("bob").document(read).variable("a", lot).execute().path("auction.myMaxCents").valueIsNull();
    }

    @Test
    void errorsCarryMachineReadableCodes() {
        String lot = newLot("errors");
        String bid = "mutation($a: ID!) { placeBid(auctionId: $a, maxCents: 70000000) { accepted } }";

        graphQl.document(bid).variable("a", lot).execute()
                .errors().satisfy(e -> assertThat(code(e)).isEqualTo("UNAUTHENTICATED"));
        as("erin").document(bid).variable("a", lot).execute()
                .errors().satisfy(e -> assertThat(code(e)).isEqualTo("NOT_REGISTERED"));

        String book = "mutation { createBooking(input: {equipmentId: \"eq-1001\", start: \"2026-10-08\", end: \"2026-10-10\"}) { id } }";
        as("erin").document(book).execute().errors().satisfy(e -> assertThat(code(e)).isEqualTo("BOOKING_CONFLICT"));
    }

    @Test
    void tooLowBidIsDataNotAnError() {
        String lot = newLot("low");
        var r = as("frank").document("mutation($a: ID!) { registerToBid(auctionId: $a) { status } placeBid(auctionId: $a, maxCents: 100) { accepted reason minimumCents } }")
                .variable("a", lot).execute();
        r.path("placeBid.accepted").entity(Boolean.class).isEqualTo(false);
        r.path("placeBid.reason").entity(String.class).isEqualTo("TOO_LOW");
        r.path("placeBid.minimumCents").entity(Long.class).isEqualTo(65_000_000L);
    }

    @Test
    void loanQuoteMatchesTheFrontend() {
        graphQl.document("{ loanQuote(input: {priceCents: 16450000, downPaymentCents: 3290000, aprBps: 725, termMonths: 60}) { monthlyPaymentCents schedule { month } } }")
                .execute().path("loanQuote.monthlyPaymentCents").entity(Long.class).isEqualTo(262_139L);
    }
}
