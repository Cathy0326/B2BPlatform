<script setup lang="ts">
import type { Category } from '~/types/equipment'

// Simple vector silhouettes per category, so the demo needs no external images.
defineProps<{ category: Category; compact?: boolean }>()
</script>

<template>
  <div class="art" :class="{ compact }" aria-hidden="true">
    <svg viewBox="0 0 200 120" fill="none" stroke-linecap="round" stroke-linejoin="round">
      <line x1="10" y1="104" x2="190" y2="104" class="ground" />
      <g v-if="category === 'EXCAVATOR'">
        <rect x="40" y="86" width="90" height="16" rx="8" class="dark" />
        <rect x="52" y="58" width="62" height="28" rx="4" class="body" />
        <rect x="56" y="40" width="26" height="22" rx="3" class="body" />
        <rect x="60" y="44" width="18" height="12" rx="2" class="glass" />
        <path d="M110 62 L148 26 L176 52" class="arm" />
        <path d="M176 52 L182 72 L164 72 Z" class="dark-fill" />
      </g>
      <g v-else-if="category === 'BULLDOZER'">
        <rect x="46" y="84" width="96" height="18" rx="9" class="dark" />
        <rect x="56" y="60" width="74" height="26" rx="4" class="body" />
        <rect x="64" y="38" width="32" height="24" rx="3" class="body" />
        <rect x="69" y="42" width="22" height="14" rx="2" class="glass" />
        <path d="M130 70 L156 76" class="arm" />
        <path d="M156 56 Q168 80 156 102 L164 102 Q176 80 164 56 Z" class="dark-fill" />
      </g>
      <g v-else-if="category === 'WHEEL_LOADER'">
        <circle cx="66" cy="88" r="15" class="dark-fill" />
        <circle cx="126" cy="88" r="15" class="dark-fill" />
        <rect x="46" y="58" width="96" height="22" rx="4" class="body" />
        <rect x="56" y="34" width="30" height="26" rx="3" class="body" />
        <rect x="61" y="38" width="20" height="14" rx="2" class="glass" />
        <path d="M138 66 L162 58" class="arm" />
        <path d="M160 46 L182 50 L178 78 L156 74 Z" class="dark-fill" />
      </g>
      <g v-else-if="category === 'SKID_STEER'">
        <circle cx="78" cy="92" r="11" class="dark-fill" />
        <circle cx="112" cy="92" r="11" class="dark-fill" />
        <rect x="66" y="50" width="58" height="36" rx="5" class="body" />
        <rect x="74" y="56" width="28" height="22" rx="2" class="glass" />
        <path d="M120 56 L144 74" class="arm" />
        <path d="M140 66 L162 70 L160 92 L138 90 Z" class="dark-fill" />
      </g>
      <g v-else-if="category === 'CRANE'">
        <circle cx="52" cy="94" r="10" class="dark-fill" />
        <circle cx="80" cy="94" r="10" class="dark-fill" />
        <circle cx="124" cy="94" r="10" class="dark-fill" />
        <rect x="34" y="72" width="110" height="16" rx="3" class="body" />
        <rect x="44" y="52" width="26" height="20" rx="3" class="body" />
        <rect x="48" y="56" width="18" height="10" rx="2" class="glass" />
        <path d="M70 64 L176 14" class="arm" />
        <line x1="176" y1="14" x2="176" y2="56" class="cable" />
        <path d="M172 56 L180 56 L176 64 Z" class="dark-fill" />
      </g>
      <g v-else>
        <circle cx="70" cy="88" r="15" class="dark-fill" />
        <circle cx="130" cy="94" r="9" class="dark-fill" />
        <rect x="52" y="62" width="92" height="20" rx="4" class="body" />
        <rect x="74" y="36" width="30" height="28" rx="3" class="body" />
        <rect x="79" y="40" width="20" height="14" rx="2" class="glass" />
        <path d="M144 70 L166 66" class="arm" />
        <path d="M164 56 L184 60 L180 82 L160 78 Z" class="dark-fill" />
        <path d="M52 68 L26 36 L16 64" class="arm" />
        <path d="M16 64 L10 80 L24 78 Z" class="dark-fill" />
      </g>
    </svg>
  </div>
</template>

<style scoped>
.art {
  background: linear-gradient(160deg, var(--surface-2), var(--bg));
  aspect-ratio: 16 / 9;
  display: grid;
  place-items: center;
}
.art svg {
  width: 78%;
  height: auto;
}
.compact svg {
  width: 90%;
}
.ground {
  stroke: var(--border);
  stroke-width: 2;
}
.body {
  fill: var(--brand);
  stroke: #8a6100;
  stroke-width: 1.5;
}
.glass {
  fill: #cfe3f7;
  stroke: #8a6100;
  stroke-width: 1;
}
.dark {
  fill: #2b2b2b;
  stroke: #111;
  stroke-width: 2;
}
.dark-fill {
  fill: #2b2b2b;
}
.arm {
  stroke: #c88b00;
  stroke-width: 7;
}
.cable {
  stroke: #555;
  stroke-width: 1.5;
}
</style>
