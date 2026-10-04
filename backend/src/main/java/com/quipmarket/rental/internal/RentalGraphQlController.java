package com.quipmarket.rental.internal;

import com.quipmarket.catalog.Equipment;
import com.quipmarket.rental.Booking;
import com.quipmarket.rental.RentalService;
import com.quipmarket.shared.CurrentUser;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
class RentalGraphQlController {

    private final RentalService rentals;
    private final BookingRepository bookings;

    RentalGraphQlController(RentalService rentals, BookingRepository bookings) {
        this.rentals = rentals;
        this.bookings = bookings;
    }

    record RentalQuoteView(int rentalDays, long totalCents, int months, int weeks, int days, long naiveDailyCents,
                           long savingsCents, boolean available, List<Booking> conflicts, LocalDate nextAvailableStart) {}

    record CreateBookingInput(String equipmentId, LocalDate start, LocalDate end) {}

    @QueryMapping
    RentalQuoteView rentalQuote(@Argument String equipmentId, @Argument LocalDate start, @Argument LocalDate end) {
        var q = rentals.quote(equipmentId, start, end);
        var p = q.price();
        return new RentalQuoteView(p.rentalDays(), p.totalCents(), p.months(), p.weeks(), p.days(), p.naiveDailyCents(),
                p.savingsCents(), q.available(), q.conflicts(), q.nextAvailableStart());
    }

    @MutationMapping
    Booking createBooking(@Argument CreateBookingInput input,
                          @ContextValue(name = CurrentUser.CONTEXT_KEY, required = false) String userId) {
        return rentals.book(CurrentUser.require(userId), input.equipmentId(), input.start(), input.end());
    }

    /**
     * Equipment.bookings for a whole list in ONE query.
     * Without @BatchMapping, `equipment { bookings }` for 12 machines = 1 + 12 queries (the N+1 problem).
     */
    @BatchMapping(typeName = "Equipment")
    Map<Equipment, List<Booking>> bookings(List<Equipment> equipment) {
        var byId = bookings.findByEquipmentIds(equipment.stream().map(Equipment::id).toList());
        var result = new LinkedHashMap<Equipment, List<Booking>>();
        equipment.forEach(e -> result.put(e, byId.getOrDefault(e.id(), List.of())));
        return result;
    }
}
