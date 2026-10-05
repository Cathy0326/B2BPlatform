package com.quipmarket.rental;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.catalog.Equipment.RentalRates;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/** The dynamic-programming quote against invariants and against a brute-force search over every block mix. */
class RentalPricingProperties {

    @Provide
    Arbitrary<RentalRates> rates() {
        return Combinators.combine(
                Arbitraries.longs().between(1, 500_000),
                Arbitraries.longs().between(1, 3_000_000),
                Arbitraries.longs().between(1, 10_000_000)).as(RentalRates::new);
    }

    @Property
    void quoteIsTheCheapestOfAllBlockMixes(@ForAll @IntRange(min = 1, max = 120) int days, @ForAll("rates") RentalRates r) {
        // Oracle: try every number of months and weeks; days fill the rest. Obviously correct, just slow.
        long best = Long.MAX_VALUE;
        for (int m = 0; m <= days / 28 + 1; m++) {
            for (int w = 0; w <= days / 7 + 1; w++) {
                int rest = Math.max(0, days - 28 * m - 7 * w);
                best = Math.min(best, m * r.monthlyCents() + w * r.weeklyCents() + rest * r.dailyCents());
            }
        }
        assertThat(RentalPricing.quote(days, r).totalCents()).isEqualTo(best);
    }

    @Property
    void breakdownPaysForTheTotalAndCoversEveryDay(@ForAll @IntRange(min = 1, max = 365) int days, @ForAll("rates") RentalRates r) {
        var p = RentalPricing.quote(days, r);
        assertThat(p.months() * r.monthlyCents() + p.weeks() * r.weeklyCents() + p.days() * r.dailyCents()).isEqualTo(p.totalCents());
        assertThat(p.months() * 28 + p.weeks() * 7 + p.days()).isGreaterThanOrEqualTo(days);
        assertThat(p.savingsCents()).isGreaterThanOrEqualTo(0); // never worse than paying daily
    }

    @Property
    void aLongerRentalNeverCostsLess(@ForAll @IntRange(min = 1, max = 364) int days, @ForAll("rates") RentalRates r) {
        assertThat(RentalPricing.quote(days + 1, r).totalCents()).isGreaterThanOrEqualTo(RentalPricing.quote(days, r).totalCents());
    }
}
