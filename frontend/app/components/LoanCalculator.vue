<script setup lang="ts">
const props = defineProps<{ priceCents: number; compact?: boolean; monthlyRentCents?: number | null }>()

const { form, errors, result, yearly } = useLoanCalc({ priceCents: props.priceCents })
watch(
  () => props.priceCents,
  (p) => (form.price = Math.round(p / 100)),
)
const showSchedule = ref(false)
const breakEven = computed(() =>
  result.value && props.monthlyRentCents ? breakEvenMonths(result.value.totalCostCents, props.monthlyRentCents) : null,
)
</script>

<template>
  <div class="loan stack">
    <div class="inputs" :class="{ compact }">
      <label class="field">
        Equipment price
        <div class="input-prefix"><span>$</span><input v-model.number="form.price" type="number" min="0" step="1000" ></div>
      </label>
      <label class="field">
        Down payment ({{ form.downPaymentPercent }}%)
        <input v-model.number="form.downPaymentPercent" type="range" min="0" max="50" step="5" >
      </label>
      <label class="field">
        APR (%)
        <input v-model.number="form.aprPercent" type="number" min="0" max="50" step="0.25" >
      </label>
      <label class="field">
        Term
        <select v-model.number="form.termMonths">
          <option v-for="m in [12, 24, 36, 48, 60, 72, 84]" :key="m" :value="m">{{ m }} months</option>
        </select>
      </label>
    </div>

    <div v-if="errors.length" class="alert alert-error" role="alert">
      <div v-for="e in errors" :key="e">{{ e }}</div>
    </div>

    <template v-if="result">
      <div class="tiles">
        <div class="tile">
          <span class="eyebrow">Monthly payment</span>
          <span class="big-number">{{ formatCents(result.monthlyPaymentCents) }}</span>
        </div>
        <div class="tile">
          <span class="eyebrow">Amount financed</span>
          <span class="num strong">{{ formatCents(result.principalCents) }}</span>
        </div>
        <div class="tile">
          <span class="eyebrow">Total interest</span>
          <span class="num strong">{{ formatCents(result.totalInterestCents) }}</span>
        </div>
        <div class="tile">
          <span class="eyebrow">Total cost</span>
          <span class="num strong">{{ formatCents(result.totalCostCents) }}</span>
        </div>
      </div>

      <p v-if="breakEven" class="alert alert-warn">
        Buy vs. rent: renting this machine monthly costs as much as financing it after
        <strong>{{ breakEven }} months</strong> (before resale value).
      </p>

      <AmortizationChart v-if="!compact" :rows="yearly" />

      <button type="button" class="btn" :aria-expanded="showSchedule" @click="showSchedule = !showSchedule">
        {{ showSchedule ? 'Hide' : 'Show' }} monthly schedule ({{ result.schedule.length }} payments)
      </button>
      <div v-if="showSchedule" class="table-wrap schedule">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th class="r">Payment</th>
              <th class="r">Principal</th>
              <th class="r">Interest</th>
              <th class="r">Balance</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in result.schedule" :key="r.month">
              <td>{{ r.month }}</td>
              <td class="r num">{{ formatCents(r.paymentCents) }}</td>
              <td class="r num">{{ formatCents(r.principalCents) }}</td>
              <td class="r num">{{ formatCents(r.interestCents) }}</td>
              <td class="r num">{{ formatCents(r.balanceCents) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.inputs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.inputs.compact {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
@media (max-width: 700px) {
  .inputs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
input[type='range'] {
  padding: 0;
  accent-color: var(--brand);
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}
.tile {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  padding: 12px;
}
.strong {
  font-weight: 700;
  font-size: 1.1rem;
}
.schedule {
  max-height: 360px;
  overflow-y: auto;
}
</style>
