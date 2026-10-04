package com.quipmarket.rental;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Half-open period [start, end): `end` is the return day and the machine is free again that day. */
public record Booking(Long id, String equipmentId, LocalDate start, LocalDate end) {

    public long days() {
        return ChronoUnit.DAYS.between(start, end);
    }

    /** Classic interval-overlap test (strict < makes back-to-back bookings legal). */
    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
}
