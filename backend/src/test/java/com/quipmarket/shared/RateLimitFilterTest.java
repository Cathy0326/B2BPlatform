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
}
