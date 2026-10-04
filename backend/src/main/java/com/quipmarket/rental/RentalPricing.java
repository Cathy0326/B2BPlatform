package com.quipmarket.rental;

import com.quipmarket.catalog.Equipment.RentalRates;

/**
 * Cheapest way to cover N consecutive days with day / week (7) / month (28) blocks.
 * LeetCode 983 "Minimum Cost For Tickets" with every day a travel day:
 *
 *   dp[i] = min(dp[i-1] + daily, dp[max(0, i-7)] + weekly, dp[max(0, i-28)] + monthly)
 *
 * Same algorithm and same test cases as frontend/app/utils/rentalPricing.ts.
 * O(N) time and space.
 */
public final class RentalPricing {

    public static final int WEEK_DAYS = 7;
    public static final int MONTH_DAYS = 28;
    public static final int MAX_DAYS = 365;

    private RentalPricing() {}

    public record Price(int rentalDays, long totalCents, int months, int weeks, int days, long naiveDailyCents) {
        public long savingsCents() {
            return naiveDailyCents - totalCents;
        }
    }

    public static Price quote(int rentalDays, RentalRates rates) {
        if (rentalDays < 1 || rentalDays > MAX_DAYS) {
            throw new IllegalArgumentException("Rental must be 1 to " + MAX_DAYS + " days");
        }
        long[] dp = new long[rentalDays + 1];
        char[] choice = new char[rentalDays + 1];

        for (int i = 1; i <= rentalDays; i++) {
            long best = dp[i - 1] + rates.dailyCents();
            char pick = 'D';
            long byWeek = dp[Math.max(0, i - WEEK_DAYS)] + rates.weeklyCents();
            if (byWeek < best) { best = byWeek; pick = 'W'; }
            long byMonth = dp[Math.max(0, i - MONTH_DAYS)] + rates.monthlyCents();
            if (byMonth < best) { best = byMonth; pick = 'M'; }
            dp[i] = best;
            choice[i] = pick;
        }

        int months = 0, weeks = 0, days = 0;
        for (int i = rentalDays; i > 0; ) {
            switch (choice[i]) {
                case 'M' -> { months++; i = Math.max(0, i - MONTH_DAYS); }
                case 'W' -> { weeks++; i = Math.max(0, i - WEEK_DAYS); }
                default -> { days++; i--; }
            }
        }
        return new Price(rentalDays, dp[rentalDays], months, weeks, days, Math.multiplyExact((long) rentalDays, rates.dailyCents()));
    }
}
