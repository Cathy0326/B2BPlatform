<script setup lang="ts">
import type { Equipment } from '~/types/equipment'
import { MONTH_DAYS, WEEK_DAYS } from '~/utils/rentalPricing'

const props = defineProps<{ equipment: Equipment }>()
const eq = toRef(props, 'equipment')
const r = useRentalQuote(eq)
const today = todayIso()
const fmtDate = (iso: string) => new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })
</script>

<template>
  <div v-if="equipment.rentalRates" class="stack">
    <div class="rates num">
      <div><span class="eyebrow">Day</span><strong>{{ formatCents(equipment.rentalRates.dailyCents) }}</strong></div>
      <div><span class="eyebrow">Week ({{ WEEK_DAYS }}d)</span><strong>{{ formatCents(equipment.rentalRates.weeklyCents) }}</strong></div>
      <div><span class="eyebrow">Month ({{ MONTH_DAYS }}d)</span><strong>{{ formatCents(equipment.rentalRates.monthlyCents) }}</strong></div>
    </div>

    <div class="dates">
      <label class="field">Pick-up<input v-model="r.start.value" type="date" :min="today" ></label>
      <label class="field">Return<input v-model="r.end.value" type="date" :min="r.start.value" ></label>
    </div>

    <div v-if="r.dateError.value" class="alert alert-error">{{ r.dateError.value }}</div>

    <template v-else-if="r.quote.value">
      <div class="quote">
        <div class="spread">
          <span class="muted">{{ r.rentalDays.value }} day{{ r.rentalDays.value === 1 ? '' : 's' }}</span>
          <span class="big-number">{{ formatCents(r.quote.value.totalCents) }}</span>
        </div>
        <div class="spread subtle">
          <span>Billed as {{ describeBreakdown(r.quote.value.breakdown) }}</span>
          <span v-if="r.quote.value.savingsCents > 0" class="saving">Save {{ formatCents(r.quote.value.savingsCents) }} vs daily</span>
        </div>
      </div>

      <div v-if="r.conflicts.value.length" class="alert alert-warn">
        Those dates overlap an existing booking.
        <button v-if="r.suggestedStart.value" type="button" class="link" @click="r.applySuggestion()">
          Next free {{ r.rentalDays.value }}-day window starts {{ fmtDate(r.suggestedStart.value) }} →
        </button>
      </div>

      <button
        class="btn btn-primary btn-block"
        :disabled="r.submitting.value || r.conflicts.value.length > 0"
        @click="r.requestBooking()"
      >
        Request booking
      </button>
    </template>

    <div v-if="r.message.value" class="alert" :class="r.message.value.kind === 'success' ? 'alert-success' : 'alert-error'">
      {{ r.message.value.text }}
    </div>

    <div>
      <span class="eyebrow">Already booked</span>
      <ul v-if="r.bookedBlocks.value.length" class="booked">
        <li v-for="b in r.bookedBlocks.value" :key="b.start" class="num">{{ fmtDate(b.start) }} → {{ fmtDate(b.end) }}</li>
      </ul>
      <p v-else class="subtle">No bookings. Fully available.</p>
    </div>
  </div>
  <p v-else class="muted">This machine is for sale only.</p>
</template>

<style scoped>
.rates {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.rates div {
  display: flex;
  flex-direction: column;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
}
.dates {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.quote {
  border: 1px dashed var(--border);
  border-radius: var(--radius-sm);
  padding: 12px;
}
.saving {
  color: var(--good);
  font-weight: 600;
}
.booked {
  margin: 4px 0 0;
  padding-left: 18px;
  font-size: 0.9rem;
}
.link {
  font: inherit;
  background: none;
  border: none;
  color: var(--accent);
  cursor: pointer;
  padding: 0;
  margin-top: 4px;
  display: block;
  text-align: left;
}
</style>
