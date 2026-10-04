package com.quipmarket.payments;

import com.quipmarket.shared.DomainException;
import java.util.Map;

public class PaymentDeclinedException extends DomainException {
    private final String declineCode;

    public PaymentDeclinedException(String message, String declineCode) {
        super(message);
        this.declineCode = declineCode == null ? "generic_decline" : declineCode;
    }

    @Override
    public String code() {
        return "PAYMENT_DECLINED";
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("declineCode", declineCode);
    }
}
