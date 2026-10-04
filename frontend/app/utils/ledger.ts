import type { AccountBalance, JournalEntry, JournalLine, TrialBalance } from '~/types/escrow'

/**
 * Double-entry rules, mirroring the backend's PostgreSQL constraints (V4 migration):
 *   - every line is one-sided and non-negative
 *   - an entry has at least two lines and Σ debits = Σ credits
 *   - balances are computed from journal lines on every read (no stored balance column)
 */

export type AccountType = AccountBalance['type']

export interface LedgerAccount {
  code: string
  type: AccountType
  name: string
}

export class UnbalancedEntryError extends Error {}

export function assertBalanced(lines: JournalLine[]): void {
  if (lines.length < 2) throw new UnbalancedEntryError('A journal entry needs at least two lines')
  for (const l of lines) {
    if (!Number.isSafeInteger(l.debitCents) || !Number.isSafeInteger(l.creditCents)) {
      throw new UnbalancedEntryError(`Amounts must be integer cents (${l.accountCode})`)
    }
    if (l.debitCents < 0 || l.creditCents < 0) throw new UnbalancedEntryError(`Negative amount on ${l.accountCode}`)
    if ((l.debitCents > 0) === (l.creditCents > 0)) {
      throw new UnbalancedEntryError(`Line on ${l.accountCode} must be either a debit or a credit`)
    }
  }
  const debits = lines.reduce((s, l) => s + l.debitCents, 0)
  const credits = lines.reduce((s, l) => s + l.creditCents, 0)
  if (debits !== credits) throw new UnbalancedEntryError(`Unbalanced entry: debits ${debits} ≠ credits ${credits}`)
}

export const debit = (accountCode: string, cents: number): JournalLine => ({ accountCode, debitCents: cents, creditCents: 0 })
export const credit = (accountCode: string, cents: number): JournalLine => ({ accountCode, debitCents: 0, creditCents: cents })

/** Assets and expenses grow with debits; liabilities, equity and revenue grow with credits. */
function normalBalance(type: AccountType, debits: number, credits: number): number {
  return type === 'ASSET' || type === 'EXPENSE' ? debits - credits : credits - debits
}

export function trialBalance(accounts: LedgerAccount[], journal: JournalEntry[]): TrialBalance {
  const totals = new Map(accounts.map((a) => [a.code, { d: 0, c: 0 }]))
  for (const e of journal) {
    for (const l of e.lines) {
      const t = totals.get(l.accountCode)
      if (!t) throw new Error(`Unknown account ${l.accountCode}`)
      t.d += l.debitCents
      t.c += l.creditCents
    }
  }
  const rows: AccountBalance[] = accounts.map((a) => {
    const t = totals.get(a.code)!
    return { code: a.code, type: a.type, name: a.name, debitsCents: t.d, creditsCents: t.c, balanceCents: normalBalance(a.type, t.d, t.c) }
  })
  const totalDebitsCents = rows.reduce((s, r) => s + r.debitsCents, 0)
  const totalCreditsCents = rows.reduce((s, r) => s + r.creditsCents, 0)
  return { accounts: rows, totalDebitsCents, totalCreditsCents, balanced: totalDebitsCents === totalCreditsCents }
}

/** Platform fee in basis points, rounded half-up, exactly like Escrow.platformFee in the backend. */
export const FEE_BPS = 500
export function platformFeeCents(hammerCents: number): number {
  return Math.floor((hammerCents * FEE_BPS + 5_000) / 10_000)
}
