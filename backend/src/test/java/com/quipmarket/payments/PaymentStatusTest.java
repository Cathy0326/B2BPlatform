package com.quipmarket.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.payments.Payment.Status;
import org.junit.jupiter.api.Test;

/** Webhooks arrive late and out of order; a payment status must only ever move forward. */
class PaymentStatusTest {

    @Test
    void pendingPaymentsCanMoveToAnyLaterState() {
        assertThat(Status.PROCESSING.canMoveTo(Status.AUTHORIZED)).isTrue();
        assertThat(Status.PROCESSING.canMoveTo(Status.REQUIRES_ACTION)).isTrue();
        assertThat(Status.REQUIRES_ACTION.canMoveTo(Status.AUTHORIZED)).isTrue();
        assertThat(Status.REQUIRES_ACTION.canMoveTo(Status.FAILED)).isTrue();
        assertThat(Status.PROCESSING.canMoveTo(Status.PROCESSING)).isFalse();
        assertThat(Status.REQUIRES_ACTION.canMoveTo(Status.REQUIRES_ACTION)).isFalse();
        assertThat(Status.REQUIRES_ACTION.canMoveTo(Status.PROCESSING)).isFalse();
    }

    @Test
    void anAuthorizationCanOnlyBeCapturedOrCanceled() {
        assertThat(Status.AUTHORIZED.canMoveTo(Status.CAPTURED)).isTrue();
        assertThat(Status.AUTHORIZED.canMoveTo(Status.CANCELED)).isTrue();
        assertThat(Status.AUTHORIZED.canMoveTo(Status.PROCESSING)).isFalse();
        assertThat(Status.AUTHORIZED.canMoveTo(Status.FAILED)).isFalse();
    }

    @Test
    void finalStatesNeverChange() {
        for (Status end : new Status[] {Status.CAPTURED, Status.CANCELED, Status.FAILED}) {
            for (Status next : Status.values()) assertThat(end.canMoveTo(next)).as("%s -> %s", end, next).isFalse();
        }
    }
}
