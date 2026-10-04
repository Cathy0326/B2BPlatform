/**
 * Domain types shared across the frontend.
 *
 * Money rule: every amount is an integer number of CENTS (e.g. $1,250.50 -> 125050).
 * Floating-point dollars are only produced at the very edge, when formatting for display.
 */

export type Cents = number

/** ISO calendar date, no time and no timezone: "2026-10-04". */
export type IsoDate = string

export type Category =
  | 'EXCAVATOR'
  | 'BULLDOZER'
  | 'WHEEL_LOADER'
  | 'SKID_STEER'
  | 'CRANE'
  | 'BACKHOE'

export type ListingType = 'SALE' | 'RENT' | 'BOTH'

export interface RentalRates {
  dailyCents: Cents
  weeklyCents: Cents
  /** Equipment rental industry convention: one "month" = 28 days (4 weeks). */
  monthlyCents: Cents
}

/**
 * A booked period, half-open: [start, end).
 * `start` is the pick-up day, `end` is the return day, and the machine is free again on `end`.
 */
export interface Booking {
  start: IsoDate
  end: IsoDate
}

export interface Equipment {
  id: string
  title: string
  category: Category
  make: string
  model: string
  year: number
  hours: number
  location: string
  listingType: ListingType
  /** Present when listingType is SALE or BOTH. */
  salePriceCents: Cents | null
  /** Present when listingType is RENT or BOTH. */
  rentalRates: RentalRates | null
  description: string
  specs: Record<string, string>
  bookings: Booking[]
}

export type SortKey = 'newest' | 'price_asc' | 'price_desc' | 'hours_asc'

export interface EquipmentFilter {
  search: string
  categories: Category[]
  listingType: 'ANY' | 'SALE' | 'RENT'
  maxPriceCents: Cents | null
  minYear: number | null
  maxHours: number | null
  location: string
  sort: SortKey
}

export const CATEGORY_LABELS: Record<Category, string> = {
  EXCAVATOR: 'Excavator',
  BULLDOZER: 'Bulldozer',
  WHEEL_LOADER: 'Wheel Loader',
  SKID_STEER: 'Skid Steer',
  CRANE: 'Crane',
  BACKHOE: 'Backhoe',
}
