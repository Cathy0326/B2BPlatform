package com.quipmarket.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.catalog.Equipment;
import com.quipmarket.shared.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RentalValidationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.parse("2026-10-01");

    static Equipment machine(Equipment.RentalRates rates) {
        return new Equipment("eq-1", "Dozer", Equipment.Category.BULLDOZER, "Cat", "D6", 2022, 900, "Austin, TX",
                rates == null ? Equipment.ListingType.SALE : Equipment.ListingType.BOTH, 1L, rates, "", List.of());
    }

    static final Equipment RENTABLE = machine(new Equipment.RentalRates(10_000, 60_000, 200_000));

    @Test
    void acceptsAValidPeriodAndReturnsItsLength() {
        assertThat(RentalService.validate(RENTABLE, TODAY, TODAY.plusDays(7), CLOCK)).isEqualTo(7);
        assertThat(RentalService.validate(RENTABLE, TODAY, TODAY.plusDays(365), CLOCK)).isEqualTo(365);
    }

    @Test
    void rejectsEachInvalidRequestWithAClearMessage() {
        assertThatThrownBy(() -> RentalService.validate(machine(null), TODAY, TODAY.plusDays(1), CLOCK))
                .isInstanceOf(DomainException.InvalidInput.class).hasMessageContaining("not available for rent");
        assertThatThrownBy(() -> RentalService.validate(RENTABLE, TODAY, TODAY, CLOCK)).hasMessageContaining("after the pick-up date");
        assertThatThrownBy(() -> RentalService.validate(RENTABLE, TODAY, TODAY.plusDays(366), CLOCK)).hasMessageContaining("longer than a year");
        assertThatThrownBy(() -> RentalService.validate(RENTABLE, TODAY.minusDays(1), TODAY.plusDays(3), CLOCK)).hasMessageContaining("in the past");
    }
}
