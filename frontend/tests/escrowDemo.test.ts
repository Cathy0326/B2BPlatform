import { beforeEach, describe, expect, it } from 'vitest'
import { createMockAuctionApi } from '~/services/auctionApi'
import { createMockEscrowApi, EscrowStateError, PaymentDeclinedError, type EscrowApi } from '~/services/escrowApi'

/** The in-browser escrow demo must follow the same rules as the backend: state machine, double entry, audit chain. */
describe('in-browser escrow demo', () => {
  let clock: number
  let user: string
  let escrow: EscrowApi
  let auctions: ReturnType<typeof createMockAuctionApi>

  beforeEach(() => {
    clock = Date.parse('2026-10-04T12:00:00Z')
    user = 'u-alice'
    auctions = createMockAuctionApi(() => clock)
    escrow = createMockEscrowApi({ currentUserId: () => user, auctions, now: () => clock })
  })

  const awaiting = async () => (await escrow.myDeals()).find((d) => d.state === 'AWAITING_BALANCE')!

  it('gives each visitor one settled deal and one waiting for the balance', async () => {
    const deals = await escrow.myDeals()

    expect(deals.map((d) => d.state).sort()).toEqual(['AWAITING_BALANCE', 'PAID_OUT'])
    const settled = deals.find((d) => d.state === 'PAID_OUT')!
    expect(settled.journal.map((e) => e.kind)).toEqual(['DEPOSIT_CAPTURED', 'BALANCE_RECEIVED', 'ESCROW_RELEASED', 'SELLER_PAYOUT'])
    expect(settled.feeCents).toBe(525_000) // 5% of $105,000
    expect(settled.sellerProceedsCents).toBe(9_975_000)
  })

  it('walks the whole state machine: pay balance, then confirm delivery', async () => {
    const deal = await awaiting()

    const funded = await escrow.payBalance(deal.id, 'pm_card_visa')
    expect(funded.state).toBe('FUNDED')
    expect(funded.balancePayment?.amountCents).toBe(deal.hammerCents - deal.depositCents)

    const paid = await escrow.confirmDelivery(deal.id)
    expect(paid.state).toBe('PAID_OUT')
    expect(paid.journal.map((e) => e.kind)).toEqual(['DEPOSIT_CAPTURED', 'BALANCE_RECEIVED', 'ESCROW_RELEASED', 'SELLER_PAYOUT'])
  })

  it('keeps the books balanced and empties escrow once the seller is paid', async () => {
    const deal = await awaiting()
    await escrow.payBalance(deal.id, null)
    await escrow.confirmDelivery(deal.id)

    const { trialBalance, verifyAuditChain } = await escrow.ledgerOverview()
    const balance = (code: string) => trialBalance.accounts.find((a) => a.code === code)!.balanceCents

    expect(trialBalance.balanced).toBe(true)
    expect(balance(`escrow:${deal.id}`)).toBe(0)
    expect(balance(`seller_payable:${deal.sellerId}`)).toBe(0)
    expect(balance('platform_revenue')).toBe(525_000 + deal.feeCents) // fees from both deals
    expect(balance('platform_cash')).toBe(balance('platform_revenue')) // cash left = fees kept
    expect(verifyAuditChain.valid).toBe(true)
    for (const e of (await escrow.ledgerOverview()).journalEntries) {
      expect(e.lines.reduce((s, l) => s + l.debitCents - l.creditCents, 0)).toBe(0)
    }
  })

  it('rejects steps out of order, so a repeated click cannot move money twice', async () => {
    const deal = await awaiting()

    await expect(escrow.confirmDelivery(deal.id)).rejects.toThrow(EscrowStateError)
    await escrow.payBalance(deal.id, null)
    await expect(escrow.payBalance(deal.id, null)).rejects.toThrow(EscrowStateError)

    const after = (await escrow.myDeals()).find((d) => d.id === deal.id)!
    expect(after.journal.filter((e) => e.kind === 'BALANCE_RECEIVED')).toHaveLength(1)
  })

  it('leaves the deal untouched when the card is declined', async () => {
    const deal = await awaiting()

    await expect(escrow.payBalance(deal.id, 'pm_card_chargeDeclined')).rejects.toThrow(PaymentDeclinedError)

    const after = (await escrow.myDeals()).find((d) => d.id === deal.id)!
    expect(after.state).toBe('AWAITING_BALANCE')
    expect(after.journal).toHaveLength(1)
    expect(after.balancePayment).toBeNull()
  })

  it("never shows or touches another visitor's deals", async () => {
    const aliceDeal = await awaiting()
    user = 'u-bob'

    expect((await escrow.myDeals()).every((d) => d.buyerId === 'u-bob')).toBe(true)
    await expect(escrow.payBalance(aliceDeal.id, null)).rejects.toThrow('Deal not found')
  })

  it('turns an auction won in the mock auction room into a deal with the deposit captured', async () => {
    await auctions.registerToBid('au-2002', user) // no reserve, ends in about 6 minutes
    const bid = await auctions.placeBid('au-2002', user, 50_000_000)
    expect(bid.ok).toBe(true)
    clock += 60 * 60_000 // the auction has ended

    const deal = (await escrow.myDeals()).find((d) => d.auctionId === 'au-2002')!

    expect(deal.state).toBe('AWAITING_BALANCE')
    expect(deal.depositCents).toBe(900_000)
    expect(deal.journal[0]!.kind).toBe('DEPOSIT_CAPTURED')
    expect((await escrow.myDeals()).filter((d) => d.auctionId === 'au-2002')).toHaveLength(1) // settled once
  })

  it('returns copies, so callers cannot rewrite history', async () => {
    const deal = await awaiting()
    deal.journal[0]!.lines[0]!.debitCents = 1

    const fresh = (await escrow.myDeals()).find((d) => d.id === deal.id)!
    expect(fresh.journal[0]!.lines[0]!.debitCents).toBe(deal.depositCents)
  })
})
