package com.quipmarket.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.catalog.Equipment.RentalRates;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class RentalPricingTest {

    static final RentalRates RATES = new RentalRates(110_000, 320_000, 800_000);

    @Test
    void dailyForShortRentals() {
        var p = RentalPricing.quote(2, RATES);
        assertThat(p.totalCents()).isEqualTo(220_000);
        assertThat(p.days()).isEqualTo(2);
    }

    @Test
    void buysAWeekWhenCheaperThanDaily() {
        var p = RentalPricing.quote(3, RATES); // 3 x 1,100 = 3,300 > 3,200
        assertThat(p.totalCents()).isEqualTo(320_000);
        assertThat(p.weeks()).isEqualTo(1);
    }

    @Test
    void combinesBlocks() {
        var p = RentalPricing.quote(31, RATES); // 1M + 1W beats 1M + 3D
        assertThat(p.totalCents()).isEqualTo(1_120_000);
        assertThat(p.months()).isEqualTo(1);
        assertThat(p.weeks()).isEqualTo(1);
    }

    @Test
    void breakdownAlwaysPricesToTheTotal() {
        for (int d = 1; d <= 365; d++) {
            var p = RentalPricing.quote(d, RATES);
            assertThat(p.months() * RATES.monthlyCents() + p.weeks() * RATES.weeklyCents() + p.days() * RATES.dailyCents())
                    .isEqualTo(p.totalCents());
            assertThat(p.months() * 28 + p.weeks() * 7 + p.days()).isGreaterThanOrEqualTo(d);
        }
    }

    @Test
    void reportsTheSavingAgainstTheDailyRate() {
        var p = RentalPricing.quote(28, RATES);
        assertThat(p.naiveDailyCents()).isEqualTo(28 * 110_000L);
        assertThat(p.savingsCents()).isEqualTo(28 * 110_000L - 800_000);
    }

    @Test
    void acceptsTheFullRangeOneToThreeHundredSixtyFiveDays() {
        assertThat(RentalPricing.quote(1, RATES).rentalDays()).isEqualTo(1);
        assertThat(RentalPricing.quote(RentalPricing.MAX_DAYS, RATES).rentalDays()).isEqualTo(365);
    }

    @Test
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> RentalPricing.quote(0, RATES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RentalPricing.quote(366, RATES)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextAvailableStartSkipsGapsThatAreTooSmall() {
        var bookings = List.of(
                new Booking(1L, "e", LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-30")),
                new Booking(2L, "e", LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-20")));
        assertThat(RentalService.nextAvailableStart(LocalDate.parse("2026-10-01"), 5, bookings)).isEqualTo("2026-10-01");
        assertThat(RentalService.nextAvailableStart(LocalDate.parse("2026-10-08"), 5, bookings)).isEqualTo("2026-10-30");
        assertThat(RentalService.nextAvailableStart(LocalDate.parse("2026-10-08"), 3, bookings)).isEqualTo("2026-10-20");
    }

    @Test
    void backToBackBookingsDoNotOverlap() {
        var b = new Booking(1L, "e", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-05"));
        assertThat(b.overlaps(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-08"))).isFalse();
        assertThat(b.overlaps(LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-08"))).isTrue();
    }
}
