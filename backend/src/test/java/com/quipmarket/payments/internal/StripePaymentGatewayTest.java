package com.quipmarket.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.payments.Payment;
import com.quipmarket.payments.PaymentDeclinedException;
import com.quipmarket.payments.PaymentGateway;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Stripe SDK against a tiny local HTTP stub, so we can assert the exact requests
 * (paths, form fields, Idempotency-Key header) without network access or real keys.
 */
class StripePaymentGatewayTest {

    record Req(String method, String path, String idempotencyKey, String body) {}

    HttpServer server;
    final List<Req> requests = new CopyOnWriteArrayList<>();
    volatile int nextStatus = 200;
    volatile String nextBody = "{}";
    StripePaymentGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Req(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Idempotency-Key"),
                    URLDecoder.decode(body, StandardCharsets.UTF_8)));
            byte[] out = nextBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(nextStatus, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        gateway = new StripePaymentGateway("sk_test_dummy", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String status) {
        nextStatus = 200;
        nextBody = """
                {"id":"pi_123","object":"payment_intent","amount":1500000,"currency":"usd","status":"%s","client_secret":"pi_123_secret_x"}
                """.formatted(status);
    }

    @Test
    void depositHoldIsAManualCaptureConfirmedPaymentIntentWithIdempotencyKey() {
        respond("requires_capture");
        var r = gateway.create(new PaymentGateway.ChargeRequest(1_500_000, "usd", "pm_card_visa", true, "deposit",
                Map.of("auction_id", "au-1")), "payment:pay_abc");

        assertThat(r).isEqualTo(new PaymentGateway.Result("pi_123", Payment.Status.AUTHORIZED, null));
        Req req = requests.getFirst();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/v1/payment_intents");
        assertThat(req.idempotencyKey()).isEqualTo("payment:pay_abc");
        assertThat(req.body()).contains("amount=1500000", "currency=usd", "capture_method=manual", "confirm=true",
                "payment_method=pm_card_visa", "metadata[auction_id]=au-1", "automatic_payment_methods[allow_redirects]=never");
    }

    @Test
    void balanceIsCapturedImmediately() {
        respond("succeeded");
        var r = gateway.create(new PaymentGateway.ChargeRequest(500, "usd", null, false, "balance", Map.of()), "payment:pay_b");
        assertThat(r.status()).isEqualTo(Payment.Status.CAPTURED);
        assertThat(requests.getFirst().body()).contains("capture_method=automatic");
    }

    @Test
    void captureAndCancelHitTheRightEndpointsWithKeys() {
        respond("succeeded");
        assertThat(gateway.capture("pi_123", "capture:pay_abc").status()).isEqualTo(Payment.Status.CAPTURED);
        respond("canceled");
        assertThat(gateway.cancel("pi_123", "cancel:pay_abc").status()).isEqualTo(Payment.Status.CANCELED);
        assertThat(requests).extracting(Req::path).containsExactly("/v1/payment_intents/pi_123/capture", "/v1/payment_intents/pi_123/cancel");
        assertThat(requests).extracting(Req::idempotencyKey).containsExactly("capture:pay_abc", "cancel:pay_abc");
    }

    @Test
    void threeDSecureExposesClientSecret() {
        respond("requires_action");
        var r = gateway.create(new PaymentGateway.ChargeRequest(500, "usd", "pm_x", true, "d", Map.of()), "k1");
        assertThat(r.status()).isEqualTo(Payment.Status.REQUIRES_ACTION);
        assertThat(r.clientSecret()).isEqualTo("pi_123_secret_x");
    }

    @Test
    void cardDeclineBecomesPaymentDeclined() {
        nextStatus = 402;
        nextBody = """
                {"error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds","message":"Your card has insufficient funds."}}
                """;
        assertThatThrownBy(() -> gateway.create(new PaymentGateway.ChargeRequest(500, "usd", "pm_card_insufficientFunds", true, "d", Map.of()), "k2"))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("insufficient funds");
    }

    @Test
    void refusesLiveKeys() {
        assertThatThrownBy(() -> new StripePaymentGateway("sk_live_real_money", null)).hasMessageContaining("non-test");
        assertThatThrownBy(() -> new StripePaymentGateway("", null)).hasMessageContaining("required");
    }
}
