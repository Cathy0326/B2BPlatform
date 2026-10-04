import { describe, expect, it } from 'vitest'
import { addDays, daysBetween, findConflicts, mergeBookings, nextAvailableStart, overlaps, toDayNumber } from '~/utils/dateRange'

describe('day arithmetic', () => {
  it('counts days in a half-open range', () => {
    expect(daysBetween('2026-10-01', '2026-10-08')).toBe(7)
    expect(daysBetween('2026-02-27', '2026-03-02')).toBe(3) // non-leap year
    expect(daysBetween('2028-02-27', '2028-03-02')).toBe(4) // leap year
  })

  it('is immune to daylight-saving changes', () => {
    // US DST ends 2026-11-01; local-time math would produce 6.96 days here.
    expect(daysBetween('2026-10-29', '2026-11-05')).toBe(7)
  })

  it('rejects impossible dates', () => {
    expect(() => toDayNumber('2026-02-31')).toThrow(RangeError)
    expect(() => toDayNumber('10/04/2026')).toThrow(RangeError)
  })

  it('adds days across month boundaries', () => {
    expect(addDays('2026-10-30', 3)).toBe('2026-11-02')
  })
})

describe('overlaps (Meeting Rooms pattern)', () => {
  const a = { start: '2026-10-01', end: '2026-10-05' }
  it('detects true overlap', () => {
    expect(overlaps(a, { start: '2026-10-04', end: '2026-10-10' })).toBe(true)
    expect(overlaps(a, { start: '2026-09-01', end: '2026-12-01' })).toBe(true) // containment
  })
  it('back-to-back bookings do not overlap (half-open)', () => {
    expect(overlaps(a, { start: '2026-10-05', end: '2026-10-08' })).toBe(false)
    expect(overlaps({ start: '2026-09-28', end: '2026-10-01' }, a)).toBe(false)
  })
  it('lists every conflicting booking', () => {
    const existing = [
      { start: '2026-10-01', end: '2026-10-03' },
      { start: '2026-10-10', end: '2026-10-12' },
    ]
    expect(findConflicts({ start: '2026-10-02', end: '2026-10-11' }, existing)).toHaveLength(2)
  })
})

describe('mergeBookings (Merge Intervals pattern)', () => {
  it('merges overlapping and touching ranges, unsorted input', () => {
    const merged = mergeBookings([
      { start: '2026-11-02', end: '2026-11-09' },
      { start: '2026-10-13', end: '2026-10-20' },
      { start: '2026-10-06', end: '2026-10-13' },
    ])
    expect(merged).toEqual([
      { start: '2026-10-06', end: '2026-10-20' },
      { start: '2026-11-02', end: '2026-11-09' },
    ])
  })
  it('does not mutate the input', () => {
    const input = [{ start: '2026-10-01', end: '2026-10-05' }, { start: '2026-10-03', end: '2026-10-09' }]
    mergeBookings(input)
    expect(input[0]!.end).toBe('2026-10-05')
  })
})

describe('nextAvailableStart', () => {
  const bookings = [
    { start: '2026-10-06', end: '2026-10-20' },
    { start: '2026-10-23', end: '2026-10-30' },
  ]
  it('returns the requested day when free', () => {
    expect(nextAvailableStart('2026-10-01', 5, bookings)).toBe('2026-10-01')
  })
  it('skips a gap that is too small', () => {
    // Gap Oct 20-23 is 3 days; a 5-day rental must wait until Oct 30.
    expect(nextAvailableStart('2026-10-08', 5, bookings)).toBe('2026-10-30')
  })
  it('uses a gap that is exactly big enough', () => {
    expect(nextAvailableStart('2026-10-08', 3, bookings)).toBe('2026-10-20')
  })
})
