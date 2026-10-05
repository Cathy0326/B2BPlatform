import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { platformFeeCents } from '~/utils/ledger'
import { quoteRental } from '~/utils/rentalPricing'

// The money rules shared by every client. The same file is checked by the backend (JUnit) and the mobile app
// (Kotlin, on JVM, Android and iOS), so the price this app shows is the price the backend charges.
interface Rates { dailyCents: number, weeklyCents: number, monthlyCents: number }
const rules = JSON.parse(readFileSync(new URL('../../contracts/money-rules.json', import.meta.url), 'utf8')) as {
  platformFee: { cases: { hammerCents: number, feeCents: number }[] }
  rentalQuote: {
    rates: Record<string, Rates>
    cases: { rates: string, rentalDays: number, totalCents: number, months: number, weeks: number, days: number }[]
  }
}

describe('money rules contract (contracts/money-rules.json)', () => {
  it.each(rules.platformFee.cases)('fee($hammerCents) = $feeCents', ({ hammerCents, feeCents }) => {
    expect(platformFeeCents(hammerCents)).toBe(feeCents)
  })

  it.each(rules.rentalQuote.cases)('$rates, $rentalDays days', ({ rates, rentalDays, totalCents, months, weeks, days }) => {
    const q = quoteRental(rentalDays, rules.rentalQuote.rates[rates]!)
    expect(q.totalCents).toBe(totalCents)
    expect(q.breakdown).toEqual({ months, weeks, days })
  })
})
