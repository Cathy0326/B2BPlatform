import type { Booking, IsoDate } from '~/types/equipment'

/**
 * Date ranges are HALF-OPEN: [start, end).
 *   - start = pick-up day (inclusive)
 *   - end   = return day  (exclusive, the machine is free again that day)
 *
 * Why half-open?
 *   1. length = end - start (no "+1" bugs)
 *   2. back-to-back bookings [1,5) and [5,8) do NOT overlap, which matches reality:
 *      one customer returns in the morning, the next picks up in the afternoon.
 *
 * We convert ISO dates to integer "day numbers" with Date.UTC, so local timezone
 * and daylight-saving changes can never shift a date by one.
 */

const MS_PER_DAY = 86_400_000
const ISO_RE = /^(\d{4})-(\d{2})-(\d{2})$/

export function toDayNumber(iso: IsoDate): number {
  const m = ISO_RE.exec(iso)
  if (!m) throw new RangeError(`Invalid ISO date: ${iso}`)
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  const ms = Date.UTC(y, mo - 1, d)
  // Reject rollovers such as 2026-02-31 -> March 3.
  const check = new Date(ms)
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d) {
    throw new RangeError(`Invalid ISO date: ${iso}`)
  }
  return ms / MS_PER_DAY
}

export function fromDayNumber(day: number): IsoDate {
  return new Date(day * MS_PER_DAY).toISOString().slice(0, 10)
}

export function addDays(iso: IsoDate, days: number): IsoDate {
  return fromDayNumber(toDayNumber(iso) + days)
}

/** Number of rental days in [start, end). */
export function daysBetween(start: IsoDate, end: IsoDate): number {
  return toDayNumber(end) - toDayNumber(start)
}

/** Classic interval-overlap test (NeetCode: Meeting Rooms). */
export function overlaps(a: Booking, b: Booking): boolean {
  return toDayNumber(a.start) < toDayNumber(b.end) && toDayNumber(b.start) < toDayNumber(a.end)
}

export function findConflicts(request: Booking, existing: readonly Booking[]): Booking[] {
  return existing.filter((b) => overlaps(request, b))
}

/**
 * Merge overlapping OR touching bookings into continuous blocks (NeetCode: Merge Intervals).
 * Used to show "Booked: Oct 3 - Oct 12" instead of three separate rows.
 * Sort by start: O(n log n); single sweep: O(n).
 */
export function mergeBookings(bookings: readonly Booking[]): Booking[] {
  const sorted = [...bookings].sort((a, b) => toDayNumber(a.start) - toDayNumber(b.start))
  const merged: Booking[] = []
  for (const b of sorted) {
    const last = merged[merged.length - 1]
    if (last && toDayNumber(b.start) <= toDayNumber(last.end)) {
      if (toDayNumber(b.end) > toDayNumber(last.end)) last.end = b.end
    } else {
      merged.push({ ...b })
    }
  }
  return merged
}

/**
 * Earliest start >= `from` where a rental of `days` fits without conflicts.
 * Walk merged blocks in order; each block either ends before our window or pushes it later.
 */
export function nextAvailableStart(from: IsoDate, days: number, bookings: readonly Booking[]): IsoDate {
  let candidate = toDayNumber(from)
  for (const block of mergeBookings(bookings)) {
    const s = toDayNumber(block.start)
    const e = toDayNumber(block.end)
    if (candidate + days <= s) break // fits before this block
    if (candidate < e) candidate = e // collides: jump to the block's end
  }
  return fromDayNumber(candidate)
}

export function todayIso(): IsoDate {
  return new Date().toISOString().slice(0, 10)
}
