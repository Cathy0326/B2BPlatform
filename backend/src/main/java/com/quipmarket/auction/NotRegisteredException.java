package com.quipmarket.auction;

import com.quipmarket.shared.DomainException;
import org.springframework.graphql.execution.ErrorType;

public class NotRegisteredException extends DomainException {
    public NotRegisteredException() {
        super("Place a refundable deposit hold before bidding.");
    }

    public NotRegisteredException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "NOT_REGISTERED";
    }

    @Override
    public ErrorType errorType() {
        return ErrorType.FORBIDDEN;
    }
}
