package com.quipmarket.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.catalog.Equipment.Category;
import com.quipmarket.catalog.Equipment.ListingType;
import com.quipmarket.catalog.EquipmentFilter.ListingFilter;
import com.quipmarket.catalog.EquipmentFilter.Sort;
import com.quipmarket.support.IntegrationTest;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Each filter is checked by a PROPERTY of the result (every machine matches, the result is non-empty and
 * narrower than everything) rather than hard-coded ids, so the tests stay true if the demo inventory changes.
 */
@IntegrationTest
class CatalogSearchIT {

    @Autowired Catalog catalog;

    List<Equipment> search(String text, List<Category> categories, ListingFilter listing, Long maxPrice, Integer minYear,
                           Integer maxHours, String location, Sort sort) {
        return catalog.search(new EquipmentFilter(text, categories, listing, maxPrice, minYear, maxHours, location, sort));
    }

    List<Equipment> all() {
        return catalog.search(null);
    }

    @Test
    void noFilterReturnsTheWholeInventoryNewestFirst() {
        List<Equipment> all = all();
        assertThat(all).hasSizeGreaterThanOrEqualTo(10);
        assertThat(all).isSortedAccordingTo(Comparator.comparingInt(Equipment::year).reversed().thenComparingInt(Equipment::hours));
    }

    @Test
    void textSearchMatchesWordPrefixes() {
        List<Equipment> cat = search("cat", null, null, null, null, null, null, null);

        assertThat(cat).isNotEmpty().allMatch(e -> e.make().equalsIgnoreCase("Caterpillar") || e.title().matches("(?i).*\\bcat.*"));
        assertThat(cat).noneMatch(e -> e.make().equalsIgnoreCase("Bobcat")); // "cat" is not a word prefix of Bobcat
        assertThat(search("cat 320", null, null, null, null, null, null, null)).allMatch(e -> e.title().contains("320")); // all terms must match
        assertThat(search("!!!", null, null, null, null, null, null, null)).hasSameSizeAs(all()); // punctuation only = no filter
        assertThat(search("   ", null, null, null, null, null, null, null)).hasSameSizeAs(all());
    }

    @Test
    void categoryAndListingFilters() {
        assertThat(search(null, List.of(Category.CRANE, Category.BACKHOE), null, null, null, null, null, null))
                .isNotEmpty().allMatch(e -> e.category() == Category.CRANE || e.category() == Category.BACKHOE);
        assertThat(search(null, List.of(), null, null, null, null, null, null)).hasSameSizeAs(all()); // empty list = any

        List<Equipment> forSale = search(null, null, ListingFilter.SALE, null, null, null, null, null);
        List<Equipment> forRent = search(null, null, ListingFilter.RENT, null, null, null, null, null);
        assertThat(forSale).isNotEmpty().noneMatch(e -> e.listingType() == ListingType.RENT);
        assertThat(forRent).isNotEmpty().noneMatch(e -> e.listingType() == ListingType.SALE);
        assertThat(search(null, null, ListingFilter.ANY, null, null, null, null, null)).hasSameSizeAs(all());
    }

    @Test
    void numericFiltersAndLocation() {
        long maxPrice = 10_000_000;
        assertThat(search(null, null, null, maxPrice, null, null, null, null))
                .isNotEmpty().allMatch(e -> e.salePriceCents() == null || e.salePriceCents() <= maxPrice); // rent-only machines pass
        assertThat(search(null, null, null, null, 2021, null, null, null)).isNotEmpty().allMatch(e -> e.year() >= 2021);
        assertThat(search(null, null, null, null, null, 2_000, null, null)).isNotEmpty().allMatch(e -> e.hours() <= 2_000);

        String city = all().getFirst().location().substring(0, 4);
        assertThat(search(null, null, null, null, null, null, city.toUpperCase(), null))
                .isNotEmpty().allMatch(e -> e.location().toLowerCase().contains(city.toLowerCase()));
        assertThat(search(null, null, null, null, null, null, "%", null)).isEmpty(); // a literal %, not a wildcard
        assertThat(search(null, null, null, null, null, null, " ", null)).hasSameSizeAs(all());
    }

    @Test
    void filtersCombineWithAnd() {
        List<Equipment> r = search(null, List.of(Category.EXCAVATOR), ListingFilter.RENT, null, 2015, null, null, null);
        assertThat(r).allMatch(e -> e.category() == Category.EXCAVATOR && e.listingType() != ListingType.SALE && e.year() >= 2015);
        assertThat(r.size()).isLessThan(all().size());
    }

    @Test
    void everySortOrderIsHonoured() {
        Comparator<Equipment> price = Comparator.comparingLong(e -> e.salePriceCents() != null ? e.salePriceCents() : e.rentalRates().monthlyCents());

        assertThat(search(null, null, null, null, null, null, null, Sort.PRICE_ASC)).isSortedAccordingTo(price);
        assertThat(search(null, null, null, null, null, null, null, Sort.PRICE_DESC)).isSortedAccordingTo(price.reversed());
        assertThat(search(null, null, null, null, null, null, null, Sort.HOURS_ASC)).isSortedAccordingTo(Comparator.comparingInt(Equipment::hours));
        assertThat(search(null, null, null, null, null, null, null, Sort.NEWEST)).isSortedAccordingTo(Comparator.comparingInt(Equipment::year).reversed().thenComparingInt(Equipment::hours));
    }
}
