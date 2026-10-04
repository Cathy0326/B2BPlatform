package com.quipmarket.payments.internal;

import com.quipmarket.payments.Payment.Status;
import com.quipmarket.payments.PaymentDeclinedException;
import com.quipmarket.payments.PaymentGateway;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

/**
 * Stripe PaymentIntents API.
 *   deposit hold  -> capture_method=manual  -> status requires_capture   (= AUTHORIZED)
 *   balance       -> capture_method=automatic -> status succeeded       (= CAPTURED)
 * Our idempotency key is forwarded as Stripe's Idempotency-Key header, so a retried request after a
 * network timeout returns the original PaymentIntent instead of creating a second charge.
 *
 * Safety: refuses to start with a live key (sk_live_...). This is a demo.
 */
class StripePaymentGateway implements PaymentGateway {

    private final StripeClient stripe;

    StripePaymentGateway(String secretKey, String apiBase) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("STRIPE_SECRET_KEY is required when quipmarket.payments.gateway=stripe");
        }
        if (!secretKey.startsWith("sk_test_") && !secretKey.startsWith("rk_test_")) {
            throw new IllegalStateException("Refusing to run with a non-test Stripe key. Use a sk_test_... key.");
        }
        var builder = StripeClient.builder().setApiKey(secretKey).setMaxNetworkRetries(2);
        if (apiBase != null && !apiBase.isBlank()) builder.setApiBase(apiBase); // tests point this at a local stub
        this.stripe = builder.build();
    }

    @Override
    public String name() {
        return "STRIPE";
    }

    @Override
    public Result create(ChargeRequest r, String idempotencyKey) {
        var params = PaymentIntentCreateParams.builder()
                .setAmount(r.amountCents())
                .setCurrency(r.currency())
                .setPaymentMethod(r.paymentMethodId() == null ? "pm_card_visa" : r.paymentMethodId())
                // Server-side confirmation: allow card-like methods only, never a redirect flow.
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .setConfirm(true)
                .setCaptureMethod(r.captureLater()
                        ? PaymentIntentCreateParams.CaptureMethod.MANUAL
                        : PaymentIntentCreateParams.CaptureMethod.AUTOMATIC)
                .setDescription(r.description())
                .putAllMetadata(r.metadata())
                .build();
        return call(() -> stripe.v1().paymentIntents().create(params, options(idempotencyKey)));
    }

    @Override
    public Result capture(String ref, String idempotencyKey) {
        return call(() -> stripe.v1().paymentIntents().capture(ref,
                com.stripe.param.PaymentIntentCaptureParams.builder().build(), options(idempotencyKey)));
    }

    @Override
    public Result cancel(String ref, String idempotencyKey) {
        return call(() -> stripe.v1().paymentIntents().cancel(ref,
                com.stripe.param.PaymentIntentCancelParams.builder().build(), options(idempotencyKey)));
    }

    @Override
    public Result retrieve(String ref) {
        return call(() -> stripe.v1().paymentIntents().retrieve(ref));
    }

    private static RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    interface StripeCall {
        PaymentIntent run() throws StripeException;
    }

    private static Result call(StripeCall c) {
        try {
            PaymentIntent pi = c.run();
            Status status = map(pi.getStatus());
            return new Result(pi.getId(), status, status == Status.REQUIRES_ACTION ? pi.getClientSecret() : null);
        } catch (CardException e) {
            throw new PaymentDeclinedException(e.getUserMessage() != null ? e.getUserMessage() : e.getMessage(), e.getDeclineCode());
        } catch (StripeException e) {
            throw new IllegalStateException("Stripe error: " + e.getMessage(), e);
        }
    }

    static Status map(String stripeStatus) {
        return switch (stripeStatus) {
            case "requires_capture" -> Status.AUTHORIZED;
            case "succeeded" -> Status.CAPTURED;
            case "canceled" -> Status.CANCELED;
            case "requires_action", "requires_confirmation" -> Status.REQUIRES_ACTION;
            case "processing" -> Status.PROCESSING;
            default -> Status.FAILED; // requires_payment_method = the last attempt failed
        };
    }
}
