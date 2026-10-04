package com.quipmarket.rental;

import com.quipmarket.catalog.Equipment;
import com.quipmarket.shared.DomainException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Public API of the rental module. */
public interface RentalService {

    record Quote(RentalPricing.Price price, List<Booking> conflicts, LocalDate nextAvailableStart) {
        public boolean available() {
            return conflicts.isEmpty();
        }
    }

    Quote quote(String equipmentId, LocalDate start, LocalDate end);

    Booking book(String renterId, String equipmentId, LocalDate start, LocalDate end);

    /**
     * Earliest start >= from where `days` fit between bookings (NeetCode: Merge Intervals + sweep).
     * Pure function, shared by the service and tests.
     */
    static LocalDate nextAvailableStart(LocalDate from, long days, List<Booking> bookings) {
        List<Booking> sorted = new ArrayList<>(bookings);
        sorted.sort(Comparator.comparing(Booking::start));
        LocalDate candidate = from;
        for (Booking b : sorted) {
            if (!candidate.plusDays(days).isAfter(b.start())) break; // fits before this block
            if (candidate.isBefore(b.end())) candidate = b.end();   // collides: jump past it
        }
        return candidate;
    }

    /** Shared validation for quotes and bookings. */
    static long validate(Equipment equipment, LocalDate start, LocalDate end, Clock clock) {
        if (!equipment.isRentable()) throw new DomainException.InvalidInput("This machine is not available for rent.");
        long days = ChronoUnit.DAYS.between(start, end);
        if (days < 1) throw new DomainException.InvalidInput("Return date must be after the pick-up date.");
        if (days > RentalPricing.MAX_DAYS) throw new DomainException.InvalidInput("Rentals longer than a year need a custom quote.");
        if (start.isBefore(LocalDate.now(clock))) throw new DomainException.InvalidInput("Pick-up date cannot be in the past.");
        return days;
    }
}
