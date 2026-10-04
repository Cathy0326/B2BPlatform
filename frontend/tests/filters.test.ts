import { describe, expect, it } from 'vitest'
import { MOCK_EQUIPMENT } from '~/data/equipment'
import { applyFilter, DEFAULT_FILTER, filterFromQuery, filterToQuery } from '~/utils/filters'

describe('applyFilter', () => {
  it('returns everything with the default filter', () => {
    expect(applyFilter(MOCK_EQUIPMENT, DEFAULT_FILTER)).toHaveLength(MOCK_EQUIPMENT.length)
  })

  it('filters by category and listing type', () => {
    const r = applyFilter(MOCK_EQUIPMENT, { ...DEFAULT_FILTER, categories: ['EXCAVATOR'], listingType: 'RENT' })
    expect(r.map((e) => e.id)).toEqual(['eq-1001'])
  })

  it('multi-word search matches all terms', () => {
    const r = applyFilter(MOCK_EQUIPMENT, { ...DEFAULT_FILTER, search: 'cat loader' })
    expect(r.map((e) => e.id)).toEqual(['eq-1006'])
  })

  it('max sale price keeps rent-only machines', () => {
    const r = applyFilter(MOCK_EQUIPMENT, { ...DEFAULT_FILTER, maxPriceCents: 10_000_000 })
    expect(r.every((e) => e.salePriceCents == null || e.salePriceCents <= 10_000_000)).toBe(true)
    expect(r.some((e) => e.salePriceCents == null)).toBe(true)
  })

  it('sorts by price ascending', () => {
    const r = applyFilter(MOCK_EQUIPMENT, { ...DEFAULT_FILTER, listingType: 'SALE', sort: 'price_asc' })
    const prices = r.map((e) => e.salePriceCents!)
    expect(prices).toEqual([...prices].sort((a, b) => a - b))
  })
})

describe('URL query round trip', () => {
  it('serializes only non-default values', () => {
    expect(filterToQuery(DEFAULT_FILTER)).toEqual({})
  })

  it('round-trips a full filter', () => {
    const f = { ...DEFAULT_FILTER, search: 'cat', categories: ['CRANE' as const], listingType: 'SALE' as const, maxPriceCents: 50_000_000, minYear: 2018, maxHours: 8000, location: 'TX', sort: 'price_desc' as const }
    expect(filterFromQuery(filterToQuery(f))).toEqual(f)
  })

  it('ignores garbage values', () => {
    const f = filterFromQuery({ cat: 'TANK,CRANE', type: 'LEASE', minYear: 'abc', sort: 'random' })
    expect(f.categories).toEqual(['CRANE'])
    expect(f.listingType).toBe('ANY')
    expect(f.minYear).toBeNull()
    expect(f.sort).toBe('newest')
  })
})
