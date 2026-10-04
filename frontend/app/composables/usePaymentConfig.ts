import type { PaymentConfig } from '~/types/escrow'

/** Which provider the backend uses, and the Stripe publishable key (if Stripe Elements should be shown). */
export function usePaymentConfig() {
  const api = useEscrowApi()
  return useAsyncData<PaymentConfig | null>('payment-config', () => api.paymentConfig(), {
    server: false,
    default: () => null,
  })
}
