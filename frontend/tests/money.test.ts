import { describe, expect, it } from 'vitest'
import { formatCents, formatCentsWhole, parseDollarsToCents, sumCents } from '~/utils/money'

describe('money', () => {
  it('formats cents as USD', () => {
    expect(formatCents(125050)).toBe('$1,250.50')
    expect(formatCents(0)).toBe('$0.00')
    expect(formatCentsWhole(16450000)).toBe('$164,500')
  })

  it('rejects non-integer cents', () => {
    expect(() => formatCents(10.5)).toThrow(TypeError)
  })

  it('parses dollar strings without float error', () => {
    expect(parseDollarsToCents('12.34')).toBe(1234) // 12.34 * 100 would be 1233.999...
    expect(parseDollarsToCents('$1,250.5')).toBe(125050)
    expect(parseDollarsToCents('7')).toBe(700)
    expect(parseDollarsToCents('1.234')).toBeNull()
    expect(parseDollarsToCents('-5')).toBeNull()
    expect(parseDollarsToCents('abc')).toBeNull()
  })

  it('sums exactly where floats drift', () => {
    expect(0.1 + 0.2).not.toBe(0.3)
    expect(sumCents([10, 20])).toBe(30)
  })
})
