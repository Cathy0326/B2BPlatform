package com.quipmarket.shared;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Abuse protection for a public GraphQL API.
 * GraphQL needs more than request counting: ONE request can ask for a huge nested result
 * (auction -> equipment -> activeAuction -> equipment -> ...). Depth and complexity limits reject such
 * queries before execution.
 */
@Configuration
class ApiProtectionConfig {

    @Bean
    MaxQueryDepthInstrumentation maxQueryDepth(@Value("${quipmarket.graphql.max-depth:10}") int maxDepth) {
        return new MaxQueryDepthInstrumentation(maxDepth);
    }

    /** Complexity = number of fields requested (default 1 per field), summed over the whole query. */
    @Bean
    MaxQueryComplexityInstrumentation maxQueryComplexity(@Value("${quipmarket.graphql.max-complexity:400}") int max) {
        return new MaxQueryComplexityInstrumentation(max);
    }

    /** Registered right AFTER Spring Security, so the authenticated subject is known for the bucket key. */
    @Bean
    @ConditionalOnProperty(name = "quipmarket.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            @Value("${quipmarket.rate-limit.capacity:60}") double capacity,
            @Value("${quipmarket.rate-limit.refill-per-second:10}") double refill,
            @Value("${quipmarket.rate-limit.mutation-cost:5}") double mutationCost) {
        var reg = new FilterRegistrationBean<>(new RateLimitFilter(capacity, refill, mutationCost, System::nanoTime));
        reg.addUrlPatterns("/graphql");
        reg.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
        return reg;
    }
}
