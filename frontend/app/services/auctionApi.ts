import type { Auction, BidOutcome } from '~/types/auction'
import type { Cents } from '~/types/equipment'
import { buildMockAuctions } from '~/data/auctions'
import { auctionStatus, bidIncrementCents, minimumNextBidCents, placeBid, publicView } from '~/utils/auction'

/**
 * Escrow deposit a bidder must HOLD before bidding (like a card pre-authorization).
 *   HELD     -> money reserved, not taken
 *   RELEASED -> auction lost/ended, hold dropped (refund)
 *   APPLIED  -> winner's deposit counts toward the final payment
 * Phase 3 backs this with the double-entry ledger + Stripe PaymentIntent (capture_method=manual).
 */
export interface DepositHold {
  auctionId: string
  bidderId: string
  amountCents: Cents
  status: 'HELD' | 'RELEASED' | 'APPLIED'
  createdAt: number
}

export interface AuctionApi {
  list(viewerId: string | null): Promise<Auction[]>
  get(id: string, viewerId: string | null): Promise<Auction | null>
  getHold(auctionId: string, bidderId: string): Promise<DepositHold | null>
  registerToBid(auctionId: string, bidderId: string): Promise<DepositHold>
  placeBid(auctionId: string, bidderId: string, maxCents: Cents): Promise<BidOutcome>
  /** Live updates (Phase 2: GraphQL subscription over WebSocket). Returns an unsubscribe fn. */
  subscribe(auctionId: string, viewerId: string | null, onChange: (a: Auction) => void): () => void
}

export class NotRegisteredError extends Error {
  constructor() {
    super('Place a refundable deposit hold before bidding.')
  }
}

const BOT_IDS = ['b-4821', 'b-1377', 'b-9054', 'b-2210', 'b-7788', 'b-3141', 'b-6060']

/**
 * In-browser mock "server". It owns the authoritative auction state and runs the same
 * pure engine (utils/auction.ts) that the tests cover. Rival bidders are simulated with a
 * timer while someone is watching, so the auction room feels live in a static demo.
 */
export function createMockAuctionApi(now: () => number = Date.now): AuctionApi {
  const db = new Map(buildMockAuctions(now()).map((a) => [a.id, a]))
  const holds = new Map<string, DepositHold>()
  const listeners = new Map<string, Set<(a: Auction) => void>>()
  const bots = new Map<string, ReturnType<typeof setInterval>>()
  const key = (a: string, b: string) => `${a}:${b}`

  function emit(id: string) {
    const a = db.get(id)
    if (!a) return
    listeners.get(id)?.forEach((fn) => fn(structuredClone(a)))
  }

  function botTick(id: string) {
    const a = db.get(id)
    if (!a || auctionStatus(a, now()) !== 'LIVE') return
    if (Math.random() > 0.35) return
    const candidates = BOT_IDS.filter((b) => b !== a.leaderId)
    const bot = candidates[Math.floor(Math.random() * candidates.length)]!
    const min = minimumNextBidCents(a)
    const max = min + bidIncrementCents(min) * Math.floor(Math.random() * 4)
    const r = placeBid(a, bot, max, now())
    if (r.ok) {
      db.set(id, r.auction)
      emit(id)
    }
  }

  return {
    async list(viewerId) {
      return [...db.values()].map((a) => structuredClone(publicView(a, viewerId)))
    },
    async get(id, viewerId) {
      const a = db.get(id)
      return a ? structuredClone(publicView(a, viewerId)) : null
    },
    async getHold(auctionId, bidderId) {
      return holds.get(key(auctionId, bidderId)) ?? null
    },
    async registerToBid(auctionId, bidderId) {
      const a = db.get(auctionId)
      if (!a) throw new Error('Auction not found')
      const k = key(auctionId, bidderId)
      // Idempotent: registering twice returns the same hold instead of holding money twice.
      const existing = holds.get(k)
      if (existing) return existing
      const hold: DepositHold = { auctionId, bidderId, amountCents: a.depositCents, status: 'HELD', createdAt: now() }
      holds.set(k, hold)
      return hold
    },
    async placeBid(auctionId, bidderId, maxCents) {
      const a = db.get(auctionId)
      if (!a) throw new Error('Auction not found')
      if (!holds.has(key(auctionId, bidderId))) throw new NotRegisteredError()
      const r = placeBid(a, bidderId, maxCents, now())
      if (r.ok) {
        db.set(auctionId, r.auction)
        emit(auctionId)
        return { ...r, auction: structuredClone(publicView(r.auction, bidderId)) }
      }
      return r
    },
    subscribe(auctionId, viewerId, onChange) {
      const wrapped = (a: Auction) => onChange(publicView(a, viewerId))
      let set = listeners.get(auctionId)
      if (!set) listeners.set(auctionId, (set = new Set()))
      set.add(wrapped)
      if (!bots.has(auctionId)) bots.set(auctionId, setInterval(() => botTick(auctionId), 6_000))
      return () => {
        set!.delete(wrapped)
        if (set!.size === 0) {
          clearInterval(bots.get(auctionId))
          bots.delete(auctionId)
        }
      }
    },
  }
}
