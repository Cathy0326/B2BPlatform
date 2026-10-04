import type { Cents } from '~/types/equipment'

/**
 * English (ascending) auction with PROXY bidding, like eBay:
 *   - A bidder submits their MAXIMUM price, not a single bid.
 *   - The system bids on their behalf, one increment at a time, only as high as needed.
 *   - Visible price = second-highest max + one increment (capped at the leader's max).
 *
 * Time is stored as epoch milliseconds (UTC). The server clock is the only clock that counts.
 */

export type AuctionStatus = 'UPCOMING' | 'LIVE' | 'ENDED'

export interface VisibleBid {
  bidderId: string
  amountCents: Cents
  /** true when the system placed it automatically from someone's max (proxy). */
  auto: boolean
  at: number
}

export interface Auction {
  id: string
  equipmentId: string
  startingPriceCents: Cents
  /** Hidden minimum the seller will accept. null = no reserve. */
  reservePriceCents: Cents | null
  /** Refundable deposit a bidder must hold before bidding (escrow). */
  depositCents: Cents
  startsAt: number
  endsAt: number
  /** Soft close: a bid inside this window pushes endsAt out to now + window. */
  softCloseMs: number

  currentPriceCents: Cents
  leaderId: string | null
  /** SECRET. Never sent to other bidders (the backend strips it in Phase 2). */
  leaderMaxCents: Cents | null
  bids: VisibleBid[]
  extensions: number
  /**
   * Values computed by the backend (Phase 2+). When present they win over local computation,
   * because the client must not know the secret reserve price.
   */
  server?: { hasReserve: boolean; reserveMet: boolean; minimumNextBidCents: Cents }
}

export type BidRejection = 'NOT_STARTED' | 'ENDED' | 'TOO_LOW' | 'NOT_ABOVE_OWN_MAX'

export type BidOutcome =
  | { ok: true; auction: Auction; leading: boolean; extended: boolean }
  | { ok: false; reason: BidRejection; minimumCents?: Cents }
