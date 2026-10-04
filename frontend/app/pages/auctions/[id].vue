<script setup lang="ts">
import { formatCountdown } from '~/utils/auction'

const route = useRoute()
const id = route.params.id as string
const room = useAuctionRoom(id)
const { auction, equipment, status, user } = room
const source = useDataSourceLabel()
const { data: paymentConfig } = usePaymentConfig()

useSeoMeta({ title: () => (equipment.value ? `Auction: ${equipment.value.title}` : 'Auction') })
</script>

<template>
  <div class="stack">
    <NuxtLink to="/auctions" class="subtle">← All auctions</NuxtLink>

    <p v-if="!room.now.value || room.loadStatus.value === 'idle' || room.loadStatus.value === 'pending'" class="muted">Loading auction room…</p>
    <div v-else-if="!auction" class="card">
      <h1>Auction not found</h1>
      <NuxtLink to="/auctions">Back to auctions</NuxtLink>
    </div>

    <div v-else class="two-col">
      <div class="stack">
        <div class="card card-flush">
          <EquipmentArt v-if="equipment" :category="equipment.category" />
        </div>
        <div class="card stack">
          <div class="row">
            <AuctionStatusBadge :status="status" />
            <span v-if="auction.extensions" class="badge badge-warn">Extended ×{{ auction.extensions }}</span>
          </div>
          <h1>{{ equipment?.title }}</h1>
          <p class="muted">
            {{ equipment?.location }} · {{ equipment?.hours.toLocaleString('en-US') }} hrs ·
            <NuxtLink v-if="equipment" :to="`/equipment/${equipment.id}`">Full specs</NuxtLink>
          </p>
          <h2>Bid history</h2>
          <BidHistory :bids="auction.bids" :me-id="user.id" />
        </div>
      </div>

      <aside class="card side" :class="{ flash: room.flash.value }">
        <div class="spread">
          <span class="eyebrow">{{ auction.leaderId ? 'Current bid' : 'Starting bid' }}</span>
          <span v-if="room.isLeader.value" class="badge badge-good">You're winning</span>
        </div>
        <span class="big-number price">{{ formatCents(auction.currentPriceCents) }}</span>

        <div class="facts">
          <div>
            <span class="eyebrow">{{ status === 'UPCOMING' ? 'Starts in' : 'Time left' }}</span>
            <strong class="num" :class="{ urgent: status === 'LIVE' && room.msLeft.value < 120_000 }">
              {{ status === 'UPCOMING' ? formatCountdown(room.msToStart.value) : formatCountdown(room.msLeft.value) }}
            </strong>
          </div>
          <div>
            <span class="eyebrow">Reserve</span>
            <strong v-if="!room.hasReserve.value">No reserve</strong>
            <strong v-else-if="room.isReserveMet.value" class="ok">Met ✓</strong>
            <strong v-else class="warn">Not yet met</strong>
          </div>
          <div>
            <span class="eyebrow">Next min. bid</span>
            <strong class="num">{{ formatCents(room.minimumNext.value) }}</strong>
          </div>
          <div>
            <span class="eyebrow">Deposit</span>
            <strong class="num">{{ formatCents(auction.depositCents) }}</strong>
          </div>
        </div>

        <div v-if="room.result.value" class="alert" :class="room.result.value.sold ? 'alert-success' : 'alert-warn'">
          <template v-if="room.result.value.sold">
            Sold for <strong>{{ formatCents(room.result.value.hammerPriceCents!) }}</strong>
            to {{ bidderLabel(room.result.value.winnerId!, user.id) }}.
            <span v-if="room.iWon.value">
              Your deposit is applied to the price.
              <NuxtLink v-if="source === 'graphql'" to="/deals"><strong>Go to your escrow deal →</strong></NuxtLink>
            </span>
          </template>
          <template v-else>Ended without a sale (reserve not met). All deposit holds are released.</template>
        </div>

        <BidPanel
          :auction="auction"
          :status="status"
          :minimum-next="room.minimumNext.value"
          :is-leader="room.isLeader.value"
          :my-max="room.myMax.value"
          :hold="room.hold.value"
          :busy="room.busy.value"
          :source="source"
          :stripe-key="paymentConfig?.stripePublishableKey ?? null"
          @register="room.register($event)"
          @confirm="room.confirmRegistration()"
          @bid="room.bid($event)"
        />
        <div
          v-if="room.message.value"
          class="alert"
          :class="{ 'alert-success': room.message.value.kind === 'success', 'alert-error': room.message.value.kind === 'error', 'alert-warn': room.message.value.kind === 'warn' }"
          role="status"
        >
          {{ room.message.value.text }}
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 76px;
  transition: box-shadow 0.3s ease;
}
.side.flash {
  box-shadow: 0 0 0 3px var(--brand);
}
.price {
  font-size: 2.3rem;
}
.facts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.facts div {
  display: flex;
  flex-direction: column;
  background: var(--surface-2);
  border-radius: var(--radius-sm);
  padding: 8px 10px;
}
.urgent {
  color: var(--bad);
}
.ok {
  color: var(--good);
}
.warn {
  color: var(--warn);
}
@media (max-width: 900px) {
  .side {
    position: static;
  }
}
</style>
