import { describe, expect, it } from 'vitest'
import type { Auction } from '~/types/auction'
import {
  auctionStatus,
  bidIncrementCents,
  formatCountdown,
  minimumNextBidCents,
  placeBid,
  publicView,
  reserveMet,
  settleResult,
} from '~/utils/auction'

const T0 = 1_800_000_000_000
const MIN = 60_000

function makeAuction(over: Partial<Auction> = {}): Auction {
  return {
    id: 'a1',
    equipmentId: 'eq-1',
    startingPriceCents: 5_000_000, // $50,000 -> increment $500
    reservePriceCents: null,
    depositCents: 500_000,
    startsAt: T0,
    endsAt: T0 + 60 * MIN,
    softCloseMs: 2 * MIN,
    currentPriceCents: 5_000_000,
    leaderId: null,
    leaderMaxCents: null,
    bids: [],
    extensions: 0,
    ...over,
  }
}

function bid(a: Auction, who: string, max: number, at = T0 + MIN): Auction {
  const r = placeBid(a, who, max, at)
  if (!r.ok) throw new Error(`bid rejected: ${r.reason}`)
  return r.auction
}

describe('increments and status', () => {
  it('increment grows with price', () => {
    expect(bidIncrementCents(500_000)).toBe(10_000)
    expect(bidIncrementCents(5_000_000)).toBe(50_000)
    expect(bidIncrementCents(80_000_000)).toBe(250_000)
  })
  it('status follows the clock', () => {
    const a = makeAuction()
    expect(auctionStatus(a, T0 - 1)).toBe('UPCOMING')
    expect(auctionStatus(a, T0)).toBe('LIVE')
    expect(auctionStatus(a, a.endsAt)).toBe('ENDED')
  })
})

describe('proxy bidding', () => {
  it('first bid opens at the starting price, not the max', () => {
    const a = bid(makeAuction(), 'alice', 7_000_000)
    expect(a.leaderId).toBe('alice')
    expect(a.currentPriceCents).toBe(5_000_000)
  })

  it('a lower challenger pushes the price to their max + 1 increment', () => {
    let a = bid(makeAuction(), 'alice', 7_000_000)
    a = bid(a, 'bob', 6_000_000)
    expect(a.leaderId).toBe('alice')
    expect(a.currentPriceCents).toBe(6_050_000) // $60,000 + $500
  })

  it('a higher challenger takes the lead at old max + 1 increment', () => {
    let a = bid(makeAuction(), 'alice', 7_000_000)
    a = bid(a, 'bob', 9_000_000)
    expect(a.leaderId).toBe('bob')
    expect(a.currentPriceCents).toBe(7_050_000)
  })

  it('price never exceeds the leader max', () => {
    let a = bid(makeAuction(), 'alice', 7_000_000)
    a = bid(a, 'bob', 7_020_000) // only $200 above alice's max, less than one increment
    expect(a.leaderId).toBe('bob')
    expect(a.currentPriceCents).toBe(7_020_000)
  })

  it('TIE goes to the earlier bidder (time priority)', () => {
    let a = bid(makeAuction(), 'alice', 7_000_000)
    a = bid(a, 'bob', 7_000_000)
    expect(a.leaderId).toBe('alice')
    expect(a.currentPriceCents).toBe(7_000_000)
  })

  it('rejects bids under the minimum and reports it', () => {
    const a = bid(makeAuction(), 'alice', 7_000_000)
    const r = placeBid(a, 'bob', 5_010_000, T0 + MIN)
    expect(r).toEqual({ ok: false, reason: 'TOO_LOW', minimumCents: minimumNextBidCents(a) })
  })

  it('leader can raise their secret max without moving the price', () => {
    let a = bid(makeAuction(), 'alice', 6_000_000)
    a = bid(a, 'alice', 8_000_000)
    expect(a.currentPriceCents).toBe(5_000_000)
    expect(a.leaderMaxCents).toBe(8_000_000)
    expect(placeBid(a, 'alice', 7_000_000, T0 + MIN)).toMatchObject({ ok: false, reason: 'NOT_ABOVE_OWN_MAX' })
  })

  it('does not mutate the input auction', () => {
    const a = makeAuction()
    bid(a, 'alice', 7_000_000)
    expect(a.leaderId).toBeNull()
    expect(a.bids).toHaveLength(0)
  })
})

describe('reserve price', () => {
  it('jumps to the reserve when the leader max covers it', () => {
    const a = bid(makeAuction({ reservePriceCents: 6_500_000 }), 'alice', 7_000_000)
    expect(a.currentPriceCents).toBe(6_500_000)
    expect(reserveMet(a)).toBe(true)
  })
  it('auction ends unsold when the reserve is not met', () => {
    const a = bid(makeAuction({ reservePriceCents: 9_000_000 }), 'alice', 7_000_000)
    expect(reserveMet(a)).toBe(false)
    expect(settleResult(a, a.endsAt)).toEqual({ sold: false, winnerId: null, hammerPriceCents: null })
  })
})

describe('soft close (anti-sniping)', () => {
  it('a bid in the last 2 minutes extends the auction', () => {
    const a0 = makeAuction()
    const lateBid = a0.endsAt - 30_000
    const r = placeBid(a0, 'alice', 6_000_000, lateBid)
    expect(r.ok && r.extended).toBe(true)
    if (r.ok) {
      expect(r.auction.endsAt).toBe(lateBid + 2 * MIN)
      expect(r.auction.extensions).toBe(1)
    }
  })
  it('an early bid does not extend', () => {
    const r = placeBid(makeAuction(), 'alice', 6_000_000, T0 + MIN)
    expect(r.ok && r.extended).toBe(false)
  })
  it('rejects bids before start and after end', () => {
    const a = makeAuction()
    expect(placeBid(a, 'x', 9_000_000, T0 - 1)).toMatchObject({ ok: false, reason: 'NOT_STARTED' })
    expect(placeBid(a, 'x', 9_000_000, a.endsAt)).toMatchObject({ ok: false, reason: 'ENDED' })
  })
})

describe('settlement and privacy', () => {
  it('winner pays the current price, not their max', () => {
    let a = bid(makeAuction(), 'alice', 9_000_000)
    a = bid(a, 'bob', 6_000_000)
    expect(settleResult(a, a.endsAt)).toEqual({ sold: true, winnerId: 'alice', hammerPriceCents: 6_050_000 })
  })
  it('hides the leader max from everyone else', () => {
    const a = bid(makeAuction(), 'alice', 9_000_000)
    expect(publicView(a, 'bob').leaderMaxCents).toBeNull()
    expect(publicView(a, 'alice').leaderMaxCents).toBe(9_000_000)
  })
  it('random bid sequences keep invariants', () => {
    // Invariants: price <= leader max, price >= starting, leader max is the highest max seen.
    let a = makeAuction()
    let highest = 0
    let seed = 42
    const rand = () => (seed = (seed * 1_103_515_245 + 12_345) % 2 ** 31) / 2 ** 31
    for (let i = 0; i < 500; i++) {
      const who = `b${Math.floor(rand() * 5)}`
      const max = 5_000_000 + Math.floor(rand() * 200) * 25_000
      const r = placeBid(a, who, max, T0 + MIN)
      if (!r.ok) continue
      a = r.auction
      highest = Math.max(highest, max)
      expect(a.currentPriceCents).toBeLessThanOrEqual(a.leaderMaxCents!)
      expect(a.currentPriceCents).toBeGreaterThanOrEqual(a.startingPriceCents)
      expect(a.leaderMaxCents).toBe(highest)
    }
  })
})

describe('formatCountdown', () => {
  it('formats durations', () => {
    expect(formatCountdown(0)).toBe('Ended')
    expect(formatCountdown(65_000)).toBe('1m 05s')
    expect(formatCountdown(3 * 3600_000 + 4 * 60_000)).toBe('3h 04m')
    expect(formatCountdown(2 * 86_400_000 + 5 * 3600_000)).toBe('2d 5h')
  })
})
