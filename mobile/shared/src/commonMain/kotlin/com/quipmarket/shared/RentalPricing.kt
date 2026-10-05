package com.quipmarket.shared

data class RentalRates(val dailyCents: Long, val weeklyCents: Long, val monthlyCents: Long)

data class RentalQuote(
    val rentalDays: Int,
    val totalCents: Long,
    val months: Int,
    val weeks: Int,
    val days: Int,
    /** What the customer would pay at the plain daily rate, to show the saving. */
    val naiveDailyCents: Long,
) {
    val savingsCents: Long get() = naiveDailyCents - totalCents

    /** "1 month + 2 weeks + 3 days" */
    fun describe(): String = listOf(months to "month", weeks to "week", days to "day")
        .filter { (n, _) -> n > 0 }
        .joinToString(" + ") { (n, word) -> "$n $word${if (n == 1) "" else "s"}" }
}

/**
 * Cheapest way to cover N consecutive days with day / week (7) / month (28) blocks, as in the backend
 * (RentalPricing.java) and the web app (rentalPricing.ts). LeetCode 983 "Minimum Cost For Tickets"
 * with every day a travel day:
 *
 *   dp[i] = min(dp[i-1] + daily, dp[max(0, i-7)] + weekly, dp[max(0, i-28)] + monthly)
 *
 * A block may overhang the end (a week can be cheaper than 5 days). O(N) time and space.
 */
object RentalPricing {
    const val WEEK_DAYS = 7
    const val MONTH_DAYS = 28
    const val MAX_DAYS = 365

    fun quote(rentalDays: Int, rates: RentalRates): RentalQuote {
        require(rentalDays in 1..MAX_DAYS) { "Rental must be 1 to $MAX_DAYS days" }
        val dp = LongArray(rentalDays + 1)
        val choice = CharArray(rentalDays + 1)

        for (i in 1..rentalDays) {
            var best = dp[i - 1] + rates.dailyCents
            var pick = 'D'
            val byWeek = dp[maxOf(0, i - WEEK_DAYS)] + rates.weeklyCents
            if (byWeek < best) { best = byWeek; pick = 'W' }
            val byMonth = dp[maxOf(0, i - MONTH_DAYS)] + rates.monthlyCents
            if (byMonth < best) { best = byMonth; pick = 'M' }
            dp[i] = best
            choice[i] = pick
        }

        // Walk the choices backwards to find which blocks were bought.
        var months = 0
        var weeks = 0
        var days = 0
        var i = rentalDays
        while (i > 0) {
            when (choice[i]) {
                'M' -> { months++; i = maxOf(0, i - MONTH_DAYS) }
                'W' -> { weeks++; i = maxOf(0, i - WEEK_DAYS) }
                else -> { days++; i-- }
            }
        }
        return RentalQuote(rentalDays, dp[rentalDays], months, weeks, days, rentalDays * rates.dailyCents)
    }
}
