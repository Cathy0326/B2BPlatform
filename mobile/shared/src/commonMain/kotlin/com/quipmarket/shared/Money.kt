package com.quipmarket.shared

/** Platform fee in basis points: 5.00% of the hammer price. Same constant as the backend's Escrow.FEE_BPS. */
const val FEE_BPS: Long = 500

/** Fee in integer cents, rounded half-up, exactly like Escrow.platformFee (backend) and platformFeeCents (web). */
fun platformFeeCents(hammerCents: Long): Long {
    require(hammerCents >= 0) { "Hammer price cannot be negative" }
    return (hammerCents * FEE_BPS + 5_000) / 10_000
}

/** "$1,234.56". Money stays in integer cents everywhere; this is for display only. */
fun formatCents(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = if (cents < 0) -cents else cents
    val dollars = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
    val rest = (abs % 100).toString().padStart(2, '0')
    return "${sign}\$${dollars}.${rest}"
}
