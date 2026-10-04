package com.quipmarket.shared;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two modes, chosen by quipmarket.auth.mode:
 *
 *   demo  (default) - no login. Callers identify with the X-User-Id header. For local development,
 *                     tests and the public demo. NEVER for real money.
 *   auth0           - OAuth2 resource server. Every request may carry "Authorization: Bearer <JWT>"
 *                     issued by Auth0. The token's signature (JWKS), issuer, audience and expiry are
 *                     validated. Anonymous requests can still read public data (catalog, auctions);
 *                     resolvers that need a user or the ADMIN role enforce it.
 *
 * Both modes: stateless (no session cookie), so CSRF protection is not needed. CSRF attacks ride on
 * cookies the browser sends automatically; a bearer token is never sent automatically.
 */
@Configuration
class SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "quipmarket.auth.mode", havingValue = "demo", matchIfMissing = true)
    SecurityFilterChain demoSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "quipmarket.auth.mode", havingValue = "auth0")
    SecurityFilterChain auth0Security(HttpSecurity http) throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()          // authenticated by Stripe's signature instead
                        .requestMatchers("/graphql", "/graphql-ws", "/graphql/schema").permitAll() // field-level checks in resolvers
                        .requestMatchers("/graphiql/**").denyAll()            // no browser IDE in production mode
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * JWKS is fetched lazily on the first token (no network call at startup).
     * Validates: signature, exp/nbf, issuer, and that the token was issued FOR THIS API (audience).
     * Without the audience check, a token Auth0 issued for some other API of the same tenant would work here.
     */
    @Bean
    @ConditionalOnProperty(name = "quipmarket.auth.mode", havingValue = "auth0")
    JwtDecoder auth0JwtDecoder(@Value("${quipmarket.auth.issuer}") String issuer,
                               @Value("${quipmarket.auth.audience}") String audience) {
        String normalized = issuer.endsWith("/") ? issuer : issuer + "/";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(normalized + ".well-known/jwks.json").build();
        decoder.setJwtValidator(validators(normalized, audience));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            List<String> aud = jwt.getAudience();
            return aud != null && aud.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token audience is not " + audience, null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }
}
