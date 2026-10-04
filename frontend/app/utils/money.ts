import type { Cents } from '~/types/equipment'

/**
 * Why integer cents?
 *   0.1 + 0.2 === 0.30000000000000004 in IEEE-754 floating point.
 *   Integers up to 2^53 are exact, so adding/subtracting cents never drifts.
 *   We convert to dollars ONLY when rendering text.
 */

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const usdWhole = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 0,
})

/** 125050 -> "$1,250.50" */
export function formatCents(cents: Cents): string {
  assertCents(cents)
  return usd.format(cents / 100)
}

/** 125050 -> "$1,251" (for listing cards where cents are noise). */
export function formatCentsWhole(cents: Cents): string {
  assertCents(cents)
  return usdWhole.format(Math.round(cents / 100))
}

/**
 * Parse user input like "1,250.5" or "$1250.50" into cents without going through
 * a float multiply (12.34 * 100 === 1233.9999999999998).
 * Returns null for anything that is not a valid non-negative amount.
 */
export function parseDollarsToCents(input: string): Cents | null {
  const cleaned = input.trim().replace(/[$,\s]/g, '')
  const match = /^(\d+)(?:\.(\d{0,2}))?$/.exec(cleaned)
  if (!match) return null
  const whole = Number(match[1])
  const fraction = Number((match[2] ?? '').padEnd(2, '0'))
  const cents = whole * 100 + fraction
  return Number.isSafeInteger(cents) ? cents : null
}

/** Whole dollars (from a number input) -> cents. */
export function dollarsToCents(dollars: number): Cents {
  return Math.round(dollars * 100)
}

/** Sum a list of cents exactly. */
export function sumCents(values: readonly Cents[]): Cents {
  return values.reduce((acc, v) => acc + v, 0)
}

export function assertCents(value: number): void {
  if (!Number.isInteger(value)) {
    throw new TypeError(`Expected integer cents, got ${value}`)
  }
}
