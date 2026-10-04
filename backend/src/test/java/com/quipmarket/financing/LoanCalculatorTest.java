package com.quipmarket.financing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.quipmarket.shared.DomainException;
import org.junit.jupiter.api.Test;

class LoanCalculatorTest {

    @Test
    void textbookPayment() {
        // $100,000 at 6% for 60 months -> $1,933.28
        assertThat(LoanCalculator.monthlyPaymentCents(10_000_000, 600, 60)).isEqualTo(193_328);
    }

    @Test
    void zeroPercentDoesNotDivideByZero() {
        assertThat(LoanCalculator.monthlyPaymentCents(1_200_000, 0, 12)).isEqualTo(100_000);
        var q = LoanCalculator.amortize(new LoanCalculator.Input(1_000, 0, 0, 3));
        assertThat(q.schedule()).extracting(LoanCalculator.Row::paymentCents).containsExactly(334L, 334L, 332L);
    }

    @Test
    void balanceEndsAtExactlyZeroAndTotalsReconcile() {
        var in = new LoanCalculator.Input(16_450_000, 3_290_000, 725, 60);
        var q = LoanCalculator.amortize(in);
        assertThat(q.schedule()).hasSize(60);
        assertThat(q.schedule().getLast().balanceCents()).isZero();
        assertThat(q.schedule().stream().mapToLong(LoanCalculator.Row::principalCents).sum()).isEqualTo(q.principalCents());
        assertThat(q.totalPaidCents()).isEqualTo(q.principalCents() + q.totalInterestCents());
        assertThat(q.totalCostCents()).isEqualTo(q.totalPaidCents() + in.downPaymentCents());
    }

    @Test
    void matchesTheFrontendForTheDemoMachine() {
        // Same inputs as the Financing page screenshot: $164,500, 20% down, 7.25%, 60 months.
        var q = LoanCalculator.amortize(new LoanCalculator.Input(16_450_000, 3_290_000, 725, 60));
        assertThat(q.monthlyPaymentCents()).isEqualTo(262_139);
        assertThat(q.totalInterestCents()).isEqualTo(2_568_322);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> LoanCalculator.amortize(new LoanCalculator.Input(100, 100, 500, 12)))
                .isInstanceOf(DomainException.InvalidInput.class);
        assertThatThrownBy(() -> LoanCalculator.amortize(new LoanCalculator.Input(1000, 0, 500, 0)))
                .isInstanceOf(DomainException.InvalidInput.class);
    }
}
