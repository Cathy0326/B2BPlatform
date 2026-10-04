package com.quipmarket.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quipmarket.auction.AuctionService;
import com.quipmarket.auction.Registration;
import com.quipmarket.escrow.Escrow;
import com.quipmarket.ledger.Ledger;
import com.quipmarket.support.IntegrationTest;
import com.quipmarket.support.Lots;
import com.quipmarket.support.MutableClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class IdempotencyAndWebhookIT {

    @Autowired HttpGraphQlTester graphQl;
    @Autowired MockMvc mvc;
    @Autowired AuctionService auctions;
    @Autowired Escrow escrow;
    @Autowired Payments payments;
    @Autowired Ledger ledger;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    private String fundedDealAwaitingBalance(String buyer) {
        String lot = Lots.live(auctions, clock, "idem", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        auctions.register(lot, buyer, null);
        auctions.placeBid(lot, buyer, 16_000_000);
        clock.advance(Duration.ofHours(1));
        try {
            return escrow.settle(lot).orElseThrow().id();
        } finally {
            clock.advance(Duration.ofHours(-1));
        }
    }

    private static final String PAY = "mutation($d: ID!) { payBalance(dealId: $d) { id state balanceDueCents } }";

    @Test
    void payBalanceRequiresAKeyAndRetriesWithTheSameKeyChargeOnce() {
        String deal = fundedDealAwaitingBalance("gina");
        var gina = graphQl.mutate().header("X-User-Id", "gina").build();

        gina.document(PAY).variable("d", deal).execute().errors()
                .satisfy(e -> assertThat(e.getFirst().getExtensions().get("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));

        var withKey = graphQl.mutate().header("X-User-Id", "gina").header("Idempotency-Key", "click-" + deal).build();
        withKey.document(PAY).variable("d", deal).execute().path("payBalance.state").entity(String.class).isEqualTo("FUNDED");
        // The client timed out and retries with the SAME key: same answer, no second charge.
        withKey.document(PAY).variable("d", deal).execute().path("payBalance.state").entity(String.class).isEqualTo("FUNDED");

        int balancePayments = jdbc.sql("SELECT count(*) FROM payments WHERE purpose = 'BALANCE' AND auction_id = :a")
                .param("a", deal.substring("deal-".length())).query(Integer.class).single();
        assertThat(balancePayments).isEqualTo(1);
        assertThat(ledger.entries(deal, 10)).extracting(Ledger.Entry::kind).containsExactly("BALANCE_RECEIVED", "DEPOSIT_CAPTURED");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String deal1 = fundedDealAwaitingBalance("hank");
        String deal2 = fundedDealAwaitingBalance("hank");
        var hank = graphQl.mutate().header("X-User-Id", "hank").header("Idempotency-Key", "same-key-" + deal1).build();
        hank.document(PAY).variable("d", deal1).execute().path("payBalance.state").entity(String.class).isEqualTo("FUNDED");
        hank.document(PAY).variable("d", deal2).execute().errors()
                .satisfy(e -> assertThat(e.getFirst().getExtensions().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    // ---------------------------------------------------------------- webhooks

    private String sign(String payload, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(IntegrationTest.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + sig;
    }

    private static String event(String id, String type, String paymentIntentId) {
        return """
                {"id":"%s","object":"event","type":"%s","api_version":"2024-06-20","created":1,
                 "data":{"object":{"id":"%s","object":"payment_intent","status":"canceled"}}}
                """.formatted(id, type, paymentIntentId);
    }

    @Test
    void validWebhookUpdatesPaymentAndRegistrationExactlyOnce() throws Exception {
        String lot = Lots.live(auctions, clock, "wh", "eq-1002", 15_000_000, null, 1_500_000, Duration.ofMinutes(30));
        Registration reg = auctions.register(lot, "ivy", null);
        String ref = payments.find(reg.paymentId()).orElseThrow().providerRef();
        String body = event("evt_" + System.nanoTime(), "payment_intent.canceled", ref);
        long now = clock.instant().getEpochSecond();

        for (int i = 0; i < 2; i++) { // Stripe may deliver the same event twice
            mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body).header("Stripe-Signature", sign(body, now)))
                    .andExpect(status().isOk());
        }
        assertThat(payments.find(reg.paymentId()).orElseThrow().status()).isEqualTo(Payment.Status.CANCELED);
        assertThat(auctions.registration(lot, "ivy").orElseThrow().status()).isEqualTo(Registration.Status.RELEASED);
        int audited = jdbc.sql("SELECT count(*) FROM audit_log WHERE event_type = 'PAYMENT_CANCELED' AND subject = :p")
                .param("p", reg.paymentId()).query(Integer.class).single();
        assertThat(audited).isEqualTo(1);

        // A late, out-of-order "authorized" event must not resurrect a canceled hold.
        String late = event("evt_late_" + System.nanoTime(), "payment_intent.amount_capturable_updated", ref);
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(late).header("Stripe-Signature", sign(late, now)))
                .andExpect(status().isOk());
        assertThat(payments.find(reg.paymentId()).orElseThrow().status()).isEqualTo(Payment.Status.CANCELED);
    }

    @Test
    void forgedTamperedOrReplayedWebhooksAreRejected() throws Exception {
        String body = event("evt_forged_" + System.nanoTime(), "payment_intent.succeeded", "pi_whatever");
        long now = clock.instant().getEpochSecond();

        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Stripe-Signature", "t=" + now + ",v1=deadbeef")).andExpect(status().isBadRequest());

        String signedForOtherBody = sign(body.replace("succeeded", "canceled"), now);
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Stripe-Signature", signedForOtherBody)).andExpect(status().isBadRequest());

        // Correct signature but 10 minutes old: a captured request being replayed.
        mvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Stripe-Signature", sign(body, now - 600))).andExpect(status().isBadRequest());

        assertThat(jdbc.sql("SELECT count(*) FROM webhook_events WHERE type = 'payment_intent.succeeded' AND event_id LIKE 'evt_forged_%'")
                .query(Integer.class).single()).isZero();
    }
}
