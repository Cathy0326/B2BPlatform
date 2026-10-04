import type { AuctionApi } from '~/services/auctionApi'
import type {
  AuditRecord,
  AuditVerification,
  EscrowDeal,
  EscrowState,
  JournalEntry,
  JournalLine,
  PaymentConfig,
  PaymentSummary,
  TrialBalance,
} from '~/types/escrow'
import { MOCK_EQUIPMENT } from '~/data/equipment'
import { settleResult } from '~/utils/auction'
import { chainRecord, verifyChain } from '~/utils/auditChain'
import { assertBalanced, credit, debit, platformFeeCents, trialBalance, type LedgerAccount } from '~/utils/ledger'

export interface LedgerOverview {
  trialBalance: TrialBalance
  journalEntries: JournalEntry[]
  auditLog: AuditRecord[]
  verifyAuditChain: AuditVerification
}

/** Escrow, ledger and audit. Implemented by the GraphQL client (backend) and by the in-browser demo below. */
export interface EscrowApi {
  paymentConfig(): Promise<PaymentConfig>
  myDeals(): Promise<EscrowDeal[]>
  payBalance(dealId: string, paymentMethodId: string | null): Promise<EscrowDeal>
  confirmDelivery(dealId: string): Promise<EscrowDeal>
  ledgerOverview(): Promise<LedgerOverview>
}

export class EscrowStateError extends Error {}
export class PaymentDeclinedError extends Error {}

const PLATFORM_CASH = 'platform_cash'
const PLATFORM_REVENUE = 'platform_revenue'
const HOUR = 3_600_000
const DAY = 24 * HOUR

interface Options {
  /** The signed-in user's id ('' = anonymous). */
  currentUserId: () => string
  /** The in-browser auction "server": auctions the user wins there become deals here. */
  auctions: AuctionApi
  now?: () => number
}

/**
 * In-browser demo of the backend's escrow module (DefaultEscrow + JdbcLedger + JdbcAuditTrail):
 * the same state machine, the same journal entries, the same fee rule and the same audit hash scheme.
 * Nothing leaves the browser and a page reload starts over.
 *
 * Each visitor gets two seeded deals (one settled, one waiting for the balance) so the flow can be
 * clicked through immediately; auctions the visitor actually wins in the mock auction room are added too.
 */
export function createMockEscrowApi({ currentUserId, auctions, now = Date.now }: Options): EscrowApi {
  const accounts = new Map<string, LedgerAccount>([
    [PLATFORM_CASH, { code: PLATFORM_CASH, type: 'ASSET', name: 'Platform bank account (funds received from payment processor)' }],
    [PLATFORM_REVENUE, { code: PLATFORM_REVENUE, type: 'REVENUE', name: 'Marketplace fees earned' }],
  ])
  const journal: JournalEntry[] = [] // append-only: entries are never edited or removed
  const audit: AuditRecord[] = []
  const deals = new Map<string, EscrowDeal>()
  const seededFor = new Set<string>()
  let nextDeal = 1001
  let nextEntry = 1
  let nextPayment = 1
  // Async work (SHA-256) is serialized so audit records chain in order even if two clicks overlap.
  let queue: Promise<unknown> = Promise.resolve()
  const serialized = <T>(fn: () => Promise<T>): Promise<T> => {
    const run = queue.then(fn, fn)
    queue = run.catch(() => undefined)
    return run
  }

  const iso = (ms: number) => new Date(ms).toISOString()

  function ensureAccount(code: string, type: LedgerAccount['type'], name: string) {
    if (!accounts.has(code)) accounts.set(code, { code, type, name })
  }

  function post(deal: EscrowDeal, kind: string, description: string, lines: JournalLine[], at: number) {
    assertBalanced(lines) // the backend's deferred constraint trigger, in TypeScript
    const entry: JournalEntry = { id: String(nextEntry++), kind, reference: deal.id, description, createdAt: iso(at), lines }
    journal.push(entry)
    deal.journal.push(entry)
  }

  async function record(eventType: string, subject: string, actor: string, payload: Record<string, unknown>, at: number) {
    audit.push(await chainRecord(audit.at(-1), { eventType, subject, actor, payload, createdAt: iso(at) }))
  }

  async function transition(deal: EscrowDeal, from: EscrowState, to: EscrowState, actor: string, at: number) {
    // Compare-and-set, like `UPDATE ... WHERE state = :from`: a stale or repeated click cannot move money twice.
    if (deal.state !== from) throw new EscrowStateError(`Deal ${deal.id} is ${deal.state.replaceAll('_', ' ').toLowerCase()}, not ${from.replaceAll('_', ' ').toLowerCase()}`)
    deal.state = to
    deal.updatedAt = iso(at)
    await record(`DEAL_${to}`, deal.id, actor, { from }, at)
  }

  function payment(purpose: PaymentSummary['purpose'], amountCents: number): PaymentSummary {
    const n = nextPayment++
    return { id: `pay-${n}`, provider: 'SIMULATED', providerRef: `sim_pi_${String(n).padStart(6, '0')}`, purpose, amountCents, status: 'CAPTURED' }
  }

  /** Same test tokens as the backend's SimulatedPaymentGateway. */
  function charge(paymentMethodId: string | null) {
    if (paymentMethodId === 'pm_card_chargeDeclined') throw new PaymentDeclinedError('Your card was declined.')
    if (paymentMethodId === 'pm_card_insufficientFunds') throw new PaymentDeclinedError('Your card has insufficient funds.')
  }

  async function createDeal(auctionId: string, equipmentId: string, buyerId: string, hammerCents: number, depositHeldCents: number, at: number) {
    const eq = MOCK_EQUIPMENT.find((e) => e.id === equipmentId)
    const sellerId = `s-${equipmentId.replace(/^eq-/, '')}`
    const depositCents = Math.min(depositHeldCents, hammerCents)
    const deal: EscrowDeal = {
      id: `D-${nextDeal++}`,
      auctionId,
      equipment: { id: equipmentId, title: eq?.title ?? equipmentId, category: eq?.category ?? '' },
      buyerId,
      sellerId,
      hammerCents,
      depositCents,
      balanceDueCents: hammerCents - depositCents,
      feeCents: platformFeeCents(hammerCents),
      sellerProceedsCents: hammerCents - platformFeeCents(hammerCents),
      state: 'AWAITING_DEPOSIT_CAPTURE',
      depositPayment: depositCents > 0 ? payment('DEPOSIT', depositCents) : null,
      balancePayment: null,
      journal: [],
      createdAt: iso(at),
      updatedAt: iso(at),
    }
    deals.set(deal.id, deal)
    ensureAccount(`escrow:${deal.id}`, 'LIABILITY', `Buyer funds held in escrow for ${deal.id}`)
    ensureAccount(`seller_payable:${sellerId}`, 'LIABILITY', `Owed to seller ${sellerId}`)
    await record('DEAL_CREATED', deal.id, 'system', { auctionId, buyerId, depositCents, feeCents: deal.feeCents, hammerCents }, at)
    if (depositCents > 0) {
      post(deal, 'DEPOSIT_CAPTURED', "Winner's deposit captured into escrow", [debit(PLATFORM_CASH, depositCents), credit(`escrow:${deal.id}`, depositCents)], at)
    }
    await transition(deal, 'AWAITING_DEPOSIT_CAPTURE', 'AWAITING_BALANCE', 'system', at)
    return deal
  }

  async function payBalanceAt(deal: EscrowDeal, actor: string, paymentMethodId: string | null, at: number) {
    if (deal.state !== 'AWAITING_BALANCE') throw new EscrowStateError(`Deal ${deal.id} is not waiting for the balance`)
    charge(paymentMethodId)
    deal.balancePayment = payment('BALANCE', deal.balanceDueCents)
    post(deal, 'BALANCE_RECEIVED', 'Buyer paid the remaining balance into escrow', [debit(PLATFORM_CASH, deal.balanceDueCents), credit(`escrow:${deal.id}`, deal.balanceDueCents)], at)
    await transition(deal, 'AWAITING_BALANCE', 'FUNDED', actor, at)
  }

  async function confirmDeliveryAt(deal: EscrowDeal, actor: string, at: number) {
    if (deal.state !== 'FUNDED') throw new EscrowStateError(`Deal ${deal.id} is not funded yet`)
    post(deal, 'ESCROW_RELEASED', 'Buyer accepted delivery; release escrow minus 5% fee', [
      debit(`escrow:${deal.id}`, deal.hammerCents),
      credit(`seller_payable:${deal.sellerId}`, deal.sellerProceedsCents),
      credit(PLATFORM_REVENUE, deal.feeCents),
    ], at)
    await transition(deal, 'FUNDED', 'RELEASED', actor, at)
    // The backend runs the payout as a background job; the demo does it immediately.
    post(deal, 'SELLER_PAYOUT', `Payout to seller ${deal.sellerId}`, [debit(`seller_payable:${deal.sellerId}`, deal.sellerProceedsCents), credit(PLATFORM_CASH, deal.sellerProceedsCents)], at)
    await transition(deal, 'RELEASED', 'PAID_OUT', 'system', at)
  }

  /** Two deals per visitor so every step can be tried right away (amounts match the "How escrow works" page). */
  async function seed(buyerId: string) {
    if (seededFor.has(buyerId)) return
    seededFor.add(buyerId)
    const t = now()
    const settled = await createDeal('au-1901', 'eq-1001', buyerId, 10_500_000, 1_000_000, t - 3 * DAY)
    await payBalanceAt(settled, buyerId, 'pm_card_visa', t - 3 * DAY + HOUR)
    await confirmDeliveryAt(settled, buyerId, t - DAY)
    await createDeal('au-1902', 'eq-1011', buyerId, 4_250_000, 500_000, t - 2 * HOUR)
  }

  /** Auctions the visitor won in the mock auction room become deals (the backend's EscrowJobs.settle). */
  async function settleWonAuctions(buyerId: string) {
    const t = now()
    for (const a of await auctions.list(buyerId)) {
      const result = settleResult(a, t)
      if (!result?.sold || result.winnerId !== buyerId) continue
      if ([...deals.values()].some((d) => d.auctionId === a.id)) continue
      const hold = await auctions.getHold(a.id, buyerId)
      await createDeal(a.id, a.equipmentId, buyerId, result.hammerPriceCents!, hold?.amountCents ?? 0, t)
    }
  }

  function owned(dealId: string) {
    const deal = deals.get(dealId)
    // Same answer for "missing" and "someone else's": never confirm that another user's deal exists.
    if (!deal || deal.buyerId !== currentUserId()) throw new EscrowStateError('Deal not found')
    return deal
  }

  return {
    async paymentConfig() {
      return { gateway: 'SIMULATED', stripePublishableKey: null }
    },
    myDeals: () =>
      serialized(async () => {
        const me = currentUserId()
        if (!me) return []
        await seed(me)
        await settleWonAuctions(me)
        return structuredClone([...deals.values()].filter((d) => d.buyerId === me).sort((a, b) => b.createdAt.localeCompare(a.createdAt)))
      }),
    payBalance: (dealId, paymentMethodId) =>
      serialized(async () => {
        const deal = owned(dealId)
        await payBalanceAt(deal, deal.buyerId, paymentMethodId, now())
        return structuredClone(deal)
      }),
    confirmDelivery: (dealId) =>
      serialized(async () => {
        const deal = owned(dealId)
        await confirmDeliveryAt(deal, deal.buyerId, now())
        return structuredClone(deal)
      }),
    ledgerOverview: () =>
      serialized(async () => {
        const me = currentUserId()
        if (me) await seed(me)
        return structuredClone({
          trialBalance: trialBalance([...accounts.values()], journal),
          journalEntries: journal.slice(-25).reverse(),
          auditLog: audit.slice(-15).reverse(),
          verifyAuditChain: await verifyChain(audit),
        })
      }),
  }
}
