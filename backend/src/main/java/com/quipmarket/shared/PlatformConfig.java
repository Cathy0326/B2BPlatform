package com.quipmarket.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PlatformConfig {

    /**
     * Inject a Clock instead of calling Instant.now() directly, so tests can freeze or move time
     * (essential for soft-close and "auction ended" tests).
     * CORS for /graphql and /graphql-ws is configured via spring.graphql.cors.* in application.yml.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
