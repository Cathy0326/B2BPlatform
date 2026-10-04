package com.quipmarket.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.Lots;
import com.quipmarket.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

@IntegrationTest
class AdminAndLimitsIT {

    @Autowired HttpGraphQlTester graphQl;
    @Autowired AuctionService auctions;
    @Autowired MutableClock clock;

    @Test
    void ledgerAndAuditRequireTheAdminRole() {
        graphQl.mutate().header("X-User-Id", "buyer1").build()
                .document("{ trialBalance { balanced } }").execute().errors()
                .satisfy(e -> assertThat(e.getFirst().getExtensions().get("code")).isEqualTo("FORBIDDEN"));
        graphQl.document("{ verifyAuditChain { valid } }").execute().errors()
                .satisfy(e -> assertThat(e.getFirst().getExtensions().get("code")).isEqualTo("UNAUTHENTICATED"));

        var admin = graphQl.mutate().header("X-User-Id", "ops1").header("X-User-Roles", "admin").build();
        admin.document("{ trialBalance { balanced } verifyAuditChain { valid } }").execute()
                .path("trialBalance.balanced").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void meReturnsThePublicIdAndRoles() {
        graphQl.mutate().header("X-User-Id", "zoe").header("X-User-Roles", "admin").build()
                .document("{ me { id roles } }").execute()
                .path("me.id").entity(String.class).isEqualTo("zoe")
                .path("me.roles").entityList(String.class).containsExactly("admin");
        graphQl.document("{ me { id } }").execute().path("me").valueIsNull();
    }

    @Test
    void absurdlyDeepQueriesAreRejectedBeforeExecution() {
        // auction -> equipment -> activeAuction -> equipment -> ... is a cycle in the schema.
        String deep = "{ auctions { equipment { activeAuction { equipment { activeAuction { equipment { activeAuction {"
                + " equipment { activeAuction { equipment { title } } } } } } } } } } }";
        graphQl.document(deep).execute().errors()
                .satisfy(e -> assertThat(e.getFirst().getMessage()).containsIgnoringCase("depth"));
    }

    @Test
    void bidNotificationsTravelThroughPostgresListenNotify() {
        String lot = Lots.live(auctions, clock, "notify", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        auctions.register(lot, "nina", null);
        var received = auctions.changes().filter(lot::equals).next().toFuture();
        auctions.placeBid(lot, "nina", 16_000_000);
        // Delivered only after COMMIT, by PostgreSQL, to the listener thread, then to the Reactor sink.
        assertThat(received.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join()).isEqualTo(lot);
    }
}
