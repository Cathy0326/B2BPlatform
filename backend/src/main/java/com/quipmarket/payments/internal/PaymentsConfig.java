package com.quipmarket.payments.internal;

import com.quipmarket.payments.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the provider: quipmarket.payments.gateway = simulated (default) | stripe. */
@Configuration
class PaymentsConfig {

    @Bean
    @ConditionalOnProperty(name = "quipmarket.payments.gateway", havingValue = "simulated", matchIfMissing = true)
    PaymentGateway simulatedGateway() {
        return new SimulatedPaymentGateway();
    }

    @Bean
    @ConditionalOnProperty(name = "quipmarket.payments.gateway", havingValue = "stripe")
    PaymentGateway stripeGateway(@Value("${quipmarket.stripe.secret-key:}") String secretKey,
                                 @Value("${quipmarket.stripe.api-base:}") String apiBase) {
        return new StripePaymentGateway(secretKey, apiBase);
    }
}
