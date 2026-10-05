<script setup lang="ts">
import type { Category, EquipmentFilter, SortKey } from '~/types/equipment'
import { CATEGORY_LABELS } from '~/types/equipment'

const props = defineProps<{ filter: EquipmentFilter }>()
const emit = defineEmits<{ update: [patch: Partial<EquipmentFilter>]; reset: [] }>()

const categories = Object.entries(CATEGORY_LABELS) as [Category, string][]

// Debounce free-text fields so we don't rewrite the URL on every keystroke.
const search = ref(props.filter.search)
const location = ref(props.filter.location)
let timer: ReturnType<typeof setTimeout> | undefined
watch([search, location], ([s, l]) => {
  clearTimeout(timer)
  timer = setTimeout(() => emit('update', { search: s, location: l }), 250)
})
watch(
  () => props.filter,
  (f) => {
    if (f.search !== search.value) search.value = f.search
    if (f.location !== location.value) location.value = f.location
  },
)

function toggleCategory(c: Category) {
  const set = new Set(props.filter.categories)
  if (set.has(c)) set.delete(c)
  else set.add(c)
  emit('update', { categories: [...set] })
}

function numberOrNull(v: string): number | null {
  const n = Number(v)
  return v === '' || !Number.isFinite(n) || n < 0 ? null : Math.trunc(n)
}

function onMaxPrice(v: string) {
  const dollars = numberOrNull(v)
  emit('update', { maxPriceCents: dollars == null ? null : dollars * 100 })
}
</script>

<template>
  <aside class="card filters" aria-label="Filters">
    <div class="spread">
      <h2>Filters</h2>
      <button class="link-btn" type="button" @click="emit('reset')">Reset</button>
    </div>

    <label class="field">
      Search
      <input v-model="search" type="search" placeholder="e.g. cat excavator" >
    </label>

    <fieldset>
      <legend>Listing</legend>
      <div class="seg" role="radiogroup">
        <button
          v-for="opt in (['ANY', 'SALE', 'RENT'] as const)"
          :key="opt"
          type="button"
          role="radio"
          :aria-checked="filter.listingType === opt"
          @click="emit('update', { listingType: opt })"
        >
          {{ opt === 'ANY' ? 'All' : opt === 'SALE' ? 'Buy' : 'Rent' }}
        </button>
      </div>
    </fieldset>

    <fieldset>
      <legend>Category</legend>
      <label v-for="[value, label] in categories" :key="value" class="check">
        <input type="checkbox" :checked="filter.categories.includes(value)" @change="toggleCategory(value)" >
        {{ label }}
      </label>
    </fieldset>

    <label class="field">
      Max sale price (USD)
      <input
        type="number"
        min="0"
        step="5000"
        inputmode="numeric"
        placeholder="Any"
        :value="filter.maxPriceCents == null ? '' : filter.maxPriceCents / 100"
        @change="onMaxPrice(($event.target as HTMLInputElement).value)"
      >
    </label>

    <div class="pair">
      <label class="field">
        Min year
        <input
          type="number"
          min="1990"
          max="2030"
          placeholder="Any"
          :value="filter.minYear ?? ''"
          @change="emit('update', { minYear: numberOrNull(($event.target as HTMLInputElement).value) })"
        >
      </label>
      <label class="field">
        Max hours
        <input
          type="number"
          min="0"
          step="500"
          placeholder="Any"
          :value="filter.maxHours ?? ''"
          @change="emit('update', { maxHours: numberOrNull(($event.target as HTMLInputElement).value) })"
        >
      </label>
    </div>

    <label class="field">
      Location
      <input v-model="location" type="text" placeholder="City or state, e.g. TX" >
    </label>

    <label class="field">
      Sort by
      <select :value="filter.sort" @change="emit('update', { sort: ($event.target as HTMLSelectElement).value as SortKey })">
        <option value="newest">Newest first</option>
        <option value="price_asc">Price: low to high</option>
        <option value="price_desc">Price: high to low</option>
        <option value="hours_asc">Fewest hours</option>
      </select>
    </label>
  </aside>
</template>

<style scoped>
.filters {
  display: flex;
  flex-direction: column;
  gap: 14px;
  position: sticky;
  top: 76px;
}
h2 {
  margin: 0;
  font-size: 1.05rem;
}
fieldset {
  border: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
legend {
  font-size: 0.85rem;
  color: var(--text-2);
  font-weight: 500;
  margin-bottom: 4px;
}
.check {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 0.9rem;
  cursor: pointer;
}
.pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.seg {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.seg button {
  font: inherit;
  font-size: 0.875rem;
  border: none;
  background: var(--surface);
  color: var(--text-2);
  padding: 8px;
  cursor: pointer;
}
.seg button + button {
  border-left: 1px solid var(--border);
}
.seg button[aria-checked='true'] {
  background: var(--text);
  color: var(--surface);
  font-weight: 600;
}
.link-btn {
  font: inherit;
  font-size: 0.85rem;
  border: none;
  background: none;
  color: var(--accent);
  cursor: pointer;
}
@media (max-width: 900px) {
  .filters {
    position: static;
  }
}
</style>
