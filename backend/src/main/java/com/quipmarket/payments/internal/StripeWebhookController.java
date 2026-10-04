package com.quipmarket.payments.internal;

import com.quipmarket.audit.AuditTrail;
import com.quipmarket.payments.Payment;
import com.quipmarket.payments.Payments;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /webhooks/stripe
 *
 * 1. Verify the Stripe-Signature header: HMAC-SHA256 of "timestamp.payload" with the endpoint secret,
 *    and reject timestamps older than 5 minutes (replay protection). Anyone can POST to this URL;
 *    only Stripe knows the secret.
 * 2. De-duplicate by event id: Stripe delivers AT LEAST once, so the same event may arrive twice.
 *    Insert into webhook_events (primary key) + apply the change in ONE transaction.
 * 3. Reply 2xx fast. Any non-2xx makes Stripe retry later with backoff.
 */
@RestController
class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    private static final long TOLERANCE_SECONDS = 300;

    private final Payments payments;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final AuditTrail audit;
    private final String secret;
    private final java.time.Clock clock;

    StripeWebhookController(Payments payments, JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, AuditTrail audit,
                            java.time.Clock clock, @Value("${quipmarket.stripe.webhook-secret:}") String secret) {
        this.payments = payments;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.audit = audit;
        this.clock = clock;
        this.secret = secret;
    }

    @PostMapping(path = "/webhooks/stripe", consumes = "application/json")
    ResponseEntity<String> receive(@RequestBody String payload,
                                   @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        if (secret.isBlank()) return ResponseEntity.status(404).body("webhooks disabled");
        if (signature == null) return ResponseEntity.badRequest().body("missing signature");
        try {
            Webhook.constructEvent(payload, signature, secret, TOLERANCE_SECONDS, clock);
        } catch (SignatureVerificationException e) {
            log.warn("Rejected webhook: {}", e.getMessage());
            return ResponseEntity.badRequest().body("invalid signature");
        }

        // Parse ourselves (stable across Stripe API versions) - we only need a few fields.
        JsonNode event = json.readTree(payload);
        String eventId = event.path("id").asString();
        String type = event.path("type").asString();
        JsonNode object = event.path("data").path("object");

        tx.executeWithoutResult(s -> {
            int inserted = jdbc.sql("INSERT INTO webhook_events (event_id, provider, type) VALUES (:id, 'STRIPE', :t) ON CONFLICT DO NOTHING")
                    .param("id", eventId).param("t", type).update();
            if (inserted == 0) {
                log.info("Duplicate webhook {} ignored", eventId);
                return;
            }
            audit.record("WEBHOOK_RECEIVED", eventId, "stripe", Map.of("type", type, "object", object.path("id").asString("")));
            if ("payment_intent".equals(object.path("object").asString())) {
                Payment.Status status = switch (type) {
                    case "payment_intent.amount_capturable_updated" -> Payment.Status.AUTHORIZED;
                    case "payment_intent.succeeded" -> Payment.Status.CAPTURED;
                    case "payment_intent.canceled" -> Payment.Status.CANCELED;
                    case "payment_intent.payment_failed" -> Payment.Status.FAILED;
                    case "payment_intent.requires_action" -> Payment.Status.REQUIRES_ACTION;
                    default -> null;
                };
                if (status != null) payments.applyProviderStatus(object.path("id").asString(), status);
            }
        });
        return ResponseEntity.ok("ok");
    }
}
