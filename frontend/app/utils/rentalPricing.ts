import type { Cents, RentalRates } from '~/types/equipment'

/**
 * Cheapest way to cover N consecutive rental days with day / week / month blocks.
 * This is NeetCode / LeetCode 983 "Minimum Cost For Tickets" where every day is a travel day.
 *
 *   dp[i] = cheapest cost to cover the first i days
 *   dp[0] = 0
 *   dp[i] = min( dp[i-1]          + daily,
 *                dp[max(0, i-7)]  + weekly,
 *                dp[max(0, i-28)] + monthly )
 *
 * max(0, ...) means a block is allowed to "overhang": paying for a full week to cover
 * the last 5 days is fine if a week is cheaper than 5 daily rates.
 *
 * Time O(N), space O(N). N is at most a few hundred days, so this is instant.
 */

export const WEEK_DAYS = 7
export const MONTH_DAYS = 28

export interface RentalBreakdown {
  months: number
  weeks: number
  days: number
}

export interface RentalQuote {
  rentalDays: number
  totalCents: Cents
  breakdown: RentalBreakdown
  /** What the customer would pay at the plain daily rate, used to show savings. */
  naiveDailyCents: Cents
  savingsCents: Cents
}

type Choice = 'D' | 'W' | 'M'

export function quoteRental(rentalDays: number, rates: RentalRates): RentalQuote {
  if (!Number.isInteger(rentalDays) || rentalDays < 1) {
    throw new RangeError('Rental must be at least 1 day.')
  }
  const { dailyCents, weeklyCents, monthlyCents } = rates

  const dp = new Array<number>(rentalDays + 1).fill(0)
  const choice = new Array<Choice>(rentalDays + 1)

  for (let i = 1; i <= rentalDays; i++) {
    let best = dp[i - 1]! + dailyCents
    let pick: Choice = 'D'

    const byWeek = dp[Math.max(0, i - WEEK_DAYS)]! + weeklyCents
    if (byWeek < best) {
      best = byWeek
      pick = 'W'
    }
    const byMonth = dp[Math.max(0, i - MONTH_DAYS)]! + monthlyCents
    if (byMonth < best) {
      best = byMonth
      pick = 'M'
    }
    dp[i] = best
    choice[i] = pick
  }

  // Walk the choices backwards to reconstruct which blocks were bought.
  const breakdown: RentalBreakdown = { months: 0, weeks: 0, days: 0 }
  let i = rentalDays
  while (i > 0) {
    const c = choice[i]!
    if (c === 'M') {
      breakdown.months++
      i = Math.max(0, i - MONTH_DAYS)
    } else if (c === 'W') {
      breakdown.weeks++
      i = Math.max(0, i - WEEK_DAYS)
    } else {
      breakdown.days++
      i -= 1
    }
  }

  const naiveDailyCents = rentalDays * dailyCents
  return {
    rentalDays,
    totalCents: dp[rentalDays]!,
    breakdown,
    naiveDailyCents,
    savingsCents: naiveDailyCents - dp[rentalDays]!,
  }
}

/** "1 month + 2 weeks + 3 days" */
export function describeBreakdown(b: RentalBreakdown): string {
  const parts: string[] = []
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`
  if (b.months) parts.push(plural(b.months, 'month'))
  if (b.weeks) parts.push(plural(b.weeks, 'week'))
  if (b.days) parts.push(plural(b.days, 'day'))
  return parts.join(' + ')
}
