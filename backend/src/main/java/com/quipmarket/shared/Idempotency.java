package com.quipmarket.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stripe-style idempotency for mutations that move money.
 *
 * The client sends a unique {@code Idempotency-Key} header per user action (e.g. one UUID per click on
 * "Pay balance") and re-sends the SAME key when it retries after a timeout. Then:
 *   first request          -> runs, response stored
 *   retry, same request    -> stored response returned, the action does NOT run again
 *   retry while running    -> IDEMPOTENCY_IN_PROGRESS (try again shortly)
 *   same key, other body   -> IDEMPOTENCY_KEY_REUSED (client bug)
 *   action failed          -> key released, so a retry may run it again
 */
@Component
public class Idempotency {

    public static final String CONTEXT_KEY = "idempotencyKey";
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,255}");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate newTx;

    Idempotency(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.newTx = new TransactionTemplate(txManager);
        // Key bookkeeping commits on its own, independent of the business transaction.
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T run(String userId, String key, String operation, Object request, Class<T> responseType, Supplier<T> action) {
        if (key == null) throw new KeyRequired();
        if (!VALID_KEY.matcher(key).matches()) throw new DomainException.InvalidInput("Idempotency-Key must be 8-255 characters [A-Za-z0-9_.:-]");
        String requestHash = sha256(operation + "\n" + json.writeValueAsString(request));

        boolean claimed = Boolean.TRUE.equals(newTx.execute(s -> jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, key, operation, request_hash, status)
                        VALUES (:u, :k, :o, :h, 'IN_PROGRESS') ON CONFLICT (user_id, key) DO NOTHING
                        """)
                .param("u", userId).param("k", key).param("o", operation).param("h", requestHash).update() == 1));

        if (!claimed) {
            var existing = jdbc.sql("SELECT operation, request_hash, status, response FROM idempotency_keys WHERE user_id = :u AND key = :k")
                    .param("u", userId).param("k", key)
                    .query((rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                    .single();
            // Constant-time comparison: how long the check takes must not reveal how much of the hash matched.
            boolean sameRequest = MessageDigest.isEqual(existing[1].getBytes(StandardCharsets.UTF_8), requestHash.getBytes(StandardCharsets.UTF_8));
            if (!existing[0].equals(operation) || !sameRequest) throw new KeyReused();
            if ("IN_PROGRESS".equals(existing[2])) throw new InProgress();
            return json.readValue(existing[3], responseType);
        }

        T response;
        try {
            response = action.get();
        } catch (RuntimeException e) {
            newTx.executeWithoutResult(s -> jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = :u AND key = :k")
                    .param("u", userId).param("k", key).update());
            throw e;
        }
        String body = json.writeValueAsString(response);
        newTx.executeWithoutResult(s -> jdbc.sql("UPDATE idempotency_keys SET status = 'COMPLETED', response = :r WHERE user_id = :u AND key = :k")
                .param("r", body).param("u", userId).param("k", key).update());
        return response;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class KeyRequired extends DomainException {
        KeyRequired() { super("This operation requires an Idempotency-Key header."); }
        @Override public String code() { return "IDEMPOTENCY_KEY_REQUIRED"; }
    }

    public static final class KeyReused extends DomainException {
        KeyReused() { super("This Idempotency-Key was already used for a different request."); }
        @Override public String code() { return "IDEMPOTENCY_KEY_REUSED"; }
    }

    public static final class InProgress extends DomainException {
        InProgress() { super("A request with this Idempotency-Key is still being processed. Retry shortly."); }
        @Override public String code() { return "IDEMPOTENCY_IN_PROGRESS"; }
    }
}
