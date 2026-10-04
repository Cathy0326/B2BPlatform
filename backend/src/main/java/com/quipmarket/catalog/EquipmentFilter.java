package com.quipmarket.catalog;

import com.quipmarket.catalog.Equipment.Category;
import java.util.List;

/** Bound from the GraphQL input type of the same name. All fields optional. */
public record EquipmentFilter(
        String search,
        List<Category> categories,
        ListingFilter listing,
        Long maxSalePriceCents,
        Integer minYear,
        Integer maxHours,
        String location,
        Sort sort) {

    public enum ListingFilter { ANY, SALE, RENT }

    public enum Sort { NEWEST, PRICE_ASC, PRICE_DESC, HOURS_ASC }

    public static final EquipmentFilter NONE = new EquipmentFilter(null, null, ListingFilter.ANY, null, null, null, null, Sort.NEWEST);
}
