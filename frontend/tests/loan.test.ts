import { describe, expect, it } from 'vitest'
import { amortize, breakEvenMonths, LoanInputError, monthlyPaymentCents, validateLoanInput } from '~/utils/loan'

describe('monthlyPaymentCents', () => {
  it('matches the standard amortization formula', () => {
    // $100,000 at 6% for 60 months -> $1,933.28 (textbook value)
    expect(monthlyPaymentCents(10_000_000, 600, 60)).toBe(193_328)
  })

  it('handles a 0% promotional rate without dividing by zero', () => {
    expect(monthlyPaymentCents(1_200_000, 0, 12)).toBe(100_000)
  })
})

describe('amortize', () => {
  const input = { priceCents: 16_450_000, downPaymentCents: 3_290_000, aprBps: 725, termMonths: 60 }

  it('pays the balance down to exactly zero', () => {
    const r = amortize(input)
    expect(r.schedule).toHaveLength(60)
    expect(r.schedule.at(-1)!.balanceCents).toBe(0)
  })

  it('principal portions add up to the amount financed', () => {
    const r = amortize(input)
    const principalSum = r.schedule.reduce((s, row) => s + row.principalCents, 0)
    expect(principalSum).toBe(r.principalCents)
    expect(r.totalPaidCents).toBe(r.principalCents + r.totalInterestCents)
    expect(r.totalCostCents).toBe(r.totalPaidCents + input.downPaymentCents)
  })

  it('every row is integer cents and interest shrinks over time', () => {
    const r = amortize(input)
    for (const row of r.schedule) {
      expect(Number.isInteger(row.interestCents)).toBe(true)
      expect(row.paymentCents).toBe(row.principalCents + row.interestCents)
    }
    expect(r.schedule[0]!.interestCents).toBeGreaterThan(r.schedule.at(-1)!.interestCents)
  })

  it('works at 0% APR with an uneven split', () => {
    const r = amortize({ priceCents: 1_000, downPaymentCents: 0, aprBps: 0, termMonths: 3 })
    expect(r.totalInterestCents).toBe(0)
    expect(r.schedule.map((s) => s.paymentCents)).toEqual([334, 334, 332])
  })

  it('rejects invalid input', () => {
    expect(validateLoanInput({ priceCents: 100, downPaymentCents: 100, aprBps: 500, termMonths: 12 })).not.toHaveLength(0)
    expect(() => amortize({ priceCents: 0, downPaymentCents: 0, aprBps: 500, termMonths: 12 })).toThrow(LoanInputError)
  })
})

describe('breakEvenMonths', () => {
  it('rounds up to whole months', () => {
    expect(breakEvenMonths(1_000_000, 300_000)).toBe(4)
    expect(breakEvenMonths(1_000_000, 0)).toBeNull()
  })
})
