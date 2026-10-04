<script setup lang="ts">
import type { VisibleBid } from '~/types/auction'

const props = defineProps<{ bids: VisibleBid[]; meId: string | null; limit?: number }>()
const rows = computed(() => [...props.bids].reverse().slice(0, props.limit ?? 12))
const time = (ms: number) => new Date(ms).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', second: '2-digit' })
</script>

<template>
  <div class="table-wrap">
    <table>
      <caption class="sr-only">Bid history, newest first</caption>
      <thead>
        <tr>
          <th>Bidder</th>
          <th class="r">Amount</th>
          <th class="hide-sm">Type</th>
          <th class="r">Time</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(b, i) in rows" :key="`${b.at}-${i}`" :class="{ mine: b.bidderId === meId, top: i === 0 }">
          <td>{{ bidderLabel(b.bidderId, meId) }}</td>
          <td class="r num">{{ formatCents(b.amountCents) }}</td>
          <td class="hide-sm"><span class="subtle">{{ b.auto ? 'Auto (proxy)' : 'Bid' }}</span></td>
          <td class="r num subtle">{{ time(b.at) }}</td>
        </tr>
        <tr v-if="!rows.length">
          <td colspan="4" class="subtle">No bids yet. Be the first.</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
tr.mine td:first-child {
  font-weight: 700;
}
@media (max-width: 480px) {
  .hide-sm {
    display: none;
  }
}
tr.top td {
  background: var(--surface-2);
}
</style>
