package com.quipmarket.shared;

import java.util.Map;
import org.springframework.graphql.execution.ErrorType;

/**
 * Base class for errors the client is expected to handle.
 * {@link GraphQlErrorMapping} turns it into a GraphQL error with {@code extensions.code}.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    /** Stable, machine-readable code, e.g. BOOKING_CONFLICT. Clients switch on this, never on the message. */
    public abstract String code();

    public ErrorType errorType() {
        return ErrorType.BAD_REQUEST;
    }

    public Map<String, Object> details() {
        return Map.of();
    }

    public static final class NotFound extends DomainException {
        public NotFound(String what, String id) {
            super(what + " " + id + " not found");
        }
        @Override public String code() { return "NOT_FOUND"; }
        @Override public ErrorType errorType() { return ErrorType.NOT_FOUND; }
    }

    public static final class InvalidInput extends DomainException {
        public InvalidInput(String message) {
            super(message);
        }
        @Override public String code() { return "INVALID_INPUT"; }
    }

    public static final class Unauthenticated extends DomainException {
        public Unauthenticated() {
            super("Sign in to do this.");
        }
        @Override public String code() { return "UNAUTHENTICATED"; }
        @Override public ErrorType errorType() { return ErrorType.UNAUTHORIZED; }
    }
}
