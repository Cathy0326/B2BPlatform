import type { AuditRecord, AuditVerification, EscrowDeal, JournalEntry, PaymentConfig, TrialBalance } from '~/types/escrow'
import { newIdempotencyKey, type GraphQlClient } from './client'

const JOURNAL = 'id kind reference description createdAt lines { accountCode debitCents creditCents }'
const PAYMENT = 'id provider providerRef purpose amountCents status'
const DEAL = `
  id auctionId equipment { id title category } buyerId sellerId
  hammerCents depositCents balanceDueCents feeCents sellerProceedsCents state
  depositPayment { ${PAYMENT} } balancePayment { ${PAYMENT} }
  journal { ${JOURNAL} } createdAt updatedAt
`

/** Phase 3 operations: escrow, ledger, audit. Backend only (there is no mock for money movement). */
export function createEscrowApi(gql: GraphQlClient) {
  return {
    async paymentConfig() {
      return (await gql.request<{ paymentConfig: PaymentConfig }>('{ paymentConfig { gateway stripePublishableKey } }')).paymentConfig
    },
    async myDeals() {
      return (await gql.request<{ myDeals: EscrowDeal[] }>(`{ myDeals { ${DEAL} } }`)).myDeals
    },
    async payBalance(dealId: string, paymentMethodId: string | null) {
      // REQUIRED by the API: a fresh key per click; the server returns the stored result on a retry.
      const data = await gql.request<{ payBalance: EscrowDeal }>(
        `mutation($d: ID!, $pm: String) { payBalance(dealId: $d, paymentMethodId: $pm) { ${DEAL} } }`,
        { d: dealId, pm: paymentMethodId },
        { 'Idempotency-Key': newIdempotencyKey() },
      )
      return data.payBalance
    },
    async confirmDelivery(dealId: string) {
      const data = await gql.request<{ confirmDelivery: EscrowDeal }>(
        `mutation($d: ID!) { confirmDelivery(dealId: $d) { ${DEAL} } }`,
        { d: dealId },
      )
      return data.confirmDelivery
    },
    async ledgerOverview() {
      return gql.request<{ trialBalance: TrialBalance; journalEntries: JournalEntry[]; auditLog: AuditRecord[]; verifyAuditChain: AuditVerification }>(`{
        trialBalance { accounts { code type name debitsCents creditsCents balanceCents } totalDebitsCents totalCreditsCents balanced }
        journalEntries(last: 25) { ${JOURNAL} }
        auditLog(last: 15) { seq eventType subject actor payload createdAt prevHash hash }
        verifyAuditChain { valid entries firstBrokenSeq reason }
      }`)
    },
  }
}
