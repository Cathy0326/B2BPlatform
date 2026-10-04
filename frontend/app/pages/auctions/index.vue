<script setup lang="ts">
import type { AuctionStatus } from '~/types/auction'
import { auctionStatus } from '~/utils/auction'

useSeoMeta({ title: 'Live equipment auctions', description: 'Bid on used heavy equipment with proxy bidding and escrow-protected deposits.' })

const { rows, status } = useAuctionList()
const now = useNow()

type Row = (typeof rows.value)[number]

const groups = computed(() => {
  const g: Record<AuctionStatus, Row[]> = { LIVE: [], UPCOMING: [], ENDED: [] }
  if (!now.value) return g
  for (const r of rows.value) g[auctionStatus(r.auction, now.value)].push(r)
  g.LIVE.sort((a, b) => a.auction.endsAt - b.auction.endsAt) // ending soonest first
  return g
})
const sections = [
  { key: 'LIVE', title: 'Live now' },
  { key: 'UPCOMING', title: 'Upcoming' },
  { key: 'ENDED', title: 'Recently closed' },
] as const
</script>

<template>
  <div class="stack">
    <div>
      <h1>Auctions</h1>
      <p class="muted">Proxy bidding · 2-minute soft close · refundable deposit holds</p>
    </div>
    <p v-if="status === 'pending' || !now" class="muted">Loading auctions…</p>
    <template v-else>
      <section v-for="s in sections" :key="s.key" class="stack">
        <template v-if="groups[s.key].length">
          <h2>{{ s.title }} <span class="subtle">({{ groups[s.key].length }})</span></h2>
          <div class="grid-cards">
            <AuctionCard v-for="r in groups[s.key]" :key="r.auction.id" :auction="r.auction" :equipment="r.equipment" :now="now" />
          </div>
        </template>
      </section>
    </template>
  </div>
</template>
