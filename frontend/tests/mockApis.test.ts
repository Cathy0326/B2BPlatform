import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Equipment } from '~/types/equipment'
import { createMockAuctionApi, NotRegisteredError } from '~/services/auctionApi'
import { BookingConflictError, createMockEquipmentApi } from '~/services/equipmentApi'
import { createMockEscrowApi } from '~/services/escrowApi'

describe('in-browser equipment API', () => {
  const seed = [{ id: 'eq-1', title: 'Dozer', bookings: [{ start: '2026-10-10', end: '2026-10-17' }] }] as unknown as Equipment[]

  it('returns copies, so callers cannot change the "database"', async () => {
    const api = createMockEquipmentApi(seed)
    const list = await api.list()
    list[0]!.title = 'changed'

    expect((await api.get('eq-1'))!.title).toBe('Dozer')
    expect(await api.get('nope')).toBeNull()
  })

  it('books free dates, then rejects an overlap with the conflicting booking attached', async () => {
    const api = createMockEquipmentApi(seed)

    await api.createBooking('eq-1', { start: '2026-10-17', end: '2026-10-20' }) // touches, does not overlap
    const err = await api.createBooking('eq-1', { start: '2026-10-19', end: '2026-10-22' }).catch((e) => e)

    expect(err).toBeInstanceOf(BookingConflictError)
    expect(err.conflicts).toEqual([{ start: '2026-10-17', end: '2026-10-20' }])
    expect((await api.get('eq-1'))!.bookings).toHaveLength(2)
  })

  it('refuses to book a machine that does not exist', async () => {
    await expect(createMockEquipmentApi(seed).createBooking('eq-404', { start: '2026-11-01', end: '2026-11-02' })).rejects.toThrow(/not found/)
  })
})

describe('in-browser auction API', () => {
  let now = Date.parse('2026-10-04T12:00:00Z')
  const clock = () => now
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('lists and gets auctions, and unknown ids give null', async () => {
    const api = createMockAuctionApi(clock)

    expect((await api.list(null)).map((a) => a.id)).toContain('au-2002')
    expect(await api.get('au-404', null)).toBeNull()
    await expect(api.registerToBid('au-404', 'u-1')).rejects.toThrow(/not found/)
    await expect(api.placeBid('au-404', 'u-1', 1)).rejects.toThrow(/not found/)
  })

  it('requires a deposit hold before bidding, and registering twice holds the deposit once', async () => {
    const api = createMockAuctionApi(clock)

    await expect(api.placeBid('au-2002', 'u-1', 50_000_000)).rejects.toBeInstanceOf(NotRegisteredError)
    const first = await api.registerToBid('au-2002', 'u-1')
    const second = await api.registerToBid('au-2002', 'u-1')

    expect(second).toBe(first)
    expect(first.status).toBe('HELD')
    expect(await api.getHold('au-2002', 'u-1')).toEqual(first)
    expect(await api.getHold('au-2002', 'u-2')).toBeNull()
  })

  it("keeps each bidder's maximum secret from everyone else", async () => {
    const api = createMockAuctionApi(clock)
    await api.registerToBid('au-2002', 'u-1')

    const bid = await api.placeBid('au-2002', 'u-1', 50_000_000)

    expect(bid.ok && bid.auction.leaderMaxCents).toBe(50_000_000) // the leader sees their own max
    expect((await api.get('au-2002', 'u-2'))!.leaderMaxCents).toBeNull()
    expect((await api.list('u-2')).find((a) => a.id === 'au-2002')!.leaderMaxCents).toBeNull()
  })

  it('returns the rejection reason for a bid that is too low', async () => {
    const api = createMockAuctionApi(clock)
    await api.registerToBid('au-2002', 'u-1')

    const r = await api.placeBid('au-2002', 'u-1', 1)

    expect(r.ok).toBe(false)
    expect(!r.ok && r.reason).toBe('TOO_LOW')
  })

  it('pushes accepted bids to subscribers, each seeing their own view', async () => {
    const api = createMockAuctionApi(clock)
    const seenByU2: (number | null)[] = []
    const unsubscribe = api.subscribe('au-2002', 'u-2', (a) => seenByU2.push(a.leaderMaxCents))
    await api.registerToBid('au-2002', 'u-1')

    await api.placeBid('au-2002', 'u-1', 50_000_000)
    unsubscribe()
    await api.placeBid('au-2002', 'u-1', 60_000_000)

    expect(seenByU2).toEqual([null]) // got the first update, without u-1's secret max; nothing after unsubscribing
  })

  it('runs rival bidder bots only while someone is watching', async () => {
    vi.useFakeTimers()
    vi.spyOn(Math, 'random').mockReturnValue(0) // the bot always decides to bid, at the minimum
    const api = createMockAuctionApi(clock)
    const leaders: (string | null)[] = []
    const unsubscribe = api.subscribe('au-2002', null, (a) => leaders.push(a.leaderId))

    vi.advanceTimersByTime(6_000)
    expect(leaders).toHaveLength(1)
    expect(leaders[0]).toMatch(/^b-/)

    unsubscribe()
    vi.advanceTimersByTime(60_000)
    expect(leaders).toHaveLength(1) // timer stopped
  })

  it('bots do nothing once the auction has ended', async () => {
    vi.useFakeTimers()
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const api = createMockAuctionApi(clock)
    const updates: unknown[] = []
    api.subscribe('au-2002', null, (a) => updates.push(a))

    now += 60 * 60_000 // au-2002 ended
    vi.advanceTimersByTime(6_000)

    expect(updates).toHaveLength(0)
    now -= 60 * 60_000
  })
})

describe('in-browser escrow API extras', () => {
  it('reports the simulated payment provider and shows nothing to anonymous visitors', async () => {
    let user = ''
    const escrow = createMockEscrowApi({ currentUserId: () => user, auctions: createMockAuctionApi() })

    expect(await escrow.paymentConfig()).toEqual({ gateway: 'SIMULATED', stripePublishableKey: null })
    expect(await escrow.myDeals()).toEqual([])
    expect((await escrow.ledgerOverview()).journalEntries).toEqual([])
    user = 'u-1'
    await expect(escrow.payBalance('D-404', null)).rejects.toThrow('Deal not found')
  })

  it('rejects a payment declined for insufficient funds', async () => {
    const escrow = createMockEscrowApi({ currentUserId: () => 'u-1', auctions: createMockAuctionApi() })
    const deal = (await escrow.myDeals()).find((d) => d.state === 'AWAITING_BALANCE')!

    await expect(escrow.payBalance(deal.id, 'pm_card_insufficientFunds')).rejects.toThrow(/insufficient funds/)
  })
})
