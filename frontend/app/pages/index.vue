<script setup lang="ts">
useSeoMeta({
  title: 'Heavy equipment for sale, rent & auction',
  description: 'Browse excavators, dozers, loaders and cranes. Buy, rent by the day, or bid in live auctions with escrow protection.',
})

const { items, all, filter, updateFilter, resetFilter, status } = useEquipmentCatalog()
const { auctionByEquipment } = useAuctionList()
</script>

<template>
  <div class="stack">
    <section class="hero card">
      <div>
        <p class="eyebrow">B2B heavy-equipment marketplace</p>
        <h1>Buy, rent, or bid on heavy equipment, with funds held in escrow.</h1>
        <p class="muted">
          Every payment flows through an auditable double-entry ledger. Deposits are held, never taken, until a deal closes.
        </p>
        <div class="row">
          <NuxtLink to="/auctions" class="btn btn-primary">Browse live auctions</NuxtLink>
          <NuxtLink to="/escrow" class="btn">How escrow works</NuxtLink>
        </div>
      </div>
    </section>

    <div class="catalog">
      <CatalogFilters :filter="filter" @update="updateFilter" @reset="resetFilter" />
      <section aria-live="polite">
        <div class="spread results-head">
          <h2>{{ items.length }} of {{ all.length }} machines</h2>
        </div>
        <p v-if="status === 'pending'" class="muted">Loading inventory…</p>
        <div v-else-if="items.length" class="grid-cards">
          <EquipmentCard v-for="e in items" :key="e.id" :item="e" :auction-id="auctionByEquipment.get(e.id)" />
        </div>
        <div v-else class="card empty">
          <p>No machines match these filters.</p>
          <button class="btn" @click="resetFilter">Clear filters</button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.hero {
  background: linear-gradient(120deg, #111827 0%, #1f2937 60%, #3b2f00 100%);
  color: #fff;
  border: none;
  padding: 32px 24px;
}
.hero .muted {
  color: #cbd5e1;
  max-width: 640px;
}
.hero .eyebrow {
  color: var(--brand);
}
.hero h1 {
  max-width: 720px;
}
.hero .btn:not(.btn-primary) {
  background: transparent;
  color: #fff;
  border-color: rgb(255 255 255 / 0.3);
}
.catalog {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
}
@media (max-width: 900px) {
  .catalog {
    grid-template-columns: minmax(0, 1fr);
  }
}
.results-head {
  margin-bottom: 12px;
}
.results-head h2 {
  margin: 0;
  font-size: 1rem;
  color: var(--text-2);
}
.empty {
  text-align: center;
  padding: 40px 16px;
}
</style>
