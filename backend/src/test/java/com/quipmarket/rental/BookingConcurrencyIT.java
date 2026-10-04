package com.quipmarket.rental;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.support.IntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class BookingConcurrencyIT {

    @Autowired RentalService rentals;

    /**
     * 50 renters press "Book" for the SAME machine and the SAME week at the same instant.
     * Exactly one must win; the other 49 must get BOOKING_CONFLICT. No application lock exists:
     * the PostgreSQL EXCLUDE constraint is what guarantees this.
     */
    @Test
    void fiftyConcurrentRequestsForTheSameDatesProduceExactlyOneBooking() throws Exception {
        int threads = 50;
        LocalDate start = LocalDate.parse("2027-03-01"), end = LocalDate.parse("2027-03-08");
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var wins = new AtomicInteger();
        var conflicts = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String renter = "renter-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(); // release all threads together to maximize contention
                    try {
                        rentals.book(renter, "eq-1008", start, end);
                        wins.incrementAndGet();
                    } catch (BookingConflictException e) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) f.get();
        }

        assertThat(wins.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
    }

    @Test
    void backToBackBookingsAreAllowedButOverlapsAreNot() {
        rentals.book("r1", "eq-1011", LocalDate.parse("2027-04-01"), LocalDate.parse("2027-04-05"));
        rentals.book("r2", "eq-1011", LocalDate.parse("2027-04-05"), LocalDate.parse("2027-04-08")); // touching: OK
        assertThatThrownBy(() -> rentals.book("r3", "eq-1011", LocalDate.parse("2027-04-07"), LocalDate.parse("2027-04-10")))
                .isInstanceOf(BookingConflictException.class);
    }

    @Test
    void quoteSuggestsNextFreeWindowUsingSeededBookings() {
        // eq-1001 is booked Oct 6-13, Oct 13-20 and Nov 2-9 in the demo seed.
        var q = rentals.quote("eq-1001", LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-15"));
        assertThat(q.available()).isFalse();
        assertThat(q.conflicts()).hasSize(2);
        assertThat(q.nextAvailableStart()).isEqualTo("2026-10-20");
        assertThat(q.price().totalCents()).isEqualTo(320_000); // one week
    }
}
