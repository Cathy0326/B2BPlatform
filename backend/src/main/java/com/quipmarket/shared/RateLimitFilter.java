package com.quipmarket.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-caller rate limit for POST /graphql, answered with HTTP 429 + Retry-After.
 *   caller = Auth0 subject (auth0 mode) | X-User-Id (demo mode) | client IP (anonymous)
 *   cost   = 1 per query, `mutationCost` per mutation (mutations write to the DB and may call Stripe)
 * Also rejects bodies over 64 KB (413) before anything is parsed.
 *
 * In-memory: limits apply per replica. With N replicas the effective limit is up to N x, which is fine
 * for abuse protection; a strict global limit would need a shared store (Redis) or a gateway.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern MUTATION = Pattern.compile("\"query\"\\s*:\\s*\"\\s*mutation\\b");
    private static final long IDLE_EVICT_NANOS = 10L * 60 * 1_000_000_000;

    private final double capacity;
    private final double refillPerSecond;
    private final double mutationCost;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(double capacity, double refillPerSecond, double mutationCost, LongSupplier nanoClock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.mutationCost = mutationCost;
        this.nanoClock = nanoClock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/graphql".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(response, 413, "PAYLOAD_TOO_LARGE", "Request body too large.", null);
            return;
        }
        String text = new String(body, StandardCharsets.UTF_8);
        double cost = MUTATION.matcher(text).find() ? mutationCost : 1;

        evictIdle();
        TokenBucket bucket = buckets.computeIfAbsent(callerKey(request), k -> new TokenBucket(capacity, refillPerSecond, nanoClock));
        long retryAfter = bucket.tryConsume(cost);
        if (retryAfter > 0) {
            reject(response, 429, "RATE_LIMITED", "Too many requests. Slow down.", retryAfter);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    static String callerKey(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) return "sub:" + jwt.getSubject();
        String demoUser = CurrentUser.sanitize(request.getHeader("X-User-Id"));
        if (demoUser != null) return "user:" + demoUser;
        return "ip:" + request.getRemoteAddr(); // behind a proxy: server.forward-headers-strategy=framework
    }

    private void evictIdle() {
        if (buckets.size() < 10_000) return;
        long now = nanoClock.getAsLong();
        buckets.values().removeIf(b -> now - b.lastUsedNanos() > IDLE_EVICT_NANOS);
    }

    private static void reject(HttpServletResponse response, int status, String code, String message, Long retryAfter) throws IOException {
        response.setStatus(status);
        if (retryAfter != null) response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.getWriter().write("{\"errors\":[{\"message\":\"" + message + "\",\"extensions\":{\"code\":\"" + code + "\"}}]}");
    }

    /** The body was consumed to inspect it; replay it for the GraphQL handler. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            var in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
