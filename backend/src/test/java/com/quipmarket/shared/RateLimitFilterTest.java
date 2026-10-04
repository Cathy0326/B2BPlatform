package com.quipmarket.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    final AtomicLong now = new AtomicLong();
    final RateLimitFilter filter = new RateLimitFilter(10, 1, 5, now::get); // burst 10, mutation costs 5

    MockHttpServletResponse call(String user, String body) throws Exception {
        var req = new MockHttpServletRequest("POST", "/graphql");
        req.setRequestURI("/graphql");
        if (user != null) req.addHeader("X-User-Id", user);
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        if (res.getStatus() == 200) {
            // The downstream handler must still be able to read the full body.
            assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
        return res;
    }

    @Test
    void mutationsCostMoreThanQueries() throws Exception {
        String mutation = "{\"query\":\"mutation { placeBid(auctionId: \\\"a\\\", maxCents: 1) { accepted } }\"}";
        assertThat(call("alice", mutation).getStatus()).isEqualTo(200);
        assertThat(call("alice", mutation).getStatus()).isEqualTo(200);
        var third = call("alice", mutation);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("5");
        assertThat(third.getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void bucketsArePerCaller() throws Exception {
        String query = "{\"query\":\"{ auctions { id } }\"}";
        for (int i = 0; i < 10; i++) assertThat(call("bob", query).getStatus()).isEqualTo(200);
        assertThat(call("bob", query).getStatus()).isEqualTo(429);
        assertThat(call("carol", query).getStatus()).isEqualTo(200); // carol is unaffected by bob
    }

    @Test
    void hugeBodiesAreRejectedBeforeParsing() throws Exception {
        String big = "{\"query\":\"" + "x".repeat(RateLimitFilter.MAX_BODY_BYTES) + "\"}";
        assertThat(call("dave", big).getStatus()).isEqualTo(413);
    }

    @Test
    void onlyGraphQlPostsAreLimited() throws Exception {
        for (int i = 0; i < 50; i++) {
            var get = new MockHttpServletRequest("GET", "/graphql");
            get.setRequestURI("/graphql");
            var res = new MockHttpServletResponse();
            filter.doFilter(get, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);

            var health = new MockHttpServletRequest("POST", "/actuator/health");
            health.setRequestURI("/actuator/health");
            var res2 = new MockHttpServletResponse();
            filter.doFilter(health, res2, new MockFilterChain());
            assertThat(res2.getStatus()).isEqualTo(200);
        }
        assertThat(call("u-1", "{\"query\":\"{ x }\"}").getStatus()).isEqualTo(200); // the GraphQL bucket is untouched
    }

    @Test
    void anonymousCallersAreLimitedPerIpAddress() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(call(null, "{\"query\":\"{ x }\"}").getStatus()).isEqualTo(200);
        assertThat(call(null, "{\"query\":\"{ x }\"}").getStatus()).isEqualTo(429);   // same IP, bucket empty
        assertThat(call("u-9", "{\"query\":\"{ x }\"}").getStatus()).isEqualTo(200);  // a signed-in user has their own bucket
    }

    @Test
    void signedInUsersAreLimitedByTokenSubjectSoHeadersCannotEscapeTheLimit() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "RS256").subject("auth0|same-person").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        try {
            for (int i = 0; i < 10; i++) assertThat(call("header-" + i, "{\"query\":\"{ x }\"}").getStatus()).isEqualTo(200);
            assertThat(call("another-header", "{\"query\":\"{ x }\"}").getStatus()).isEqualTo(429);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void theApiCanStillReadTheBufferedBodyAsAStreamOrAReader() throws Exception {
        var req = new MockHttpServletRequest("POST", "/graphql");
        req.setRequestURI("/graphql");
        String body = "{\"query\":\"{ me { id } }\"}";
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        var chain = new MockFilterChain();

        filter.doFilter(req, new MockHttpServletResponse(), chain);

        var downstream = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
        assertThat(downstream.getContentLength()).isEqualTo(body.length());
        assertThat(downstream.getContentLengthLong()).isEqualTo(body.length());
        var in = downstream.getInputStream();
        assertThat(in.isReady()).isTrue();
        assertThat(in.isFinished()).isFalse();
        assertThat(in.read()).isEqualTo('{');
        in.readAllBytes();
        assertThat(in.isFinished()).isTrue();
        assertThat(downstream.getReader().readLine()).isEqualTo(body); // every reader gets the whole body again
    }
}
