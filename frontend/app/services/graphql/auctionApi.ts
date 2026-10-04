import type { Auction, BidOutcome, BidRejection } from '~/types/auction'
import { NotRegisteredError, type AuctionApi, type DepositHold } from '~/services/auctionApi'
import { GraphQlError, type GraphQlClient } from './client'

const AUCTION_FIELDS = `
  id equipment { id } startingPriceCents depositCents hasReserve reserveMet
  startsAt endsAt softCloseSeconds currentPriceCents minimumNextBidCents
  leaderId myMaxCents extensions
  bids(last: 30) { bidderId amountCents auto at }
`

interface AuctionDto {
  id: string
  equipment: { id: string }
  startingPriceCents: number
  depositCents: number
  hasReserve: boolean
  reserveMet: boolean
  startsAt: string
  endsAt: string
  softCloseSeconds: number
  currentPriceCents: number
  minimumNextBidCents: number
  leaderId: string | null
  myMaxCents: number | null
  extensions: number
  bids: { bidderId: string; amountCents: number; auto: boolean; at: string }[]
}

/**
 * Server DTO -> the Auction shape the UI already uses.
 * The reserve price itself is never sent (it is secret); the server sends hasReserve/reserveMet instead.
 */
function toAuction(d: AuctionDto): Auction {
  return {
    id: d.id,
    equipmentId: d.equipment.id,
    startingPriceCents: d.startingPriceCents,
    reservePriceCents: null,
    depositCents: d.depositCents,
    startsAt: Date.parse(d.startsAt),
    endsAt: Date.parse(d.endsAt),
    softCloseMs: d.softCloseSeconds * 1000,
    currentPriceCents: d.currentPriceCents,
    leaderId: d.leaderId,
    leaderMaxCents: d.myMaxCents,
    // API returns newest first; the UI stores chronological order.
    bids: [...d.bids].reverse().map((b) => ({ ...b, at: Date.parse(b.at) })),
    extensions: d.extensions,
    server: { hasReserve: d.hasReserve, reserveMet: d.reserveMet, minimumNextBidCents: d.minimumNextBidCents },
  }
}

interface RegistrationDto {
  auctionId: string
  bidderId: string
  depositCents: number
  status: DepositHold['status']
  createdAt: string
}
const toHold = (r: RegistrationDto): DepositHold => ({
  auctionId: r.auctionId,
  bidderId: r.bidderId,
  amountCents: r.depositCents,
  status: r.status,
  createdAt: Date.parse(r.createdAt),
})
const REG_FIELDS = 'auctionId bidderId depositCents status createdAt'

export function createGraphQlAuctionApi(gql: GraphQlClient): AuctionApi {
  return {
    async list() {
      const data = await gql.request<{ auctions: AuctionDto[] }>(`{ auctions { ${AUCTION_FIELDS} } }`)
      return data.auctions.map(toAuction)
    },
    async get(id) {
      const data = await gql.request<{ auction: AuctionDto | null }>(`query($id: ID!) { auction(id: $id) { ${AUCTION_FIELDS} } }`, { id })
      return data.auction ? toAuction(data.auction) : null
    },
    async getHold(auctionId) {
      const data = await gql.request<{ myRegistration: RegistrationDto | null }>(
        `query($a: ID!) { myRegistration(auctionId: $a) { ${REG_FIELDS} } }`,
        { a: auctionId },
      )
      return data.myRegistration ? toHold(data.myRegistration) : null
    },
    async registerToBid(auctionId) {
      const data = await gql.request<{ registerToBid: RegistrationDto }>(
        `mutation($a: ID!) { registerToBid(auctionId: $a) { ${REG_FIELDS} } }`,
        { a: auctionId },
      )
      return toHold(data.registerToBid)
    },
    async placeBid(auctionId, _bidderId, maxCents): Promise<BidOutcome> {
      try {
        const data = await gql.request<{
          placeBid: { accepted: boolean; reason: BidRejection | null; minimumCents: number | null; leading: boolean; extended: boolean; auction: AuctionDto }
        }>(
          `mutation($a: ID!, $m: Long!) { placeBid(auctionId: $a, maxCents: $m) { accepted reason minimumCents leading extended auction { ${AUCTION_FIELDS} } } }`,
          { a: auctionId, m: maxCents },
        )
        const r = data.placeBid
        if (!r.accepted) return { ok: false, reason: r.reason!, minimumCents: r.minimumCents ?? undefined }
        return { ok: true, auction: toAuction(r.auction), leading: r.leading, extended: r.extended }
      } catch (e) {
        if (e instanceof GraphQlError && e.code === 'NOT_REGISTERED') throw new NotRegisteredError()
        throw e
      }
    },
    subscribe(auctionId, _viewerId, onChange) {
      return gql.subscribe<{ auctionUpdated: AuctionDto }>(
        `subscription($a: ID!) { auctionUpdated(auctionId: $a) { ${AUCTION_FIELDS} } }`,
        { a: auctionId },
        (d) => onChange(toAuction(d.auctionUpdated)),
      )
    },
  }
}
