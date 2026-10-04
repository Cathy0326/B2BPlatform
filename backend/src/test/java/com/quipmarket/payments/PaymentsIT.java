package com.quipmarket.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.payments.Payment.Purpose;
import com.quipmarket.payments.Payment.Status;
import com.quipmarket.shared.DomainException;
import com.quipmarket.shared.Idempotency;
import com.quipmarket.support.IntegrationTest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

/** Retries are normal in payments (networks fail, users double-click): every operation must be safe to repeat. */
@IntegrationTest
class PaymentsIT {

    @Autowired Payments payments;
    @Autowired Idempotency idempotency;
    @Autowired HttpGraphQlTester graphQl;

    Payment prepared() {
        return payments.prepare(Purpose.DEPOSIT, "pay-it-" + System.nanoTime(), "payer-1", 25_000);
    }

    @Test
    void amountsMustBePositiveAndWithinTheCardLimit() {
        assertThatThrownBy(() -> payments.prepare(Purpose.DEPOSIT, "a", "p", 0)).isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> payments.prepare(Purpose.DEPOSIT, "a", "p", Payments.MAX_CARD_AMOUNT_CENTS + 1))
                .isInstanceOf(DomainException.InvalidInput.class).hasMessageContaining("wire");
        assertThat(payments.prepare(Purpose.DEPOSIT, "a-" + System.nanoTime(), "p", Payments.MAX_CARD_AMOUNT_CENTS).amountCents())
                .isEqualTo(Payments.MAX_CARD_AMOUNT_CENTS);
    }

    @Test
    void executingOrCapturingTwiceDoesNotChargeTwice() {
        Payment p = prepared();
        Payment authorized = payments.execute(p.id(), "pm_card_visa", true, "test");
        Payment retried = payments.execute(p.id(), "pm_card_visa", true, "test");

        assertThat(retried.providerRef()).isEqualTo(authorized.providerRef());
        assertThat(payments.capture(p.id()).status()).isEqualTo(Status.CAPTURED);
        assertThat(payments.capture(p.id()).status()).isEqualTo(Status.CAPTURED); // second capture is a no-op
        assertThatThrownBy(() -> payments.cancel(p.id()))                          // captured money needs a refund, not a void
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("refund");
        assertThat(payments.find(p.id()).orElseThrow().status()).isEqualTo(Status.CAPTURED);
    }

    @Test
    void cancellingIsSafeBeforeExecutionAndAfterAnEarlierCancel() {
        Payment neverSent = prepared();
        assertThat(payments.cancel(neverSent.id()).status()).isEqualTo(Status.CANCELED); // nothing at the provider to void
        assertThat(payments.cancel(neverSent.id()).status()).isEqualTo(Status.CANCELED);

        Payment held = prepared();
        payments.execute(held.id(), "pm_card_visa", true, "test");
        assertThat(payments.cancel(held.id()).status()).isEqualTo(Status.CANCELED);
        assertThatThrownBy(() -> payments.capture(held.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshingAPaymentNeverSentToTheProviderChangesNothing() {
        Payment p = prepared();
        assertThat(payments.refresh(p.id()).status()).isEqualTo(Status.PROCESSING);
        assertThatThrownBy(() -> payments.capture("no-such-payment")).isInstanceOf(DomainException.NotFound.class);
        payments.applyProviderStatus("pi_unknown_" + System.nanoTime(), Status.CAPTURED); // unknown webhook: logged, ignored
    }

    @Test
    void theApiReportsTheSimulatedGatewayWithoutAPublishableKey() {
        assertThat(payments.gatewayName()).isEqualTo("SIMULATED");
        graphQl.document("{ paymentConfig { gateway stripePublishableKey } }").execute()
                .path("paymentConfig.gateway").entity(String.class).isEqualTo("SIMULATED")
                .path("paymentConfig.stripePublishableKey").valueIsNull();
    }

    @Test
    void aFailedActionReleasesItsIdempotencyKeySoTheRetryCanRun() {
        var attempts = new AtomicInteger();
        String key = "retry-key-" + System.nanoTime();

        assertThatThrownBy(() -> idempotency.run("u-1", key, "op", Map.of("x", 1), String.class, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider timeout");
        })).hasMessage("provider timeout");
        String result = idempotency.run("u-1", key, "op", Map.of("x", 1), String.class, () -> "ok-" + attempts.incrementAndGet());

        assertThat(result).isEqualTo("ok-2"); // the retry really executed
        assertThat(idempotency.run("u-1", key, "op", Map.of("x", 1), String.class, () -> "should not run")).isEqualTo("ok-2");
        assertThatThrownBy(() -> idempotency.run("u-1", "short", "op", Map.of(), String.class, () -> "x"))
                .isInstanceOf(DomainException.InvalidInput.class);
    }
}
