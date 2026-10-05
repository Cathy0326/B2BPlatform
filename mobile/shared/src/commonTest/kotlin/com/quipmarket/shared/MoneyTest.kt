package com.quipmarket.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun formatsCentsWithThousandsSeparators() {
        assertEquals("$0.00", formatCents(0))
        assertEquals("$0.05", formatCents(5))
        assertEquals("$1,100.00", formatCents(110_000))
        assertEquals("$1,234,567.89", formatCents(123_456_789))
        assertEquals("-$12.30", formatCents(-1_230))
    }

    @Test
    fun negativeHammerPriceIsRejected() {
        assertFailsWith<IllegalArgumentException> { platformFeeCents(-1) }
    }
}
