<script setup lang="ts">
import type { Equipment } from '~/types/equipment'
import { CATEGORY_LABELS } from '~/types/equipment'

const props = defineProps<{ item: Equipment; auctionId?: string | null }>()
const hoursLabel = computed(() => props.item.hours.toLocaleString('en-US'))
</script>

<template>
  <NuxtLink :to="`/equipment/${item.id}`" class="card card-flush eq-card">
    <EquipmentArt :category="item.category" />
    <div class="body">
      <div class="row tags">
        <span class="badge">{{ CATEGORY_LABELS[item.category] }}</span>
        <span v-if="item.listingType !== 'RENT'" class="badge">For sale</span>
        <span v-if="item.listingType !== 'SALE'" class="badge">For rent</span>
        <span v-if="auctionId" class="badge badge-brand">Auction</span>
      </div>
      <h3>{{ item.title }}</h3>
      <p class="subtle">{{ item.location }} · {{ hoursLabel }} hrs</p>
      <div class="prices num">
        <div v-if="item.salePriceCents != null">
          <span class="eyebrow">Price</span>
          <strong>{{ formatCentsWhole(item.salePriceCents) }}</strong>
        </div>
        <div v-if="item.rentalRates">
          <span class="eyebrow">Rent from</span>
          <strong>{{ formatCentsWhole(item.rentalRates.dailyCents) }}<small>/day</small></strong>
        </div>
      </div>
    </div>
  </NuxtLink>
</template>

<style scoped>
.eq-card {
  display: flex;
  flex-direction: column;
  color: inherit;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.eq-card:hover {
  text-decoration: none;
  transform: translateY(-2px);
}
.body {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}
.tags {
  gap: 6px;
}
h3 {
  margin: 4px 0 0;
}
.prices {
  display: flex;
  gap: 20px;
  margin-top: auto;
  padding-top: 8px;
}
.prices div {
  display: flex;
  flex-direction: column;
}
small {
  color: var(--text-3);
  font-weight: 400;
}
</style>
