package com.quipmarket.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quipmarket.payments.Payments;
import org.junit.jupiter.api.Test;

/** The browser mounts Stripe Elements only when the backend really uses Stripe AND a publishable key is set. */
class PaymentsGraphQlControllerTest {

    static PaymentsGraphQlController controller(String gateway, String key) {
        Payments payments = mock(Payments.class);
        when(payments.gatewayName()).thenReturn(gateway);
        return new PaymentsGraphQlController(payments, key);
    }

    @Test
    void stripeWithAKeyExposesThePublishableKey() {
        assertThat(controller("STRIPE", "pk_test_123").paymentConfig())
                .isEqualTo(new PaymentsGraphQlController.PaymentConfig("STRIPE", "pk_test_123"));
    }

    @Test
    void stripeWithoutAKeyFallsBackToTheTestCardPicker() {
        assertThat(controller("STRIPE", " ").paymentConfig().stripePublishableKey()).isNull();
    }

    @Test
    void theSimulatedGatewayNeverSendsAKeyEvenIfOneIsConfigured() {
        assertThat(controller("SIMULATED", "pk_test_123").paymentConfig().stripePublishableKey()).isNull();
        assertThat(controller("SIMULATED", "").paymentConfig().gateway()).isEqualTo("SIMULATED");
    }
}
