import type { Cents } from '~/types/equipment'

export type EscrowState = 'AWAITING_DEPOSIT_CAPTURE' | 'AWAITING_BALANCE' | 'FUNDED' | 'RELEASED' | 'PAID_OUT'
export type PaymentStatus = 'PROCESSING' | 'REQUIRES_ACTION' | 'AUTHORIZED' | 'CAPTURED' | 'CANCELED' | 'FAILED'

export interface JournalLine {
  accountCode: string
  debitCents: Cents
  creditCents: Cents
}

export interface JournalEntry {
  id: string
  kind: string
  reference: string
  description: string
  createdAt: string
  lines: JournalLine[]
}

export interface PaymentSummary {
  id: string
  provider: string
  providerRef: string | null
  purpose: 'DEPOSIT' | 'BALANCE'
  amountCents: Cents
  status: PaymentStatus
}

export interface EscrowDeal {
  id: string
  auctionId: string
  equipment: { id: string; title: string; category: string }
  buyerId: string
  sellerId: string
  hammerCents: Cents
  depositCents: Cents
  balanceDueCents: Cents
  feeCents: Cents
  sellerProceedsCents: Cents
  state: EscrowState
  depositPayment: PaymentSummary | null
  balancePayment: PaymentSummary | null
  journal: JournalEntry[]
  createdAt: string
  updatedAt: string
}

export interface AccountBalance {
  code: string
  type: 'ASSET' | 'LIABILITY' | 'EQUITY' | 'REVENUE' | 'EXPENSE'
  name: string
  debitsCents: Cents
  creditsCents: Cents
  balanceCents: Cents
}

export interface TrialBalance {
  accounts: AccountBalance[]
  totalDebitsCents: Cents
  totalCreditsCents: Cents
  balanced: boolean
}

export interface AuditRecord {
  seq: number
  eventType: string
  subject: string
  actor: string
  payload: string
  createdAt: string
  prevHash: string
  hash: string
}

export interface AuditVerification {
  valid: boolean
  entries: number
  firstBrokenSeq: number | null
  reason: string | null
}

export interface PaymentConfig {
  gateway: 'SIMULATED' | 'STRIPE'
  stripePublishableKey: string | null
}

export const ESCROW_STEPS: { state: EscrowState; label: string }[] = [
  { state: 'AWAITING_DEPOSIT_CAPTURE', label: 'Deposit captured' },
  { state: 'AWAITING_BALANCE', label: 'Balance due' },
  { state: 'FUNDED', label: 'Funded in escrow' },
  { state: 'RELEASED', label: 'Released to seller' },
  { state: 'PAID_OUT', label: 'Seller paid' },
]
