<script setup lang="ts">
import type { AuctionStatus } from '~/types/auction'
defineProps<{ status: AuctionStatus | null }>()
</script>

<template>
  <span v-if="status === 'LIVE'" class="badge badge-live">Live</span>
  <span v-else-if="status === 'UPCOMING'" class="badge badge-warn">Upcoming</span>
  <span v-else-if="status === 'ENDED'" class="badge">Ended</span>
  <span v-else class="badge">…</span>
</template>
