<script setup lang="ts">
import type { EscrowDeal } from '~/types/escrow'
import { ESCROW_STEPS } from '~/types/escrow'
import { createEscrowApi } from '~/services/graphql/escrowApi'

useSeoMeta({ title: 'My escrow deals' })

const gql = useGraphQlClient()
const api = gql ? createEscrowApi(gql) : null
const { data: paymentConfig } = usePaymentConfig()

const { data: deals, status, refresh } = useAsyncData('my-deals', async () => (api ? api.myDeals() : []), {
  server: false,
  default: () => [] as EscrowDeal[],
})

const busy = ref<string | null>(null)
const messages = ref<Record<string, { kind: 'success' | 'error'; text: string }>>({})
const pickers = ref<Record<string, { getPaymentMethodId(): Promise<string> } | null>>({})

function stepIndex(d: EscrowDeal) {
  return ESCROW_STEPS.findIndex((s) => s.state === d.state)
}

async function act(d: EscrowDeal, action: 'pay' | 'confirm') {
  if (!api) return
  busy.value = d.id
  try {
    if (action === 'pay') {
      const pm = (await pickers.value[d.id]?.getPaymentMethodId()) ?? null
      await api.payBalance(d.id, pm)
      messages.value[d.id] = { kind: 'success', text: 'Balance paid. The full price is now held in escrow.' }
    } else {
      await api.confirmDelivery(d.id)
      messages.value[d.id] = { kind: 'success', text: 'Delivery confirmed. Escrow released and the seller has been paid.' }
    }
    await refresh()
  } catch (e) {
    messages.value[d.id] = { kind: 'error', text: (e as Error).message }
  } finally {
    busy.value = null
  }
}

// Settlement runs in the background a few seconds after an auction ends: poll briefly while waiting.
let poll: ReturnType<typeof setInterval> | undefined
onMounted(() => (poll = setInterval(() => refresh(), 5000)))
onBeforeUnmount(() => clearInterval(poll))
</script>

<template>
  <div class="stack">
    <div>
      <h1>My escrow deals</h1>
      <p class="muted">Auctions you won. The money stays in escrow until you confirm the machine arrived as described.</p>
    </div>

    <div v-if="!api" class="card">
      <p>Escrow needs the backend. Start the Spring Boot API and set <code>NUXT_PUBLIC_GRAPHQL_URL</code>.</p>
      <NuxtLink to="/escrow">How escrow works →</NuxtLink>
    </div>
    <p v-else-if="(status === 'idle' || status === 'pending') && !deals.length" class="muted">Loading deals…</p>
    <div v-else-if="!deals.length" class="card">
      <p>No deals yet. Win an auction and it will appear here a few seconds after the auction closes.</p>
      <NuxtLink to="/auctions" class="btn btn-primary">Browse auctions</NuxtLink>
    </div>

    <article v-for="d in deals" :key="d.id" class="card stack deal">
      <div class="spread">
        <div>
          <p class="eyebrow">{{ d.id }}</p>
          <h2>{{ d.equipment.title }}</h2>
        </div>
        <span class="badge" :class="d.state === 'PAID_OUT' ? 'badge-good' : 'badge-warn'">{{ d.state.replaceAll('_', ' ') }}</span>
      </div>

      <ol class="steps" aria-label="Escrow progress">
        <li v-for="(s, i) in ESCROW_STEPS" :key="s.state" :class="{ done: i < stepIndex(d) || d.state === 'PAID_OUT', current: i === stepIndex(d) && d.state !== 'PAID_OUT' }">
          <span class="dot">{{ i < stepIndex(d) || d.state === 'PAID_OUT' ? '✓' : i + 1 }}</span>
          <span>{{ s.label }}</span>
        </li>
      </ol>

      <div class="figures num">
        <div><span class="eyebrow">Hammer price</span><strong>{{ formatCents(d.hammerCents) }}</strong></div>
        <div><span class="eyebrow">Deposit (captured)</span><strong>{{ formatCents(d.depositCents) }}</strong></div>
        <div><span class="eyebrow">Balance due</span><strong>{{ d.state === 'AWAITING_BALANCE' ? formatCents(d.balanceDueCents) : formatCents(0) }}</strong></div>
        <div><span class="eyebrow">Seller receives</span><strong>{{ formatCents(d.sellerProceedsCents) }}</strong><small class="subtle">after 5% fee {{ formatCents(d.feeCents) }}</small></div>
      </div>

      <div v-if="d.state === 'AWAITING_BALANCE'" class="action stack">
        <PaymentMethodPicker :ref="(el) => (pickers[d.id] = el as never)" :stripe-key="paymentConfig?.stripePublishableKey ?? null" />
        <button class="btn btn-primary" :disabled="busy === d.id" @click="act(d, 'pay')">
          Pay {{ formatCents(d.balanceDueCents) }} into escrow
        </button>
      </div>
      <div v-else-if="d.state === 'FUNDED'" class="action">
        <p class="muted">The seller has been told to ship. Once the machine arrives and matches the listing:</p>
        <button class="btn btn-primary" :disabled="busy === d.id" @click="act(d, 'confirm')">Confirm delivery &amp; release escrow</button>
      </div>

      <div v-if="messages[d.id]" class="alert" :class="messages[d.id]!.kind === 'success' ? 'alert-success' : 'alert-error'" role="status">
        {{ messages[d.id]!.text }}
      </div>

      <details>
        <summary><strong>Ledger entries for this deal ({{ d.journal.length }})</strong></summary>
        <JournalTable :entries="d.journal" />
      </details>
    </article>
  </div>
</template>

<style scoped>
.deal h2 {
  margin: 0;
}
.steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 6px;
}
.steps li {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 0.78rem;
  color: var(--text-3);
  text-align: center;
}
.dot {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--surface-2);
  border: 1px solid var(--border);
  font-weight: 700;
}
.steps li.done {
  color: var(--good);
}
.steps li.done .dot {
  background: var(--good-bg);
  border-color: transparent;
}
.steps li.current {
  color: var(--text);
  font-weight: 600;
}
.steps li.current .dot {
  background: var(--brand);
  color: var(--brand-ink);
  border-color: transparent;
}
.figures {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}
.figures div {
  display: flex;
  flex-direction: column;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}
.action {
  max-width: 420px;
}
summary {
  cursor: pointer;
}
code {
  font-family: var(--mono);
}
</style>
