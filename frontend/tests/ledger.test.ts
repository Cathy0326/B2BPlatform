import { describe, expect, it } from 'vitest'
import type { JournalEntry } from '~/types/escrow'
import { assertBalanced, credit, debit, platformFeeCents, trialBalance, UnbalancedEntryError } from '~/utils/ledger'

const entry = (id: string, lines: JournalEntry['lines']): JournalEntry => ({ id, kind: 'TEST', reference: 'D-1', description: '', createdAt: '', lines })

describe('assertBalanced', () => {
  it('accepts an entry whose debits equal its credits, across more than two lines', () => {
    expect(() => assertBalanced([debit('escrow:D-1', 10_000), credit('seller_payable:s-1', 9_500), credit('platform_revenue', 500)])).not.toThrow()
  })

  it.each([
    ['unbalanced', [debit('a', 100), credit('b', 99)]],
    ['a single line', [debit('a', 100)]],
    ['a negative amount', [debit('a', -100), credit('b', -100)]],
    ['a line with both sides', [{ accountCode: 'a', debitCents: 100, creditCents: 100 }, credit('b', 0)]],
    ['a zero line', [debit('a', 0), credit('b', 0)]],
    ['fractional cents', [debit('a', 10.5), credit('b', 10.5)]],
  ])('rejects %s', (_, lines) => {
    expect(() => assertBalanced(lines)).toThrow(UnbalancedEntryError)
  })
})

describe('trialBalance', () => {
  const accounts = [
    { code: 'platform_cash', type: 'ASSET' as const, name: 'Cash' },
    { code: 'escrow:D-1', type: 'LIABILITY' as const, name: 'Escrow' },
    { code: 'platform_revenue', type: 'REVENUE' as const, name: 'Fees' },
  ]

  it('computes balances on their normal side and reports balanced totals', () => {
    const tb = trialBalance(accounts, [
      entry('1', [debit('platform_cash', 10_000), credit('escrow:D-1', 10_000)]),
      entry('2', [debit('escrow:D-1', 500), credit('platform_revenue', 500)]),
    ])

    expect(tb.balanced).toBe(true)
    expect(tb.totalDebitsCents).toBe(10_500)
    expect(tb.accounts.map((a) => [a.code, a.balanceCents])).toEqual([
      ['platform_cash', 10_000], // asset: debits - credits
      ['escrow:D-1', 9_500], // liability: credits - debits
      ['platform_revenue', 500],
    ])
  })

  it('refuses lines that point at an unknown account', () => {
    expect(() => trialBalance(accounts, [entry('1', [debit('nope', 1), credit('platform_cash', 1)])])).toThrow(/Unknown account/)
  })
})

describe('platformFeeCents', () => {
  it('is 5% rounded half-up, like the backend', () => {
    expect(platformFeeCents(10_500_000)).toBe(525_000)
    expect(platformFeeCents(10)).toBe(1) // 0.5 cents rounds up
    expect(platformFeeCents(9)).toBe(0) // 0.45 cents rounds down
  })
})
