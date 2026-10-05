package com.quipmarket.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * contracts/money-rules.json, the cases the backend (JUnit) and the web app (Vitest) also check.
 * Runs on the JVM, on Android and on the iOS simulator, so every client shows the price the backend charges.
 */
class MoneyRulesContractTest {

    @Test
    fun platformFeeMatchesTheContract() {
        assertTrue(FEE_CASES.isNotEmpty())
        FEE_CASES.forEach { c -> assertEquals(c.feeCents, platformFeeCents(c.hammerCents), "fee(${c.hammerCents})") }
    }

    @Test
    fun rentalQuotesMatchTheContract() {
        assertTrue(QUOTE_CASES.isNotEmpty())
        QUOTE_CASES.forEach { c ->
            val q = RentalPricing.quote(c.rentalDays, c.rates)
            val label = "${c.ratesName}, ${c.rentalDays} days"
            assertEquals(c.totalCents, q.totalCents, "$label: total")
            assertEquals(Triple(c.months, c.weeks, c.days), Triple(q.months, q.weeks, q.days), "$label: blocks")
        }
    }
}
