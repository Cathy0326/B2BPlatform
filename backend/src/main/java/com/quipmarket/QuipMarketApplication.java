package com.quipmarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modular monolith. Each direct sub-package is a module (verified by ModularityTests):
 *   shared     - cross-cutting GraphQL/web plumbing (open module)
 *   catalog    - equipment listings
 *   rental     - bookings + day/week/month pricing (depends on catalog)
 *   auction    - proxy-bidding auctions, deposits, live updates (depends on catalog)
 *   financing  - loan amortization (no dependencies)
 * Types in a module's "internal" package are private to that module.
 */
@SpringBootApplication
@EnableScheduling
public class QuipMarketApplication {
    public static void main(String[] args) {
        SpringApplication.run(QuipMarketApplication.class, args);
    }
}
