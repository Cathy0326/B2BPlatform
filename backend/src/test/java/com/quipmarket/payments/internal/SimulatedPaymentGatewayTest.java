package com.quipmarket.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.payments.Payment.Status;
import com.quipmarket.payments.PaymentDeclinedException;
import com.quipmarket.payments.PaymentGateway.ChargeRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The simulated gateway must behave like Stripe's test mode, or tests against it prove nothing. */
class SimulatedPaymentGatewayTest {

    final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway();

    static ChargeRequest charge(String paymentMethod, boolean captureLater) {
        return new ChargeRequest(10_000, "usd", paymentMethod, captureLater, "test", Map.of());
    }

    @Test
    void cardTokensBehaveLikeStripeTestCards() {
        assertThat(gateway.name()).isEqualTo("SIMULATED");
        assertThat(gateway.create(charge(null, false), "k1").status()).isEqualTo(Status.CAPTURED); // defaults to a working Visa
        assertThat(gateway.create(charge("pm_card_visa", true), "k2").status()).isEqualTo(Status.AUTHORIZED);

        var threeDs = gateway.create(charge("pm_card_authenticationRequired", true), "k3");
        assertThat(threeDs.status()).isEqualTo(Status.REQUIRES_ACTION);
        assertThat(threeDs.clientSecret()).endsWith("_secret_sim");

        assertThatThrownBy(() -> gateway.create(charge("pm_card_chargeDeclined", false), "k4"))
                .isInstanceOf(PaymentDeclinedException.class)
                .satisfies(e -> assertThat(((PaymentDeclinedException) e).details()).containsEntry("declineCode", "generic_decline"));
        assertThatThrownBy(() -> gateway.create(charge("pm_card_insufficientFunds", false), "k5"))
                .satisfies(e -> assertThat(((PaymentDeclinedException) e).details()).containsEntry("declineCode", "insufficient_funds"));
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameChargeInsteadOfChargingTwice() {
        var first = gateway.create(charge("pm_card_visa", true), "same-key");
        var retry = gateway.create(charge("pm_card_visa", true), "same-key");

        assertThat(retry).isEqualTo(first);
    }

    @Test
    void authorizeThenCaptureOrCancel() {
        String captured = gateway.create(charge("pm_card_visa", true), "a").providerRef();
        assertThat(gateway.capture(captured, "a-capture").status()).isEqualTo(Status.CAPTURED);
        assertThat(gateway.capture(captured, "a-capture-again").status()).isEqualTo(Status.CAPTURED); // capturing twice is harmless
        assertThatThrownBy(() -> gateway.cancel(captured, "a-cancel")).isInstanceOf(IllegalStateException.class).hasMessageContaining("refund");

        String voided = gateway.create(charge("pm_card_visa", true), "b").providerRef();
        assertThat(gateway.cancel(voided, "b-cancel").status()).isEqualTo(Status.CANCELED);
        assertThatThrownBy(() -> gateway.capture(voided, "b-capture")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void threeDSecureMustBeCompletedBeforeCapture() {
        String ref = gateway.create(charge("pm_card_authenticationRequired", true), "c").providerRef();
        assertThatThrownBy(() -> gateway.capture(ref, "c-early")).isInstanceOf(IllegalStateException.class);

        gateway.completeAuthentication(ref);

        assertThat(gateway.retrieve(ref).status()).isEqualTo(Status.AUTHORIZED);
        assertThat(gateway.capture(ref, "c-capture").status()).isEqualTo(Status.CAPTURED);
        assertThat(gateway.retrieve("sim_pi_unknown").status()).isEqualTo(Status.FAILED);
    }

    @Test
    void declineCodeDefaultsToGeneric() {
        assertThat(new PaymentDeclinedException("no", null).details()).containsEntry("declineCode", "generic_decline");
        assertThat(new PaymentDeclinedException("no", null).code()).isEqualTo("PAYMENT_DECLINED");
    }
}
