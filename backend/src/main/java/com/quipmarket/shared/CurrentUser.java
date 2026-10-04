package com.quipmarket.shared;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Who is calling? Phase 2 demo identity: the "X-User-Id" HTTP header (or the "userId" field of the
 * WebSocket connection_init payload). Phase 4 replaces this with the Auth0 JWT "sub" claim.
 *
 * The id lands in the GraphQL context under {@link #CONTEXT_KEY}; controllers read it with
 * {@code @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId}.
 */
@Component
public class CurrentUser implements WebSocketGraphQlInterceptor {

    public static final String CONTEXT_KEY = "userId";
    private static final String HEADER = "X-User-Id";
    private static final String SESSION_ATTR = "quipmarket.userId";
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    /** Returns the user id or throws UNAUTHENTICATED. */
    public static String require(String userId) {
        if (userId == null) throw new DomainException.Unauthenticated();
        return userId;
    }

    static String sanitize(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim().toLowerCase();
        return VALID.matcher(s).matches() ? s : null;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
        String userId = sanitize(request.getHeaders().getFirst(HEADER));
        if (userId == null && request instanceof org.springframework.graphql.server.WebSocketGraphQlRequest ws) {
            userId = (String) ws.getSessionInfo().getAttributes().get(SESSION_ATTR);
        }
        if (userId != null) {
            String id = userId;
            request.configureExecutionInput((input, builder) -> builder.graphQLContext(Map.of(CONTEXT_KEY, id)).build());
        }
        return chain.next(request);
    }

    @Override
    public Mono<Object> handleConnectionInitialization(WebSocketSessionInfo info, Map<String, Object> payload) {
        String userId = sanitize(payload.get(CONTEXT_KEY));
        if (userId != null) info.getAttributes().put(SESSION_ATTR, userId);
        return Mono.empty();
    }
}
