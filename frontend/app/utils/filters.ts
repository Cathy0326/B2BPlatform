import type { Category, Equipment, EquipmentFilter, SortKey } from '~/types/equipment'
import { CATEGORY_LABELS } from '~/types/equipment'

/**
 * Pure filtering / sorting, plus conversion to and from the URL query string.
 * Keeping filters in the URL means a buyer can share or bookmark "used excavators under $150k".
 */

export const DEFAULT_FILTER: EquipmentFilter = {
  search: '',
  categories: [],
  listingType: 'ANY',
  maxPriceCents: null,
  minYear: null,
  maxHours: null,
  location: '',
  sort: 'newest',
}

/** The price we compare/sort by: sale price, else the monthly rental rate. */
export function referencePriceCents(e: Equipment): number {
  return e.salePriceCents ?? e.rentalRates?.monthlyCents ?? 0
}

export function matchesFilter(e: Equipment, f: EquipmentFilter): boolean {
  if (f.search) {
    // Word-PREFIX match: "cat" finds "Caterpillar" but not "Bobcat" (plain substring would).
    const words = `${e.title} ${e.make} ${e.model} ${CATEGORY_LABELS[e.category]}`.toLowerCase().split(/[^a-z0-9]+/)
    const terms = f.search.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean)
    if (!terms.every((t) => words.some((w) => w.startsWith(t)))) return false
  }
  if (f.categories.length && !f.categories.includes(e.category)) return false
  if (f.listingType === 'SALE' && e.listingType === 'RENT') return false
  if (f.listingType === 'RENT' && e.listingType === 'SALE') return false
  // "Max sale price" only constrains machines that have a sale price; rent-only machines pass.
  if (f.maxPriceCents != null && e.salePriceCents != null && e.salePriceCents > f.maxPriceCents) return false
  if (f.minYear != null && e.year < f.minYear) return false
  if (f.maxHours != null && e.hours > f.maxHours) return false
  if (f.location && !e.location.toLowerCase().includes(f.location.toLowerCase())) return false
  return true
}

const comparators: Record<SortKey, (a: Equipment, b: Equipment) => number> = {
  newest: (a, b) => b.year - a.year || a.hours - b.hours,
  price_asc: (a, b) => referencePriceCents(a) - referencePriceCents(b),
  price_desc: (a, b) => referencePriceCents(b) - referencePriceCents(a),
  hours_asc: (a, b) => a.hours - b.hours,
}

export function applyFilter(items: readonly Equipment[], f: EquipmentFilter): Equipment[] {
  return items.filter((e) => matchesFilter(e, f)).sort(comparators[f.sort])
}

// ---------- URL query <-> filter ----------

type Query = Record<string, string | null | undefined | (string | null)[]>

function first(v: Query[string]): string | undefined {
  if (Array.isArray(v)) return v[0] ?? undefined
  return v ?? undefined
}

function intOrNull(v: string | undefined): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isInteger(n) && n >= 0 ? n : null
}

const CATEGORIES = Object.keys(CATEGORY_LABELS) as Category[]
const SORTS: SortKey[] = ['newest', 'price_asc', 'price_desc', 'hours_asc']

export function filterFromQuery(q: Query): EquipmentFilter {
  const cats = (first(q.cat) ?? '').split(',').filter((c): c is Category => CATEGORIES.includes(c as Category))
  const type = first(q.type)
  const sort = first(q.sort) as SortKey | undefined
  const maxPrice = intOrNull(first(q.maxPrice))
  return {
    search: first(q.q) ?? '',
    categories: cats,
    listingType: type === 'SALE' || type === 'RENT' ? type : 'ANY',
    maxPriceCents: maxPrice == null ? null : maxPrice * 100,
    minYear: intOrNull(first(q.minYear)),
    maxHours: intOrNull(first(q.maxHours)),
    location: first(q.loc) ?? '',
    sort: sort && SORTS.includes(sort) ? sort : 'newest',
  }
}

/** Only non-default values go into the URL, so it stays short and readable. */
export function filterToQuery(f: EquipmentFilter): Record<string, string> {
  const q: Record<string, string> = {}
  if (f.search) q.q = f.search
  if (f.categories.length) q.cat = f.categories.join(',')
  if (f.listingType !== 'ANY') q.type = f.listingType
  if (f.maxPriceCents != null) q.maxPrice = String(Math.round(f.maxPriceCents / 100))
  if (f.minYear != null) q.minYear = String(f.minYear)
  if (f.maxHours != null) q.maxHours = String(f.maxHours)
  if (f.location) q.loc = f.location
  if (f.sort !== 'newest') q.sort = f.sort
  return q
}
