package com.quipmarket.financing;

import com.quipmarket.shared.DomainException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixed-rate, fully amortizing loan. Same algorithm as frontend/app/utils/loan.ts.
 *
 *          P * r * (1 + r)^n
 *   M  =  -------------------      r = APR / 12, n = months; r = 0 -> M = ceil(P / n)
 *            (1 + r)^n - 1
 *
 * Money stays in long cents. The rate factor uses BigDecimal with 34 significant digits
 * (DECIMAL128) so (1 + r)^n has no binary-float error, then each amount is rounded HALF_UP
 * to a whole cent, matching the frontend's Math.round.
 * (Banks often use HALF_EVEN, "banker's rounding", for aggregates to avoid upward bias.)
 */
public final class LoanCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS_PER_YEAR_TO_MONTHLY = BigDecimal.valueOf(10_000L * 12);

    private LoanCalculator() {}

    public record Input(long priceCents, long downPaymentCents, int aprBps, int termMonths) {}

    public record Row(int month, long paymentCents, long principalCents, long interestCents, long balanceCents) {}

    public record Quote(long principalCents, long monthlyPaymentCents, long totalInterestCents, long totalPaidCents,
                        long totalCostCents, List<Row> schedule) {}

    public static void validate(Input in) {
        if (in.priceCents() <= 0) throw new DomainException.InvalidInput("Price must be greater than 0.");
        if (in.downPaymentCents() < 0) throw new DomainException.InvalidInput("Down payment cannot be negative.");
        if (in.downPaymentCents() >= in.priceCents()) throw new DomainException.InvalidInput("Down payment must be less than the price.");
        if (in.aprBps() < 0 || in.aprBps() > 5000) throw new DomainException.InvalidInput("APR must be between 0% and 50%.");
        if (in.termMonths() < 1 || in.termMonths() > 120) throw new DomainException.InvalidInput("Term must be 1 to 120 months.");
    }

    static BigDecimal monthlyRate(int aprBps) {
        return BigDecimal.valueOf(aprBps).divide(BPS_PER_YEAR_TO_MONTHLY, MC);
    }

    public static long monthlyPaymentCents(long principalCents, int aprBps, int termMonths) {
        if (aprBps == 0) return Math.ceilDiv(principalCents, termMonths);
        BigDecimal r = monthlyRate(aprBps);
        BigDecimal growth = BigDecimal.ONE.add(r).pow(termMonths, MC);
        BigDecimal m = BigDecimal.valueOf(principalCents).multiply(r, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE), MC);
        return m.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static Quote amortize(Input in) {
        validate(in);
        long principal = in.priceCents() - in.downPaymentCents();
        BigDecimal r = monthlyRate(in.aprBps());
        long payment = monthlyPaymentCents(principal, in.aprBps(), in.termMonths());

        List<Row> schedule = new ArrayList<>(in.termMonths());
        long balance = principal, totalInterest = 0, totalPaid = 0;
        for (int month = 1; month <= in.termMonths(); month++) {
            long interest = BigDecimal.valueOf(balance).multiply(r, MC).setScale(0, RoundingMode.HALF_UP).longValueExact();
            long principalPart = payment - interest;
            // Last payment absorbs the rounding residue so the balance lands on exactly 0.
            if (month == in.termMonths() || principalPart > balance) principalPart = balance;
            long thisPayment = principalPart + interest;
            balance -= principalPart;
            totalInterest += interest;
            totalPaid += thisPayment;
            schedule.add(new Row(month, thisPayment, principalPart, interest, balance));
            if (balance == 0) break;
        }
        return new Quote(principal, payment, totalInterest, totalPaid, totalPaid + in.downPaymentCents(), List.copyOf(schedule));
    }
}
