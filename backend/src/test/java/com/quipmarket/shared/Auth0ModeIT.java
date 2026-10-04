package com.quipmarket.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.quipmarket.support.TestInfrastructure;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Production auth mode with real RS256-signed JWTs. The only test-specific piece is the JwtDecoder,
 * which trusts a key generated here instead of downloading Auth0's JWKS. Issuer, audience and expiry
 * validation are the PRODUCTION validators.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, Auth0ModeIT.Keys.class})
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/demo",
        "quipmarket.escrow.jobs-enabled=false",
        "quipmarket.rate-limit.enabled=false",
        "quipmarket.auth.mode=auth0",
        "quipmarket.auth.issuer=" + Auth0ModeIT.ISSUER,
        "quipmarket.auth.audience=" + Auth0ModeIT.AUDIENCE
})
class Auth0ModeIT {

    static final String ISSUER = "https://quipmarket-test.eu.auth0.com/";
    static final String AUDIENCE = "https://api.quipmarket.test";
    static final KeyPair KEYS = generate();
    static final KeyPair OTHER_KEYS = generate();

    static KeyPair generate() {
        try {
            var g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        /** @Primary wins over the production decoder (which would download Auth0's JWKS). */
        @Bean
        @org.springframework.context.annotation.Primary
        JwtDecoder testJwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            decoder.setJwtValidator(SecurityConfig.validators(ISSUER, AUDIENCE));
            return decoder;
        }
    }

    @Autowired MockMvc mvc;

    static String token(KeyPair keys, String sub, String issuer, String audience, Instant expires, List<String> roles) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(sub).issuer(issuer).audience(audience)
                .issueTime(new Date()).expirationTime(Date.from(expires)).claim(CurrentUser.ROLES_CLAIM, roles).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }

    static String valid(String sub, List<String> roles) throws Exception {
        return token(KEYS, sub, ISSUER, AUDIENCE, Instant.now().plusSeconds(600), roles);
    }

    ResultActions gql(String query, String bearer, String demoUser) throws Exception {
        var req = post("/graphql").contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"" + query.replace("\"", "\\\"") + "\"}");
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        if (demoUser != null) req.header("X-User-Id", demoUser);
        return mvc.perform(req);
    }

    @Test
    void anonymousCanBrowseButIsNobody() throws Exception {
        gql("{ me { id } equipment { id } }", null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me").doesNotExist())
                .andExpect(jsonPath("$.data.equipment.length()").value(12));
    }

    @Test
    void validTokenBecomesAPseudonymNeverTheRawSubject() throws Exception {
        String sub = "auth0|65f2c0ffee";
        gql("{ me { id roles } }", valid(sub, List.of()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.me.id").value(CurrentUser.pseudonym(sub)));
    }

    @Test
    void demoHeadersAreIgnoredInAuth0Mode() throws Exception {
        gql("{ me { id } }", null, "admin").andExpect(jsonPath("$.data.me").doesNotExist());
    }

    @Test
    void forgedExpiredOrForeignTokensAreRejectedWith401() throws Exception {
        gql("{ me { id } }", token(OTHER_KEYS, "auth0|x", ISSUER, AUDIENCE, Instant.now().plusSeconds(600), List.of()), null)
                .andExpect(status().isUnauthorized());                                   // signed by someone else
        gql("{ me { id } }", token(KEYS, "auth0|x", ISSUER, AUDIENCE, Instant.now().minusSeconds(600), List.of()), null)
                .andExpect(status().isUnauthorized());                                   // expired
        gql("{ me { id } }", token(KEYS, "auth0|x", ISSUER, "https://other-api", Instant.now().plusSeconds(600), List.of()), null)
                .andExpect(status().isUnauthorized());                                   // issued for another API
        gql("{ me { id } }", token(KEYS, "auth0|x", "https://evil.example/", AUDIENCE, Instant.now().plusSeconds(600), List.of()), null)
                .andExpect(status().isUnauthorized());                                   // wrong issuer
    }

    @Test
    void adminRoleComesFromTheNamespacedClaim() throws Exception {
        gql("{ trialBalance { balanced } }", valid("auth0|buyer", List.of()), null)
                .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
        gql("{ trialBalance { balanced } }", valid("auth0|ops", List.of("admin")), null)
                .andExpect(jsonPath("$.data.trialBalance.balanced").value(true));
    }

    @Test
    void graphiqlIsDisabledInProductionMode() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/graphiql"))
                .andExpect(status().is4xxClientError());
    }
}
