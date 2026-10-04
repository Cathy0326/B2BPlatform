package com.quipmarket.rental.internal;

import com.quipmarket.catalog.Catalog;
import com.quipmarket.catalog.Equipment;
import com.quipmarket.rental.Booking;
import com.quipmarket.rental.RentalPricing;
import com.quipmarket.rental.RentalService;
import com.quipmarket.shared.DomainException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultRentalService implements RentalService {

    private final Catalog catalog;
    private final BookingRepository bookings;
    private final Clock clock;

    DefaultRentalService(Catalog catalog, BookingRepository bookings, Clock clock) {
        this.catalog = catalog;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Quote quote(String equipmentId, LocalDate start, LocalDate end) {
        Equipment equipment = load(equipmentId);
        long days = RentalService.validate(equipment, start, end, clock);
        List<Booking> existing = bookings.findByEquipment(equipmentId);
        List<Booking> conflicts = existing.stream().filter(b -> b.overlaps(start, end)).toList();
        LocalDate next = conflicts.isEmpty() ? start : RentalService.nextAvailableStart(start, days, existing);
        return new Quote(RentalPricing.quote((int) days, equipment.rentalRates()), conflicts, next);
    }

    @Override
    @Transactional
    public Booking book(String renterId, String equipmentId, LocalDate start, LocalDate end) {
        Equipment equipment = load(equipmentId);
        long days = RentalService.validate(equipment, start, end, clock);
        long total = RentalPricing.quote((int) days, equipment.rentalRates()).totalCents();
        return bookings.insert(equipmentId, renterId, start, end, total); // DB enforces no overlap
    }

    private Equipment load(String id) {
        return catalog.findById(id).orElseThrow(() -> new DomainException.NotFound("Equipment", id));
    }
}
