<script setup lang="ts">
import type { JournalEntry } from '~/types/escrow'

defineProps<{ entries: JournalEntry[] }>()
const when = (iso: string) => new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
const sum = (e: JournalEntry, k: 'debitCents' | 'creditCents') => e.lines.reduce((s, l) => s + l[k], 0)
</script>

<template>
  <div class="table-wrap">
    <table>
      <thead>
        <tr>
          <th>Entry</th>
          <th>Account</th>
          <th class="r">Debit</th>
          <th class="r">Credit</th>
        </tr>
      </thead>
      <tbody v-for="e in entries" :key="e.id" class="entry">
        <tr v-for="(l, i) in e.lines" :key="i">
          <td v-if="i === 0" :rowspan="e.lines.length + 1" class="meta">
            <strong>{{ e.kind }}</strong>
            <div class="subtle">{{ e.description }}</div>
            <div class="subtle">#{{ e.id }} · {{ e.reference }} · {{ when(e.createdAt) }}</div>
          </td>
          <td class="mono">{{ l.accountCode }}</td>
          <td class="r num">{{ l.debitCents ? formatCents(l.debitCents) : '' }}</td>
          <td class="r num">{{ l.creditCents ? formatCents(l.creditCents) : '' }}</td>
        </tr>
        <tr class="total">
          <td><span class="badge" :class="sum(e, 'debitCents') === sum(e, 'creditCents') ? 'badge-good' : 'badge-live'">
            {{ sum(e, 'debitCents') === sum(e, 'creditCents') ? 'balanced' : 'UNBALANCED' }}
          </span></td>
          <td class="r num">{{ formatCents(sum(e, 'debitCents')) }}</td>
          <td class="r num">{{ formatCents(sum(e, 'creditCents')) }}</td>
        </tr>
      </tbody>
      <tbody v-if="!entries.length">
        <tr><td colspan="4" class="subtle">No ledger entries yet.</td></tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.meta {
  vertical-align: top;
  white-space: normal;
  min-width: 220px;
  max-width: 320px;
}
.mono {
  font-family: var(--mono);
  font-size: 0.8rem;
}
.entry .total td {
  font-weight: 600;
  border-bottom: 2px solid var(--border);
}
</style>
