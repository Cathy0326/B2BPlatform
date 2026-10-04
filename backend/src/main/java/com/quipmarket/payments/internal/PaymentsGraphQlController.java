package com.quipmarket.payments.internal;

import com.quipmarket.payments.Payments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class PaymentsGraphQlController {

    record PaymentConfig(String gateway, String stripePublishableKey) {}

    private final Payments payments;
    private final String publishableKey;

    PaymentsGraphQlController(Payments payments, @Value("${quipmarket.stripe.publishable-key:}") String publishableKey) {
        this.payments = payments;
        this.publishableKey = publishableKey;
    }

    /** Tells the browser whether to mount Stripe Elements. The publishable key is public by design. */
    @QueryMapping
    PaymentConfig paymentConfig() {
        boolean stripe = "STRIPE".equals(payments.gatewayName());
        return new PaymentConfig(payments.gatewayName(), stripe && !publishableKey.isBlank() ? publishableKey : null);
    }
}
