package com.quipmarket.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketGraphQlRequest;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Puts the caller's identity, roles and Idempotency-Key into the GraphQL context.
 * Controllers read them with {@code @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false)}.
 *
 *   demo mode : userId from header X-User-Id; roles from header X-User-Roles (comma separated)
 *   auth0 mode: userId = PSEUDONYM of the JWT "sub" ("u-" + 10 hex chars of SHA-256), roles from the
 *               namespaced claim "https://quipmarket.dev/roles" (added by an Auth0 Action).
 *
 * Why a pseudonym? Bidder ids are public in bid histories. Showing "auth0|65f2..." or an email to
 * every other bidder would leak identity. The pseudonym is stable per user but reveals nothing.
 */
@Component
public class CurrentUser implements WebSocketGraphQlInterceptor {

    public static final String CONTEXT_KEY = "userId";
    public static final String ROLES_KEY = "roles";
    public static final String ROLES_CLAIM = "https://quipmarket.dev/roles";
    public static final String ADMIN = "admin";

    private static final String HEADER = "X-User-Id";
    private static final String ROLES_HEADER = "X-User-Roles";
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String SESSION_USER = "quipmarket.userId";
    private static final String SESSION_ROLES = "quipmarket.roles";
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final boolean auth0;
    private final ObjectProvider<JwtDecoder> jwtDecoder;

    CurrentUser(@Value("${quipmarket.auth.mode:demo}") String mode, ObjectProvider<JwtDecoder> jwtDecoder) {
        this.auth0 = "auth0".equals(mode);
        this.jwtDecoder = jwtDecoder;
    }

    /** Returns the user id or throws UNAUTHENTICATED. */
    public static String require(String userId) {
        if (userId == null) throw new DomainException.Unauthenticated();
        return userId;
    }

    /** Throws FORBIDDEN unless the caller has the admin role. */
    public static void requireAdmin(String userId, Collection<String> roles) {
        require(userId);
        if (roles == null || !roles.contains(ADMIN)) throw new Forbidden();
    }

    public static String pseudonym(String subject) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return "u-" + HexFormat.of().formatHex(h).substring(0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sanitize(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim().toLowerCase();
        return VALID.matcher(s).matches() ? s : null;
    }

    static List<String> rolesFromHeader(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.toLowerCase().replace(" ", "").split(","));
    }

    @SuppressWarnings("unchecked")
    static List<String> rolesFromJwt(Jwt jwt) {
        Object claim = jwt.getClaims().get(ROLES_CLAIM);
        return claim instanceof Collection<?> c ? c.stream().map(o -> o.toString().toLowerCase()).toList() : List.of();
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
        String userId = null;
        List<String> roles = List.of();

        if (request instanceof WebSocketGraphQlRequest ws) {
            userId = (String) ws.getSessionInfo().getAttributes().get(SESSION_USER);
            Object r = ws.getSessionInfo().getAttributes().get(SESSION_ROLES);
            if (r instanceof List<?> l) roles = (List<String>) l;
        } else if (auth0) {
            // The BearerTokenAuthenticationFilter already validated the token (or rejected it with 401).
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                userId = pseudonym(jwt.getSubject());
                roles = rolesFromJwt(jwt);
            }
        } else {
            userId = sanitize(request.getHeaders().getFirst(HEADER));
            roles = rolesFromHeader(request.getHeaders().getFirst(ROLES_HEADER));
        }

        var context = new HashMap<String, Object>();
        if (userId != null) context.put(CONTEXT_KEY, userId);
        context.put(ROLES_KEY, roles);
        String idempotencyKey = request.getHeaders().getFirst(IDEMPOTENCY_HEADER);
        if (idempotencyKey != null) context.put(Idempotency.CONTEXT_KEY, idempotencyKey.trim());
        request.configureExecutionInput((input, builder) -> builder.graphQLContext(context).build());
        return chain.next(request);
    }

    /**
     * WebSocket subscriptions can't send an Authorization header from browsers, so the token travels in
     * the connection_init payload and is validated here with the same JwtDecoder.
     */
    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo info, Map<String, Object> payload) {
        if (auth0) {
            Object token = payload.get("authToken");
            if (token != null) {
                try {
                    Jwt jwt = jwtDecoder.getObject().decode(token.toString());
                    info.getAttributes().put(SESSION_USER, pseudonym(jwt.getSubject()));
                    info.getAttributes().put(SESSION_ROLES, rolesFromJwt(jwt));
                } catch (JwtException e) {
                    return Mono.error(new IllegalStateException("Invalid token"));
                }
            }
        } else {
            String userId = sanitize(payload.get(CONTEXT_KEY));
            if (userId != null) info.getAttributes().put(SESSION_USER, userId);
        }
        return Mono.empty();
    }

    public static final class Forbidden extends DomainException {
        Forbidden() {
            super("Administrator role required.");
        }

        @Override
        public String code() {
            return "FORBIDDEN";
        }

        @Override
        public ErrorType errorType() {
            return ErrorType.FORBIDDEN;
        }
    }
}
