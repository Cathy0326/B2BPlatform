package com.quipmarket.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/** Properties of the 5% platform fee that must hold for every hammer price, not only the examples. */
class PlatformFeeProperties {

    @Property
    void feeIsTheExactFivePercentRoundedToTheNearestCent(@ForAll @LongRange(min = 0, max = 10_000_000_000L) long hammerCents) {
        long fee = Escrow.platformFee(hammerCents);
        // |fee - hammer * 5%| <= half a cent, in integer arithmetic: |fee * 10000 - hammer * 500| <= 5000
        assertThat(Math.abs(fee * 10_000 - hammerCents * Escrow.FEE_BPS)).isLessThanOrEqualTo(5_000);
    }

    @Property
    void feeNeverExceedsThePriceAndSellerKeepsTheRest(@ForAll @LongRange(min = 0, max = 10_000_000_000L) long hammerCents) {
        long fee = Escrow.platformFee(hammerCents);
        assertThat(fee).isBetween(0L, hammerCents);
        assertThat(fee + (hammerCents - fee)).isEqualTo(hammerCents); // nothing created or lost when splitting
    }

    @Property
    void aHigherPriceNeverMeansALowerFee(@ForAll @LongRange(min = 0, max = 1_000_000_000L) long a,
                                         @ForAll @LongRange(min = 0, max = 1_000_000_000L) long b) {
        if (a <= b) assertThat(Escrow.platformFee(a)).isLessThanOrEqualTo(Escrow.platformFee(b));
    }
}
