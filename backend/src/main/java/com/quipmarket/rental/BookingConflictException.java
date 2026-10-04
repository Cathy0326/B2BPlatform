package com.quipmarket.rental;

import com.quipmarket.shared.DomainException;
import java.util.Map;

/** Raised when PostgreSQL's EXCLUDE constraint rejects an overlapping booking. */
public class BookingConflictException extends DomainException {

    private final String equipmentId;

    public BookingConflictException(String equipmentId) {
        super("Those dates overlap an existing booking.");
        this.equipmentId = equipmentId;
    }

    @Override
    public String code() {
        return "BOOKING_CONFLICT";
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("equipmentId", equipmentId);
    }
}
