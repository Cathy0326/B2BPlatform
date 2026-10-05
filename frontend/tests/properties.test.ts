import fc from 'fast-check'
import { describe, expect, it } from 'vitest'
import type { Auction } from '~/types/auction'
import type { JournalEntry, JournalLine } from '~/types/escrow'
import { bidIncrementCents, placeBid, settleResult } from '~/utils/auction'
import { assertBalanced, credit, debit, platformFeeCents, trialBalance, UnbalancedEntryError, type LedgerAccount } from '~/utils/ledger'
import { amortize } from '~/utils/loan'
import { formatCents, parseDollarsToCents } from '~/utils/money'
import { quoteRental } from '~/utils/rentalPricing'

// Property-based tests: each rule is checked against hundreds of generated inputs, and fast-check shrinks a failure
// to the smallest input that still breaks it. The example-based tests pin down known cases; these guard the rest.
// The same properties run on the backend with jqwik (backend/src/test/java/**/*Properties.java).

const rates = fc.record({
  dailyCents: fc.integer({ min: 1, max: 500_000 }),
  weeklyCents: fc.integer({ min: 1, max: 3_000_000 }),
  monthlyCents: fc.integer({ min: 1, max: 10_000_000 }),
})

describe('rental pricing (properties)', () => {
  it('is the cheapest of every month/week/day mix (brute-force oracle)', () => {
    fc.assert(fc.property(fc.integer({ min: 1, max: 120 }), rates, (days, r) => {
      let best = Number.MAX_SAFE_INTEGER
      for (let m = 0; m <= Math.floor(days / 28) + 1; m++) {
        for (let w = 0; w <= Math.floor(days / 7) + 1; w++) {
          const rest = Math.max(0, days - 28 * m - 7 * w)
          best = Math.min(best, m * r.monthlyCents + w * r.weeklyCents + rest * r.dailyCents)
        }
      }
      expect(quoteRental(days, r).totalCents).toBe(best)
    }))
  })

  it('pays for exactly its blocks, covers every day, and never beats a longer rental', () => {
    fc.assert(fc.property(fc.integer({ min: 1, max: 364 }), rates, (days, r) => {
      const q = quoteRental(days, r)
      const { months, weeks, days: d } = q.breakdown
      expect(months * r.monthlyCents + weeks * r.weeklyCents + d * r.dailyCents).toBe(q.totalCents)
      expect(months * 28 + weeks * 7 + d).toBeGreaterThanOrEqual(days)
      expect(q.savingsCents).toBeGreaterThanOrEqual(0)
      expect(quoteRental(days + 1, r).totalCents).toBeGreaterThanOrEqual(q.totalCents)
    }))
  })
})

describe('platform fee (properties)', () => {
  it('is 5% rounded to the nearest cent, between 0 and the price', () => {
    fc.assert(fc.property(fc.integer({ min: 0, max: 10_000_000_000 }), (hammer) => {
      const fee = platformFeeCents(hammer)
      expect(Math.abs(fee * 10_000 - hammer * 500)).toBeLessThanOrEqual(5_000)
      expect(fee).toBeGreaterThanOrEqual(0)
      expect(fee).toBeLessThanOrEqual(hammer)
    }))
  })
})

describe('ledger (properties)', () => {
  const codes = ['platform_cash', 'escrow:D-1', 'seller_payable:s-1', 'platform_revenue'] as const
  const accounts: LedgerAccount[] = [
    { code: 'platform_cash', type: 'ASSET', name: '' },
    { code: 'escrow:D-1', type: 'LIABILITY', name: '' },
    { code: 'seller_payable:s-1', type: 'LIABILITY', name: '' },
    { code: 'platform_revenue', type: 'REVENUE', name: '' },
  ]
  const account = fc.constantFrom(...codes)

  // A balanced entry: random debits, and the same total split into 1 to 3 credits.
  const balancedEntry: fc.Arbitrary<JournalLine[]> = fc
    .tuple(fc.array(fc.tuple(account, fc.integer({ min: 1, max: 1_000_000 })), { minLength: 1, maxLength: 3 }), fc.array(account, { minLength: 1, maxLength: 3 }))
    .chain(([debits, creditAccounts]) => {
      const total = debits.reduce((s, [, c]) => s + c, 0)
      return fc.array(fc.integer({ min: 1, max: total }), { minLength: creditAccounts.length - 1, maxLength: creditAccounts.length - 1 })
        .map((cuts) => {
          const points = [0, ...[...new Set(cuts)].sort((a, b) => a - b), total]
          const parts = points.slice(1).map((p, i) => p - points[i]!).filter((p) => p > 0)
          return [
            ...debits.map(([a, c]) => debit(a, c)),
            ...parts.map((p, i) => credit(creditAccounts[i % creditAccounts.length]!, p)),
          ]
        })
    })

  it('any sequence of balanced entries keeps the trial balance balanced', () => {
    fc.assert(fc.property(fc.array(balancedEntry, { maxLength: 40 }), (entries) => {
      entries.forEach((lines) => expect(() => assertBalanced(lines)).not.toThrow())
      const journal: JournalEntry[] = entries.map((lines, i) => ({ id: String(i), kind: 'T', reference: 'D-1', description: '', createdAt: '', lines }))
      const tb = trialBalance(accounts, journal)
      expect(tb.balanced).toBe(true)
      expect(tb.totalDebitsCents).toBe(tb.totalCreditsCents)
      // Assets equal liabilities plus revenue (the accounting equation with no expenses or equity here).
      const balanceOf = (code: string) => tb.accounts.find((a) => a.code === code)!.balanceCents
      expect(balanceOf('platform_cash')).toBe(balanceOf('escrow:D-1') + balanceOf('seller_payable:s-1') + balanceOf('platform_revenue'))
    }))
  })

  it('an entry whose debits and credits differ by any amount is rejected', () => {
    fc.assert(fc.property(balancedEntry, fc.integer({ min: 1, max: 10_000 }), (lines, off) => {
      const last = lines.at(-1)!
      const tampered = [...lines.slice(0, -1), credit(last.accountCode, last.creditCents + off)]
      expect(() => assertBalanced(tampered)).toThrow(UnbalancedEntryError)
    }))
  })
})

describe('auction engine (properties)', () => {
  const T0 = Date.UTC(2027, 0, 15, 12)
  const STARTING = 5_000_000
  const lot = (reserve: number | null): Auction => ({
    id: 'a1', equipmentId: 'eq-1', startingPriceCents: STARTING, reservePriceCents: reserve, depositCents: 500_000,
    startsAt: T0, endsAt: T0 + 3_600_000, softCloseMs: 120_000, currentPriceCents: STARTING,
    leaderId: null, leaderMaxCents: null, bids: [], extensions: 0,
  })
  const bids = fc.array(fc.record({
    bidder: fc.constantFrom('ann', 'bob', 'cy', 'dee'),
    // $1,000 steps, so different bidders often pick the same max: ties are where priority bugs hide.
    max: fc.integer({ min: 1, max: 300 }).map((k) => k * 100_000),
    at: fc.integer({ min: 1, max: 3_599 }),
  }), { minLength: 1, maxLength: 30 }).map((l) => [...l].sort((a, b) => a.at - b.at))

  it('the leader offered the most (earliest wins a tie) and pays between the runner-up and their own max', () => {
    fc.assert(fc.property(bids, fc.option(fc.integer({ min: STARTING, max: 20_000_000 })), (seq, reserve) => {
      let a = lot(reserve)
      const committed = new Map<string, { max: number, arrival: number }>()
      seq.forEach((b, arrival) => {
        const out = placeBid(a, b.bidder, b.max, T0 + b.at * 1000)
        if (!out.ok) return
        const before = a
        a = out.auction
        const prev = committed.get(b.bidder)
        if (!prev || b.max > prev.max) committed.set(b.bidder, { max: b.max, arrival })

        const leader = [...committed.entries()].sort(([, x], [, y]) => y.max - x.max || x.arrival - y.arrival)[0]![0]
        expect(a.leaderId).toBe(leader)
        expect(a.currentPriceCents).toBeLessThanOrEqual(a.leaderMaxCents!)
        const runnerUp = Math.max(STARTING, ...[...committed.entries()].filter(([k]) => k !== leader).map(([, v]) => v.max))
        expect(a.currentPriceCents).toBeGreaterThanOrEqual(runnerUp)
        expect(a.currentPriceCents).toBeGreaterThanOrEqual(before.currentPriceCents)
        expect(a.endsAt).toBeGreaterThanOrEqual(before.endsAt)
      })
      const result = settleResult(a, a.endsAt)!
      expect(result.sold).toBe(a.leaderId != null && (reserve == null || a.currentPriceCents >= reserve))
    }), { numRuns: 500 })
  })

  it('the bid increment never shrinks as the price grows', () => {
    fc.assert(fc.property(fc.nat(200_000_000), fc.nat(200_000_000), (x, y) => {
      const [lo, hi] = x <= y ? [x, y] : [y, x]
      expect(bidIncrementCents(lo)).toBeLessThanOrEqual(bidIncrementCents(hi))
    }))
  })
})

describe('loan amortization (properties)', () => {
  const loans = fc.record({
    priceCents: fc.integer({ min: 100_000, max: 200_000_000 }),
    downPct: fc.integer({ min: 0, max: 90 }),
    aprBps: fc.integer({ min: 0, max: 5_000 }),
    termMonths: fc.integer({ min: 1, max: 120 }),
  }).map(({ downPct, ...l }) => ({ ...l, downPaymentCents: Math.floor((l.priceCents * downPct) / 100) }))

  it('repays exactly the principal and every row adds up', () => {
    fc.assert(fc.property(loans, (input) => {
      const r = amortize(input)
      expect(r.schedule.reduce((s, row) => s + row.principalCents, 0)).toBe(r.principalCents)
      expect(r.schedule.at(-1)!.balanceCents).toBe(0)
      expect(r.totalPaidCents).toBe(r.principalCents + r.totalInterestCents)
      r.schedule.forEach((row) => expect(row.paymentCents).toBe(row.principalCents + row.interestCents))
    }))
  })
})

describe('money (properties)', () => {
  it('parsing what we display gives back the same cents', () => {
    fc.assert(fc.property(fc.integer({ min: 0, max: 1_000_000_000_000 }), (cents) => {
      expect(parseDollarsToCents(formatCents(cents))).toBe(cents)
    }))
  })
})
