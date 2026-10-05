package com.quipmarket.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RentalPricingTest {
    private val rates = RentalRates(dailyCents = 110_000, weeklyCents = 320_000, monthlyCents = 800_000)

    @Test
    fun breakdownAlwaysPricesToTheTotalAndCoversEveryDay() {
        for (n in 1..RentalPricing.MAX_DAYS) {
            val q = RentalPricing.quote(n, rates)
            assertEquals(q.totalCents, q.months * rates.monthlyCents + q.weeks * rates.weeklyCents + q.days * rates.dailyCents, "$n days")
            assertTrue(q.months * 28 + q.weeks * 7 + q.days >= n, "$n days covered")
            assertTrue(q.savingsCents >= 0, "$n days never costs more than daily")
        }
    }

    @Test
    fun describesTheBlocks() {
        assertEquals("1 month + 1 week", RentalPricing.quote(31, rates).describe())
        assertEquals("2 days", RentalPricing.quote(2, rates).describe())
    }

    @Test
    fun rejectsOutOfRangeLengths() {
        assertFailsWith<IllegalArgumentException> { RentalPricing.quote(0, rates) }
        assertFailsWith<IllegalArgumentException> { RentalPricing.quote(RentalPricing.MAX_DAYS + 1, rates) }
    }
}
