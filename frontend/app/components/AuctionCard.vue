<script setup lang="ts">
import type { Auction } from '~/types/auction'
import type { Equipment } from '~/types/equipment'
import { auctionStatus, formatCountdown } from '~/utils/auction'

const props = defineProps<{ auction: Auction; equipment: Equipment | null; now: number }>()
const status = computed(() => (props.now ? auctionStatus(props.auction, props.now) : null))
const timeLabel = computed(() => {
  if (!props.now) return ''
  if (status.value === 'UPCOMING') return `Starts in ${formatCountdown(props.auction.startsAt - props.now)}`
  if (status.value === 'LIVE') return `Ends in ${formatCountdown(props.auction.endsAt - props.now)}`
  return 'Closed'
})
const endingSoon = computed(() => status.value === 'LIVE' && props.auction.endsAt - props.now < 10 * 60_000)
</script>

<template>
  <NuxtLink :to="`/auctions/${auction.id}`" class="card card-flush a-card">
    <EquipmentArt v-if="equipment" :category="equipment.category" />
    <div class="body">
      <div class="spread">
        <AuctionStatusBadge :status="status" />
        <span class="subtle num" :class="{ urgent: endingSoon }">{{ timeLabel }}</span>
      </div>
      <h3>{{ equipment?.title ?? 'Loading…' }}</h3>
      <p class="subtle">{{ equipment?.location }}</p>
      <div class="spread num price-row">
        <div>
          <span class="eyebrow">{{ auction.leaderId ? 'Current bid' : 'Starting bid' }}</span>
          <strong>{{ formatCentsWhole(auction.currentPriceCents) }}</strong>
        </div>
        <span class="subtle">{{ auction.bids.length }} bids</span>
      </div>
    </div>
  </NuxtLink>
</template>

<style scoped>
.a-card {
  display: flex;
  flex-direction: column;
  color: inherit;
}
.a-card:hover {
  text-decoration: none;
}
.body {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 1;
}
.price-row {
  margin-top: auto;
  align-items: flex-end;
}
.price-row div {
  display: flex;
  flex-direction: column;
}
.urgent {
  color: var(--bad);
  font-weight: 600;
}
</style>
