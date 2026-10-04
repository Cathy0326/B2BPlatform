package com.quipmarket.rental.internal;

import com.quipmarket.rental.Booking;
import com.quipmarket.rental.BookingConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class BookingRepository {

    /** PostgreSQL SQLSTATE for "exclusion constraint violated". */
    static final String EXCLUSION_VIOLATION = "23P01";

    private static final String SELECT = "SELECT id, equipment_id, lower(period) AS start_date, upper(period) AS end_date FROM bookings";

    private final JdbcClient jdbc;

    BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Booking> findByEquipment(String equipmentId) {
        return jdbc.sql(SELECT + " WHERE equipment_id = :id ORDER BY lower(period)").param("id", equipmentId).query(this::map).list();
    }

    /** One query for many machines (used by @BatchMapping to avoid N+1). */
    Map<String, List<Booking>> findByEquipmentIds(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.sql(SELECT + " WHERE equipment_id IN (:ids) ORDER BY lower(period)").param("ids", ids).query(this::map).list()
                .stream().collect(Collectors.groupingBy(Booking::equipmentId));
    }

    /**
     * Insert, letting the database decide whether the dates are free.
     * There is deliberately NO "check then insert" in Java: two requests could both pass the check
     * and both insert (a race). The EXCLUDE constraint makes the check and the insert one atomic step.
     *
     * The advisory lock is NOT what prevents double booking (the constraint is). It prevents a deadlock:
     * an exclusion constraint is checked after the row is inserted, so two transactions inserting
     * overlapping rows at the same instant can each wait for the other's uncommitted row, and PostgreSQL
     * aborts one with "deadlock detected" instead of an exclusion violation. Queuing bookings per machine
     * makes the loser see the winner's committed row and get a clean BOOKING_CONFLICT.
     * Different machines hash to different locks, so they never wait for each other (barring a hash collision).
     * MANDATORY: the lock is released at commit, so it only works inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Booking insert(String equipmentId, String renterId, LocalDate start, LocalDate end, long totalCents) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:eq, 0))").param("eq", equipmentId).query((rs, row) -> 1).single();
        try {
            Long id = jdbc.sql("""
                            INSERT INTO bookings (equipment_id, renter_id, period, total_cents)
                            VALUES (:eq, :renter, daterange(:start, :end, '[)'), :total)
                            RETURNING id
                            """)
                    .param("eq", equipmentId)
                    .param("renter", renterId)
                    .param("start", start)
                    .param("end", end)
                    .param("total", totalCents)
                    .query(Long.class)
                    .single();
            return new Booking(id, equipmentId, start, end);
        } catch (DataIntegrityViolationException e) {
            if (isExclusionViolation(e)) throw new BookingConflictException(equipmentId);
            throw e;
        }
    }

    private static boolean isExclusionViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private Booking map(ResultSet rs, int row) throws SQLException {
        return new Booking(
                rs.getLong("id"),
                rs.getString("equipment_id"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class));
    }
}
