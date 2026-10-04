<script setup lang="ts">
import type { Auction, AuctionStatus } from '~/types/auction'
import type { DepositHold } from '~/services/auctionApi'
import { bidIncrementCents } from '~/utils/auction'
import { parseDollarsToCents } from '~/utils/money'

const props = defineProps<{
  auction: Auction
  status: AuctionStatus | null
  minimumNext: number
  isLeader: boolean
  myMax: number | null
  hold: DepositHold | null
  busy: boolean
}>()
const emit = defineEmits<{ register: []; bid: [maxCents: number] }>()

const input = ref('')
const parsed = computed(() => parseDollarsToCents(input.value))
const quick = computed(() => {
  const step = bidIncrementCents(props.minimumNext)
  return [props.minimumNext, props.minimumNext + 2 * step, props.minimumNext + 5 * step]
})
const inputError = computed(() => {
  if (!input.value) return null
  if (parsed.value == null) return 'Enter a dollar amount, e.g. 172500'
  if (!props.isLeader && parsed.value < props.minimumNext) return `Minimum is ${formatCents(props.minimumNext)}`
  return null
})

function submit() {
  if (parsed.value == null || inputError.value) return
  emit('bid', parsed.value)
  input.value = ''
}
</script>

<template>
  <div class="bid-panel stack">
    <template v-if="status === 'LIVE'">
      <div v-if="!hold" class="stack">
        <p class="muted">
          To bid, place a <strong>refundable deposit hold</strong> of
          <strong class="num">{{ formatCents(auction.depositCents) }}</strong>.
          It is only captured if you win, and released automatically if you don't.
        </p>
        <button class="btn btn-primary btn-block" :disabled="busy" @click="emit('register')">
          Place deposit hold &amp; register
        </button>
        <p class="subtle">Demo mode: no real money moves. Phase 3 connects Stripe (test mode).</p>
      </div>

      <form v-else class="stack" @submit.prevent="submit">
        <div v-if="isLeader" class="alert alert-success">
          You're the high bidder. Your secret max: <strong class="num">{{ formatCents(myMax ?? 0) }}</strong>
        </div>
        <label class="field">
          Your maximum bid (USD)
          <div class="input-prefix">
            <span>$</span>
            <input
              v-model="input"
              inputmode="decimal"
              autocomplete="off"
              :placeholder="(minimumNext / 100).toLocaleString('en-US')"
              :aria-invalid="!!inputError"
            />
          </div>
        </label>
        <p v-if="inputError" class="subtle err">{{ inputError }}</p>
        <div class="quick">
          <button v-for="q in quick" :key="q" type="button" class="btn" @click="input = String(q / 100)">
            {{ formatCentsWhole(q) }}
          </button>
        </div>
        <button class="btn btn-primary btn-block" type="submit" :disabled="busy || !parsed || !!inputError">
          {{ isLeader ? 'Raise my maximum' : 'Place proxy bid' }}
        </button>
        <p class="subtle">
          We bid for you, one increment at a time, only as high as needed to keep you in the lead, up to your maximum.
        </p>
      </form>
    </template>
    <p v-else-if="status === 'UPCOMING'" class="muted">Bidding opens when the auction starts. You can register once it is live.</p>
    <p v-else-if="status === 'ENDED'" class="muted">Bidding is closed.</p>
  </div>
</template>

<style scoped>
.quick {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.quick .btn {
  padding: 6px;
  font-size: 0.85rem;
}
.err {
  color: var(--bad);
  margin: -8px 0 0;
}
</style>
