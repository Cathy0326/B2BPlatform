<script setup lang="ts">
import { CATEGORY_LABELS } from '~/types/equipment'

const route = useRoute()
const id = route.params.id as string
const api = useEquipmentApi()

// SSR: rendered on the server, so search engines see title, price and specs.
const { data: equipment } = await useAsyncData(`equipment-${id}`, () => api.get(id))
if (!equipment.value) {
  throw createError({ statusCode: 404, statusMessage: 'Equipment not found', fatal: true })
}

useSeoMeta({
  title: () => equipment.value?.title ?? 'Equipment',
  description: () => equipment.value?.description ?? '',
})

const { auctionByEquipment } = useAuctionList()
const auctionId = computed(() => auctionByEquipment.value.get(id) ?? null)

type Tab = 'buy' | 'rent'
const tabs = computed(() => {
  const t: { id: Tab; label: string }[] = []
  if (equipment.value?.salePriceCents != null) t.push({ id: 'buy', label: 'Buy & finance' })
  if (equipment.value?.rentalRates) t.push({ id: 'rent', label: 'Rent' })
  return t
})
const tab = ref<Tab>(tabs.value[0]?.id ?? 'buy')
</script>

<template>
  <div v-if="equipment" class="stack">
    <NuxtLink to="/" class="subtle">← Back to equipment</NuxtLink>
    <div class="two-col">
      <div class="stack">
        <div class="card card-flush">
          <EquipmentArt :category="equipment.category" />
        </div>
        <div class="card stack">
          <div class="row">
            <span class="badge">{{ CATEGORY_LABELS[equipment.category] }}</span>
            <span class="badge">{{ equipment.year }}</span>
            <span class="badge">{{ equipment.hours.toLocaleString('en-US') }} hrs</span>
            <span class="badge">{{ equipment.location }}</span>
          </div>
          <h1>{{ equipment.title }}</h1>
          <p class="muted">{{ equipment.description }}</p>
          <h2>Specifications</h2>
          <dl class="specs">
            <template v-for="(v, k) in equipment.specs" :key="k">
              <dt>{{ k }}</dt>
              <dd class="num">{{ v }}</dd>
            </template>
            <dt>Make / model</dt>
            <dd>{{ equipment.make }} {{ equipment.model }}</dd>
          </dl>
        </div>
      </div>

      <aside class="card side">
        <NuxtLink v-if="auctionId" :to="`/auctions/${auctionId}`" class="alert alert-warn auction-link">
          This machine is in an auction. <strong>Go to auction room →</strong>
        </NuxtLink>
        <div v-if="equipment.salePriceCents != null" class="price">
          <span class="eyebrow">Asking price</span>
          <span class="big-number">{{ formatCentsWhole(equipment.salePriceCents) }}</span>
        </div>
        <div v-if="tabs.length > 1" class="tabs" role="tablist">
          <button
            v-for="t in tabs"
            :key="t.id"
            role="tab"
            :aria-selected="tab === t.id"
            @click="tab = t.id"
          >
            {{ t.label }}
          </button>
        </div>
        <LoanCalculator
          v-if="tab === 'buy' && equipment.salePriceCents != null"
          :price-cents="equipment.salePriceCents"
          :monthly-rent-cents="equipment.rentalRates?.monthlyCents ?? null"
          compact
        />
        <RentalQuotePanel v-else :equipment="equipment" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.specs {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 6px 20px;
  margin: 0;
}
dt {
  color: var(--text-3);
}
dd {
  margin: 0;
  font-weight: 500;
}
.side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  position: sticky;
  top: 76px;
}
.price {
  display: flex;
  flex-direction: column;
}
.auction-link {
  display: block;
}
@media (max-width: 900px) {
  .side {
    position: static;
  }
}
</style>
