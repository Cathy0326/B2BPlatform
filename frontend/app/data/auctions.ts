import type { Auction } from '~/types/auction'
import { placeBid } from '~/utils/auction'

const MIN = 60_000
const HOUR = 60 * MIN

interface AuctionSeed {
  id: string
  equipmentId: string
  startingPriceCents: number
  reservePriceCents: number | null
  depositCents: number
  startOffsetMs: number
  endOffsetMs: number
  /** Proxy bids replayed through the real engine to build a believable history. */
  history: [bidderId: string, maxCents: number][]
}

const SEEDS: AuctionSeed[] = [
  {
    id: 'au-2001',
    equipmentId: 'eq-1002',
    startingPriceCents: 15_000_000,
    reservePriceCents: 17_500_000,
    depositCents: 1_500_000,
    startOffsetMs: -26 * HOUR,
    endOffsetMs: 2 * HOUR + 14 * MIN,
    history: [
      ['b-4821', 15_500_000],
      ['b-1377', 16_200_000],
      ['b-4821', 17_000_000],
      ['b-9054', 16_800_000],
    ],
  },
  {
    id: 'au-2002',
    equipmentId: 'eq-1006',
    startingPriceCents: 9_000_000,
    reservePriceCents: null,
    depositCents: 900_000,
    startOffsetMs: -47 * HOUR,
    endOffsetMs: 6 * MIN,
    history: [
      ['b-2210', 9_200_000],
      ['b-7788', 9_800_000],
      ['b-2210', 10_400_000],
      ['b-3141', 10_100_000],
    ],
  },
  {
    id: 'au-2003',
    equipmentId: 'eq-1010',
    startingPriceCents: 65_000_000,
    reservePriceCents: 78_000_000,
    depositCents: 2_500_000,
    startOffsetMs: -10 * HOUR,
    endOffsetMs: 29 * HOUR,
    history: [['b-6060', 66_000_000]],
  },
  {
    id: 'au-2004',
    equipmentId: 'eq-1011',
    startingPriceCents: 6_500_000,
    reservePriceCents: null,
    depositCents: 650_000,
    startOffsetMs: 3 * HOUR,
    endOffsetMs: 51 * HOUR,
    history: [],
  },
  {
    id: 'au-2005',
    equipmentId: 'eq-1005',
    startingPriceCents: 11_000_000,
    reservePriceCents: 12_500_000,
    depositCents: 1_100_000,
    startOffsetMs: -72 * HOUR,
    endOffsetMs: -2 * HOUR,
    history: [
      ['b-5150', 12_000_000],
      ['b-8008', 13_400_000],
      ['b-5150', 13_100_000],
    ],
  },
]

/** Build auctions relative to `now` so the demo always has live, upcoming, and ended lots. */
export function buildMockAuctions(now: number): Auction[] {
  return SEEDS.map((s) => {
    const startsAt = now + s.startOffsetMs
    const endsAt = now + s.endOffsetMs
    let a: Auction = {
      id: s.id,
      equipmentId: s.equipmentId,
      startingPriceCents: s.startingPriceCents,
      reservePriceCents: s.reservePriceCents,
      depositCents: s.depositCents,
      startsAt,
      endsAt,
      softCloseMs: 2 * MIN,
      currentPriceCents: s.startingPriceCents,
      leaderId: null,
      leaderMaxCents: null,
      bids: [],
      extensions: 0,
    }
    // Replay history evenly spaced between start and min(now, end) - 1 min.
    const lastAt = Math.min(now, endsAt) - MIN
    s.history.forEach(([bidder, max], i) => {
      const at = startsAt + ((lastAt - startsAt) * (i + 1)) / (s.history.length + 1)
      const r = placeBid(a, bidder, max, Math.round(at))
      if (r.ok) a = r.auction
    })
    return a
  })
}
