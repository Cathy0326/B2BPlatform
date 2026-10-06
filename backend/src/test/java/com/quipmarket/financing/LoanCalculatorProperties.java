package com.quipmarket.financing;

import static org.assertj.core.api.Assertions.assertThat;

import com.quipmarket.financing.LoanCalculator.Input;
import com.quipmarket.financing.LoanCalculator.Row;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Amortization schedules must add up to the cent for every valid loan, not only the documented examples. */
class LoanCalculatorProperties {

    @Provide
    Arbitrary<Input> loans() {
        return Combinators.combine(
                Arbitraries.longs().between(100_000, 200_000_000),
                Arbitraries.integers().between(0, 90),          // down payment, % of the price
                Arbitraries.integers().between(0, 5_000),       // APR in basis points (0% to 50%)
                Arbitraries.integers().between(1, 120))
                .as((price, downPct, apr, term) -> new Input(price, price * downPct / 100, apr, term));
    }

    @Property
    void scheduleRepaysExactlyThePrincipal(@ForAll("loans") Input in) {
        var q = LoanCalculator.amortize(in);
        assertThat(q.schedule().stream().mapToLong(Row::principalCents).sum()).isEqualTo(q.principalCents());
        assertThat(q.schedule().getLast().balanceCents()).isZero();
        assertThat(q.totalPaidCents()).isEqualTo(q.principalCents() + q.totalInterestCents());
        assertThat(q.totalCostCents()).isEqualTo(q.totalPaidCents() + in.downPaymentCents());
        assertThat(q.schedule()).hasSizeLessThanOrEqualTo(in.termMonths());
    }

    @Property
    void balanceOnlyGoesDownAndEveryRowAddsUp(@ForAll("loans") Input in) {
        long previous = in.priceCents() - in.downPaymentCents();
        for (Row row : LoanCalculator.amortize(in).schedule()) {
            assertThat(row.paymentCents()).isEqualTo(row.principalCents() + row.interestCents());
            assertThat(row.interestCents()).isGreaterThanOrEqualTo(0);
            assertThat(row.balanceCents()).isEqualTo(previous - row.principalCents()).isLessThanOrEqualTo(previous);
            previous = row.balanceCents();
        }
    }

    @Property
    void zeroPercentMeansZeroInterest(@ForAll("loans") Input in) {
        var q = LoanCalculator.amortize(new Input(in.priceCents(), in.downPaymentCents(), 0, in.termMonths()));
        assertThat(q.totalInterestCents()).isZero();
    }
}
