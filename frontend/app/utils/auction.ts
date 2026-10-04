import type { Auction, AuctionStatus, BidOutcome, VisibleBid } from '~/types/auction'
import type { Cents } from '~/types/equipment'

/**
 * Proxy-bidding auction engine (pure functions, no I/O).
 * Phase 1 uses it inside the mock API; Phase 2 re-implements the same rules in Java,
 * where the server is the single authority. Same tests, two languages.
 *
 * Priority rule = PRICE first, then TIME (earlier wins a tie). The same rule an exchange
 * order book uses, which is why this is a good concurrency/trading talking point.
 */

/** Bid increment grows with price, like real equipment auctions. */
export function bidIncrementCents(priceCents: Cents): Cents {
  if (priceCents < 1_000_000) return 10_000 // < $10k   -> $100
  if (priceCents < 5_000_000) return 25_000 // < $50k   -> $250
  if (priceCents < 10_000_000) return 50_000 // < $100k  -> $500
  if (priceCents < 50_000_000) return 100_000 // < $500k  -> $1,000
  return 250_000 // otherwise -> $2,500
}

export function auctionStatus(a: Pick<Auction, 'startsAt' | 'endsAt'>, now: number): AuctionStatus {
  if (now < a.startsAt) return 'UPCOMING'
  if (now >= a.endsAt) return 'ENDED'
  return 'LIVE'
}

/** Lowest max a NEW challenger may submit right now. */
export function minimumNextBidCents(a: Auction): Cents {
  if (a.leaderId == null) return a.startingPriceCents
  return a.currentPriceCents + bidIncrementCents(a.currentPriceCents)
}

export function reserveMet(a: Auction): boolean {
  return a.reservePriceCents == null || (a.leaderId != null && a.currentPriceCents >= a.reservePriceCents)
}

/**
 * Place a proxy bid. Returns a NEW auction object (immutability makes it easy to test
 * and mirrors how the backend writes a new row version inside one transaction).
 */
export function placeBid(auction: Auction, bidderId: string, maxCents: Cents, now: number): BidOutcome {
  if (!Number.isInteger(maxCents) || maxCents <= 0) return { ok: false, reason: 'TOO_LOW' }
  const status = auctionStatus(auction, now)
  if (status === 'UPCOMING') return { ok: false, reason: 'NOT_STARTED' }
  if (status === 'ENDED') return { ok: false, reason: 'ENDED' }

  const a: Auction = { ...auction, bids: [...auction.bids] }
  const push = (b: Omit<VisibleBid, 'at'>) => a.bids.push({ ...b, at: now })

  if (a.leaderId === bidderId) {
    // Leader raising their own secret max: price does not move (no one to outbid).
    if (maxCents <= (a.leaderMaxCents ?? 0)) return { ok: false, reason: 'NOT_ABOVE_OWN_MAX' }
    a.leaderMaxCents = maxCents
  } else {
    const minimum = minimumNextBidCents(a)
    if (maxCents < minimum) return { ok: false, reason: 'TOO_LOW', minimumCents: minimum }

    if (a.leaderId == null) {
      // First bid opens at the starting price.
      a.leaderId = bidderId
      a.leaderMaxCents = maxCents
      a.currentPriceCents = a.startingPriceCents
      push({ bidderId, amountCents: a.currentPriceCents, auto: false })
    } else if (maxCents > a.leaderMaxCents!) {
      // Challenger wins. Old leader's proxy fought up to their max, new price = that + 1 increment.
      const oldMax = a.leaderMaxCents!
      push({ bidderId: a.leaderId, amountCents: oldMax, auto: true })
      a.leaderId = bidderId
      a.leaderMaxCents = maxCents
      a.currentPriceCents = Math.min(maxCents, oldMax + bidIncrementCents(oldMax))
      push({ bidderId, amountCents: a.currentPriceCents, auto: false })
    } else {
      // Leader's max is >= challenger's: leader keeps it (a TIE goes to the EARLIER bidder).
      push({ bidderId, amountCents: maxCents, auto: false })
      a.currentPriceCents = Math.min(a.leaderMaxCents!, maxCents + bidIncrementCents(maxCents))
      push({ bidderId: a.leaderId, amountCents: a.currentPriceCents, auto: true })
    }
  }

  // Reserve jump: if the leader's max already covers the reserve, show the reserve price.
  if (a.reservePriceCents != null && a.leaderMaxCents! >= a.reservePriceCents && a.currentPriceCents < a.reservePriceCents) {
    a.currentPriceCents = a.reservePriceCents
    push({ bidderId: a.leaderId!, amountCents: a.currentPriceCents, auto: true })
  }

  // Soft close: a bid in the last `softCloseMs` pushes the end out. Stops "sniping".
  let extended = false
  if (a.endsAt - now < a.softCloseMs) {
    a.endsAt = now + a.softCloseMs
    a.extensions += 1
    extended = true
  }

  return { ok: true, auction: a, leading: a.leaderId === bidderId, extended }
}

export interface AuctionResult {
  sold: boolean
  winnerId: string | null
  hammerPriceCents: Cents | null
}

export function settleResult(a: Auction, now: number): AuctionResult | null {
  if (auctionStatus(a, now) !== 'ENDED') return null
  if (a.leaderId == null || !reserveMet(a)) return { sold: false, winnerId: null, hammerPriceCents: null }
  return { sold: true, winnerId: a.leaderId, hammerPriceCents: a.currentPriceCents }
}

/** Strip the secret proxy max before showing an auction to someone who is not the leader. */
export function publicView(a: Auction, viewerId: string | null): Auction {
  return a.leaderId === viewerId ? a : { ...a, leaderMaxCents: null }
}

/** "2d 4h", "13m 05s" */
export function formatCountdown(ms: number): string {
  if (ms <= 0) return 'Ended'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86_400)
  const h = Math.floor((s % 86_400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`
  return `${m}m ${String(sec).padStart(2, '0')}s`
}
