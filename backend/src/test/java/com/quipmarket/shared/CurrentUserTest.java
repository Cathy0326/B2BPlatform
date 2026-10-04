package com.quipmarket.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.graphql.server.WebSocketSessionInfo;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import reactor.core.publisher.Mono;

/**
 * Browsers cannot send an Authorization header on a WebSocket, so subscriptions authenticate in the
 * connection_init payload. These tests pin down who the server believes the subscriber is.
 */
class CurrentUserTest {

    /** Minimal session: only the attribute map matters to CurrentUser. */
    static final class Session implements WebSocketSessionInfo {
        final Map<String, Object> attributes = new HashMap<>();
        @Override public String getId() { return "s-1"; }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public URI getUri() { return URI.create("ws://localhost/graphql-ws"); }
        @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
        @Override public Mono<Principal> getPrincipal() { return Mono.empty(); }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
    }

    static CurrentUser demoMode() {
        return new CurrentUser("demo", new DefaultListableBeanFactory().getBeanProvider(JwtDecoder.class));
    }

    static CurrentUser auth0Mode(JwtDecoder decoder) {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("jwtDecoder", decoder);
        return new CurrentUser("auth0", factory.getBeanProvider(JwtDecoder.class));
    }

    static Jwt jwt(String subject, Object roles) {
        var builder = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject)
                .issuedAt(Instant.parse("2026-10-01T00:00:00Z")).expiresAt(Instant.parse("2026-10-02T00:00:00Z"));
        if (roles != null) builder.claim(CurrentUser.ROLES_CLAIM, roles);
        return builder.build();
    }

    @Test
    void demoModeTrustsOnlyAWellFormedUserId() {
        var ok = new Session();
        assertThat(demoMode().handleConnectionInitialization(ok, Map.of(CurrentUser.CONTEXT_KEY, " Alice ")).blockOptional()).isEmpty();
        assertThat(ok.attributes).containsEntry("quipmarket.userId", "alice");

        var injection = new Session();
        assertThat(demoMode().handleConnectionInitialization(injection, Map.of(CurrentUser.CONTEXT_KEY, "alice'; DROP TABLE")).blockOptional()).isEmpty();
        assertThat(injection.attributes).isEmpty(); // anonymous, not "alice'; DROP TABLE"

        var anonymous = new Session();
        assertThat(demoMode().handleConnectionInitialization(anonymous, Map.of()).blockOptional()).isEmpty();
        assertThat(anonymous.attributes).isEmpty();
    }

    @Test
    void auth0ModeValidatesTheTokenAndStoresOnlyAPseudonym() {
        JwtDecoder decoder = token -> jwt("auth0|abc123", List.of("Admin", "buyer"));
        var session = new Session();

        assertThat(auth0Mode(decoder).handleConnectionInitialization(session, Map.of("authToken", "valid")).blockOptional()).isEmpty();

        assertThat(session.attributes.get("quipmarket.userId")).isEqualTo(CurrentUser.pseudonym("auth0|abc123"));
        assertThat((String) session.attributes.get("quipmarket.userId")).startsWith("u-").doesNotContain("auth0");
        assertThat(session.attributes.get("quipmarket.roles")).isEqualTo(List.of("admin", "buyer"));
    }

    @Test
    void auth0ModeRejectsAnInvalidTokenAndAllowsAnonymousSubscribers() {
        JwtDecoder decoder = token -> { throw new JwtException("expired"); };

        assertThatThrownBy(() -> auth0Mode(decoder).handleConnectionInitialization(new Session(), Map.of("authToken", "bad")).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid token");

        var anonymous = new Session();
        assertThat(auth0Mode(decoder).handleConnectionInitialization(anonymous, Map.of()).blockOptional()).isEmpty();
        assertThat(anonymous.attributes).isEmpty(); // public auction updates need no login
    }

    @Test
    void rolesComeOnlyFromAListClaim() {
        assertThat(CurrentUser.rolesFromJwt(jwt("s", List.of("ADMIN")))).containsExactly("admin");
        assertThat(CurrentUser.rolesFromJwt(jwt("s", "admin"))).isEmpty(); // a plain string is not a role list
        assertThat(CurrentUser.rolesFromJwt(jwt("s", null))).isEmpty();
        assertThat(CurrentUser.rolesFromHeader(" ")).isEmpty();
        assertThat(CurrentUser.rolesFromHeader("Admin, Buyer")).containsExactly("admin", "buyer");
    }

    @Test
    void adminChecksRequireBothAUserAndTheRole() {
        assertThatThrownBy(() -> CurrentUser.requireAdmin(null, List.of("admin"))).isInstanceOf(DomainException.Unauthenticated.class);
        assertThatThrownBy(() -> CurrentUser.requireAdmin("u-1", null)).isInstanceOf(CurrentUser.Forbidden.class);
        assertThatThrownBy(() -> CurrentUser.requireAdmin("u-1", List.of("buyer"))).isInstanceOf(CurrentUser.Forbidden.class);
        CurrentUser.requireAdmin("u-1", List.of("admin"));
    }
}
