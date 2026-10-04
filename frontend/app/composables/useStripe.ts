/**
 * Loads Stripe.js from Stripe's CDN (required by PCI rules: card data must be collected by Stripe's
 * own iframe, never by our code) and returns a Stripe instance for the publishable key.
 * Minimal typing on purpose; we use only elements(), createPaymentMethod() and handleNextAction().
 */
export interface StripeLike {
  elements(): { create(type: 'card', options?: Record<string, unknown>): StripeCardElement }
  createPaymentMethod(opts: { type: 'card'; card: StripeCardElement }): Promise<{ paymentMethod?: { id: string }; error?: { message?: string } }>
  handleNextAction(opts: { clientSecret: string }): Promise<{ error?: { message?: string } }>
}
export interface StripeCardElement {
  mount(el: HTMLElement): void
  destroy(): void
  on(event: 'change', cb: (e: { error?: { message: string } }) => void): void
}

let loading: Promise<void> | null = null

function loadScript(): Promise<void> {
  if ((window as unknown as { Stripe?: unknown }).Stripe) return Promise.resolve()
  loading ??= new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://js.stripe.com/v3/'
    s.onload = () => resolve()
    s.onerror = () => reject(new Error('Could not load Stripe.js'))
    document.head.appendChild(s)
  })
  return loading
}

export async function loadStripe(publishableKey: string): Promise<StripeLike> {
  await loadScript()
  const factory = (window as unknown as { Stripe: (key: string) => StripeLike }).Stripe
  return factory(publishableKey)
}
