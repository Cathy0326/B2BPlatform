package com.quipmarket.catalog;

import java.util.List;

/** A listing. Public API of the catalog module (other modules may reference it). */
public record Equipment(
        String id,
        String title,
        Category category,
        String make,
        String model,
        int year,
        int hours,
        String location,
        ListingType listingType,
        Long salePriceCents,
        RentalRates rentalRates,
        String description,
        List<Spec> specs) {

    public Equipment {
        specs = specs == null ? List.of() : List.copyOf(specs);
    }

    public enum Category { EXCAVATOR, BULLDOZER, WHEEL_LOADER, SKID_STEER, CRANE, BACKHOE }

    public enum ListingType { SALE, RENT, BOTH }

    /** Integer cents. One rental month = 28 days. */
    public record RentalRates(long dailyCents, long weeklyCents, long monthlyCents) {}

    public record Spec(String name, String value) {}

    public boolean isRentable() {
        return rentalRates != null;
    }
}
