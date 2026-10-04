import type { PaymentConfig } from '~/types/escrow'
import { createEscrowApi } from '~/services/graphql/escrowApi'

/** Which provider the backend uses, and the Stripe publishable key (if Stripe Elements should be shown). */
export function usePaymentConfig() {
  const gql = useGraphQlClient()
  return useAsyncData<PaymentConfig | null>('payment-config', async () => (gql ? createEscrowApi(gql).paymentConfig() : null), {
    server: false,
    default: () => null,
  })
}
