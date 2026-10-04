<script setup lang="ts">
import type { StripeCardElement, StripeLike } from '~/composables/useStripe'

/**
 * Two modes:
 *  - Stripe publishable key configured -> real Stripe Elements card field (test card 4242 4242 4242 4242)
 *  - otherwise                          -> Stripe's documented TEST tokens, to demo success and failure paths
 * Either way the parent calls getPaymentMethodId() and sends only the token to our backend.
 * Raw card numbers never touch our servers.
 */
const props = defineProps<{ stripeKey: string | null }>()

const testTokens = [
  { id: 'pm_card_visa', label: 'Visa · approved' },
  { id: 'pm_card_chargeDeclined', label: 'Card declined' },
  { id: 'pm_card_insufficientFunds', label: 'Insufficient funds' },
  { id: 'pm_card_authenticationRequired', label: '3-D Secure required' },
]
const token = ref('pm_card_visa')

const cardEl = ref<HTMLElement | null>(null)
const cardError = ref<string | null>(null)
const ready = ref(false)
let stripe: StripeLike | null = null
let card: StripeCardElement | null = null

onMounted(async () => {
  if (!props.stripeKey || !cardEl.value) return
  try {
    stripe = await loadStripe(props.stripeKey)
    card = stripe.elements().create('card', { hidePostalCode: true })
    card.mount(cardEl.value)
    card.on('change', (e) => (cardError.value = e.error?.message ?? null))
    ready.value = true
  } catch (e) {
    cardError.value = (e as Error).message
  }
})
onBeforeUnmount(() => card?.destroy())

async function getPaymentMethodId(): Promise<string> {
  if (!props.stripeKey) return token.value
  if (!stripe || !card) throw new Error('Card form is not ready yet.')
  const { paymentMethod, error } = await stripe.createPaymentMethod({ type: 'card', card })
  if (error || !paymentMethod) throw new Error(error?.message ?? 'Card could not be verified.')
  return paymentMethod.id
}

/** For 3-D Secure: let Stripe.js show the bank's challenge. Returns false if not possible here. */
async function handleNextAction(clientSecret: string): Promise<boolean> {
  if (!stripe) return false
  const { error } = await stripe.handleNextAction({ clientSecret })
  if (error) throw new Error(error.message ?? 'Authentication failed.')
  return true
}

defineExpose({ getPaymentMethodId, handleNextAction })
</script>

<template>
  <div class="pm">
    <template v-if="stripeKey">
      <label class="field">Card (Stripe test mode)</label>
      <div ref="cardEl" class="card-input" :class="{ loading: !ready }" />
      <p v-if="cardError" class="subtle err">{{ cardError }}</p>
      <p class="subtle">Test card <code>4242 4242 4242 4242</code>, any future date, any CVC. For 3-D Secure use <code>4000 0027 6000 3184</code>.</p>
    </template>
    <template v-else>
      <label class="field">
        Test payment method
        <select v-model="token">
          <option v-for="t in testTokens" :key="t.id" :value="t.id">{{ t.label }}</option>
        </select>
      </label>
      <p class="subtle">Simulated provider using Stripe's test tokens: try a decline to see the error path.</p>
    </template>
  </div>
</template>

<style scoped>
.pm {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.card-input {
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 11px 10px;
  background: #fff;
  min-height: 40px;
}
.card-input.loading {
  background: var(--surface-2);
}
.err {
  color: var(--bad);
}
code {
  font-family: var(--mono);
  font-size: 0.85em;
}
</style>
