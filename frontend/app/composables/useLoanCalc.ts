import type { Cents } from '~/types/equipment'
import { amortize, validateLoanInput, type LoanResult } from '~/utils/loan'
import { dollarsToCents } from '~/utils/money'

export interface LoanFormDefaults {
  priceCents: Cents
  downPaymentPercent?: number
  aprPercent?: number
  termMonths?: number
}

/**
 * Bridges human-friendly form fields (dollars, percent) and the cents/bps math in utils/loan.
 * The form holds whatever the user typed; conversion + validation happens in one computed,
 * so the result can never be out of sync with the inputs.
 */
export function useLoanCalc(defaults: LoanFormDefaults) {
  const form = reactive({
    price: Math.round(defaults.priceCents / 100),
    downPaymentPercent: defaults.downPaymentPercent ?? 20,
    aprPercent: defaults.aprPercent ?? 7.25,
    termMonths: defaults.termMonths ?? 60,
  })

  const input = computed(() => {
    const priceCents = dollarsToCents(Number(form.price) || 0)
    return {
      priceCents,
      // Percent of a cents value, rounded to whole cents.
      downPaymentCents: Math.round((priceCents * (Number(form.downPaymentPercent) || 0)) / 100),
      // 7.25% -> 725 bps. Round to kill float noise like 7.249999.
      aprBps: Math.round((Number(form.aprPercent) || 0) * 100),
      termMonths: Math.trunc(Number(form.termMonths) || 0),
    }
  })

  const errors = computed(() => validateLoanInput(input.value))
  const result = computed<LoanResult | null>(() => (errors.value.length ? null : amortize(input.value)))

  /** Yearly roll-up for a compact chart/table (60 rows is a lot on a phone). */
  const yearly = computed(() => {
    if (!result.value) return []
    const years: { year: number; principalCents: number; interestCents: number; endBalanceCents: number }[] = []
    for (const row of result.value.schedule) {
      const y = Math.ceil(row.month / 12)
      let bucket = years[y - 1]
      if (!bucket) {
        bucket = { year: y, principalCents: 0, interestCents: 0, endBalanceCents: 0 }
        years.push(bucket)
      }
      bucket.principalCents += row.principalCents
      bucket.interestCents += row.interestCents
      bucket.endBalanceCents = row.balanceCents
    }
    return years
  })

  return { form, input, errors, result, yearly }
}
