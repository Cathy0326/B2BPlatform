import type { Cents } from '~/types/equipment'

/**
 * Equipment financing (fixed-rate, fully amortizing loan).
 *
 *            P * r * (1 + r)^n
 *   M  =  ----------------------        r = APR / 12, n = number of months
 *              (1 + r)^n - 1
 *
 * Edge case: r = 0  ->  M = P / n   (the formula would divide 0 by 0).
 *
 * Rates are passed in BASIS POINTS (1 bp = 0.01%), so 7.25% APR = 725.
 * That keeps the input an integer, the same trick as cents.
 */

export interface LoanInput {
  priceCents: Cents
  downPaymentCents: Cents
  aprBps: number
  termMonths: number
}

export interface AmortizationRow {
  month: number
  paymentCents: Cents
  principalCents: Cents
  interestCents: Cents
  balanceCents: Cents
}

export interface LoanResult {
  principalCents: Cents
  monthlyPaymentCents: Cents
  totalInterestCents: Cents
  totalPaidCents: Cents
  /** Down payment + all monthly payments. */
  totalCostCents: Cents
  schedule: AmortizationRow[]
}

export class LoanInputError extends Error {}

export function validateLoanInput(input: LoanInput): string[] {
  const errors: string[] = []
  const { priceCents, downPaymentCents, aprBps, termMonths } = input
  if (!Number.isInteger(priceCents) || priceCents <= 0) errors.push('Price must be greater than 0.')
  if (!Number.isInteger(downPaymentCents) || downPaymentCents < 0) errors.push('Down payment cannot be negative.')
  if (downPaymentCents >= priceCents) errors.push('Down payment must be less than the price.')
  if (!Number.isInteger(aprBps) || aprBps < 0 || aprBps > 5000) errors.push('APR must be between 0% and 50%.')
  if (!Number.isInteger(termMonths) || termMonths < 1 || termMonths > 120) errors.push('Term must be 1 to 120 months.')
  return errors
}

/** Monthly payment in cents, rounded half-up to the nearest cent. */
export function monthlyPaymentCents(principalCents: Cents, aprBps: number, termMonths: number): Cents {
  if (aprBps === 0) return Math.ceil(principalCents / termMonths)
  const r = aprBps / 10_000 / 12
  const growth = Math.pow(1 + r, termMonths)
  return Math.round((principalCents * r * growth) / (growth - 1))
}

/**
 * Build the full schedule in integer cents.
 * Each month: interest = round(balance * r); principal = payment - interest.
 * Rounding leaves a few cents of residue, so the LAST payment is adjusted
 * to bring the balance to exactly 0 (this is what real lenders do).
 */
export function amortize(input: LoanInput): LoanResult {
  const errors = validateLoanInput(input)
  if (errors.length) throw new LoanInputError(errors.join(' '))

  const principal = input.priceCents - input.downPaymentCents
  const r = input.aprBps / 10_000 / 12
  const payment = monthlyPaymentCents(principal, input.aprBps, input.termMonths)

  const schedule: AmortizationRow[] = []
  let balance = principal
  let totalInterest = 0
  let totalPaid = 0

  for (let month = 1; month <= input.termMonths; month++) {
    const interest = Math.round(balance * r)
    const isLast = month === input.termMonths
    let principalPart = payment - interest
    if (isLast || principalPart > balance) principalPart = balance
    const thisPayment = principalPart + interest

    balance -= principalPart
    totalInterest += interest
    totalPaid += thisPayment
    schedule.push({
      month,
      paymentCents: thisPayment,
      principalCents: principalPart,
      interestCents: interest,
      balanceCents: balance,
    })
    if (balance === 0) break
  }

  return {
    principalCents: principal,
    monthlyPaymentCents: payment,
    totalInterestCents: totalInterest,
    totalPaidCents: totalPaid,
    totalCostCents: totalPaid + input.downPaymentCents,
    schedule,
  }
}

/**
 * Buy-vs-rent break-even: how many months of renting at `monthlyRentCents`
 * cost as much as buying outright (ignores resale value, so it is conservative).
 */
export function breakEvenMonths(totalCostCents: Cents, monthlyRentCents: Cents): number | null {
  if (monthlyRentCents <= 0) return null
  return Math.ceil(totalCostCents / monthlyRentCents)
}
