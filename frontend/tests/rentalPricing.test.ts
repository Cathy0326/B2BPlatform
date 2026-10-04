import { describe, expect, it } from 'vitest'
import { describeBreakdown, quoteRental } from '~/utils/rentalPricing'

const rates = { dailyCents: 110_000, weeklyCents: 320_000, monthlyCents: 800_000 }

describe('quoteRental (LC 983 Minimum Cost For Tickets)', () => {
  it('uses daily rate for short rentals', () => {
    const q = quoteRental(2, rates)
    expect(q.totalCents).toBe(220_000)
    expect(q.breakdown).toEqual({ months: 0, weeks: 0, days: 2 })
  })

  it('buys a full week when it beats daily (overhang allowed)', () => {
    // 3 days daily = $3,300 > 1 week = $3,200
    const q = quoteRental(3, rates)
    expect(q.totalCents).toBe(320_000)
    expect(q.breakdown).toEqual({ months: 0, weeks: 1, days: 0 })
  })

  it('combines blocks: 31 days = 1 month + 3 days? no, 1 month + 1 week is cheaper', () => {
    // 1M + 3D = 8,000 + 3,300 = 11,300 ; 1M + 1W = 8,000 + 3,200 = 11,200
    const q = quoteRental(31, rates)
    expect(q.totalCents).toBe(1_120_000)
    expect(describeBreakdown(q.breakdown)).toBe('1 month + 1 week')
  })

  it('reports savings versus the plain daily rate', () => {
    const q = quoteRental(28, rates)
    expect(q.totalCents).toBe(800_000)
    expect(q.naiveDailyCents).toBe(28 * 110_000)
    expect(q.savingsCents).toBe(28 * 110_000 - 800_000)
  })

  it('reconstructed breakdown always prices to the DP total', () => {
    for (let days = 1; days <= 120; days++) {
      const q = quoteRental(days, rates)
      const { months, weeks, days: d } = q.breakdown
      expect(months * rates.monthlyCents + weeks * rates.weeklyCents + d * rates.dailyCents).toBe(q.totalCents)
      expect(months * 28 + weeks * 7 + d).toBeGreaterThanOrEqual(days)
    }
  })

  it('rejects zero-day rentals', () => {
    expect(() => quoteRental(0, rates)).toThrow(RangeError)
  })
})
