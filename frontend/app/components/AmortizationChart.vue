<script setup lang="ts">
/**
 * Stacked bars: principal vs interest paid per loan year.
 * One axis, 2 categorical series (validated palette slots 1-2), legend + hover tooltip,
 * and the full table lives next to it (accessibility: never color-alone).
 */
interface YearRow {
  year: number
  principalCents: number
  interestCents: number
  endBalanceCents: number
}
const props = defineProps<{ rows: YearRow[] }>()

// Draw at the container's real pixel width so text stays 11px at any screen size
// (a fixed viewBox would scale the axis labels up and down with the chart).
const plotEl = ref<HTMLElement | null>(null)
const W = ref(560)
const H = 240
const PAD = { top: 12, right: 8, bottom: 28, left: 56 }
let ro: ResizeObserver | undefined
onMounted(() => {
  if (!plotEl.value) return
  ro = new ResizeObserver(([entry]) => (W.value = Math.max(280, Math.round(entry!.contentRect.width))))
  ro.observe(plotEl.value)
})
onBeforeUnmount(() => ro?.disconnect())
const innerW = computed(() => W.value - PAD.left - PAD.right)
const innerH = H - PAD.top - PAD.bottom

const maxTotal = computed(() => Math.max(1, ...props.rows.map((r) => r.principalCents + r.interestCents)))
const niceMax = computed(() => {
  const raw = maxTotal.value / 100
  const pow = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].find((s) => (s * pow * 4) >= raw)! * pow
  return step * 4 * 100
})
const ticks = computed(() => [0, 1, 2, 3, 4].map((i) => (niceMax.value / 4) * i))
const y = (cents: number) => PAD.top + innerH - (cents / niceMax.value) * innerH
const band = computed(() => innerW.value / Math.max(1, props.rows.length))
const barW = computed(() => Math.min(48, band.value * 0.6))

const hover = ref<number | null>(null)
const tip = computed(() => (hover.value == null ? null : props.rows[hover.value] ?? null))
const short = (cents: number) => {
  const d = cents / 100
  return d >= 1000 ? `$${Math.round(d / 1000)}k` : `$${Math.round(d)}`
}
</script>

<template>
  <figure class="chart">
    <figcaption class="spread">
      <span class="eyebrow">Paid per year</span>
      <span class="legend">
        <span><i class="sw s1" /> Principal</span>
        <span><i class="sw s2" /> Interest</span>
      </span>
    </figcaption>
    <div ref="plotEl" class="plot">
      <svg :viewBox="`0 0 ${W} ${H}`" :width="W" :height="H" role="group" aria-label="Principal and interest paid per year">
        <g v-for="t in ticks" :key="t">
          <line :x1="PAD.left" :x2="W - PAD.right" :y1="y(t)" :y2="y(t)" class="grid" />
          <text :x="PAD.left - 8" :y="y(t) + 4" text-anchor="end" class="axis">{{ short(t) }}</text>
        </g>
        <g v-for="(r, i) in rows" :key="r.year">
          <!-- principal at the baseline, interest stacked on top, 2px surface gap between -->
          <rect
            :x="PAD.left + band * i + (band - barW) / 2"
            :y="y(r.principalCents)"
            :width="barW"
            :height="Math.max(0, y(0) - y(r.principalCents))"
            class="s1-fill"
            rx="2"
          />
          <rect
            :x="PAD.left + band * i + (band - barW) / 2"
            :y="y(r.principalCents + r.interestCents)"
            :width="barW"
            :height="Math.max(0, y(r.principalCents) - y(r.principalCents + r.interestCents) - 2)"
            class="s2-fill"
            rx="4"
          />
          <text :x="PAD.left + band * i + band / 2" :y="H - 8" text-anchor="middle" class="axis">Y{{ r.year }}</text>
          <!-- hit target larger than the mark -->
          <rect
            :x="PAD.left + band * i"
            :y="PAD.top"
            :width="band"
            :height="innerH"
            fill="transparent"
            tabindex="0"
            role="img"
            :aria-label="`Year ${r.year}: principal ${formatCents(r.principalCents)}, interest ${formatCents(r.interestCents)}`"
            @mouseenter="hover = i"
            @mouseleave="hover = null"
            @focus="hover = i"
            @blur="hover = null"
          />
        </g>
      </svg>
      <div v-if="tip" class="tooltip num" role="status">
        <strong>Year {{ tip.year }}</strong>
        <div><i class="sw s1" /> Principal {{ formatCents(tip.principalCents) }}</div>
        <div><i class="sw s2" /> Interest {{ formatCents(tip.interestCents) }}</div>
        <div class="subtle">Balance after {{ formatCents(tip.endBalanceCents) }}</div>
      </div>
    </div>
  </figure>
</template>

<style scoped>
.chart {
  margin: 0;
}
.plot {
  position: relative;
}
svg {
  display: block;
  max-width: 100%;
}
.grid {
  stroke: var(--border);
  stroke-width: 1;
}
.axis {
  fill: var(--text-3);
  font-size: 11px;
  font-family: var(--font);
}
.s1-fill {
  fill: var(--series-1);
}
.s2-fill {
  fill: var(--series-2);
}
.legend {
  display: flex;
  gap: 14px;
  font-size: 0.8rem;
  color: var(--text-2);
}
.sw {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
  vertical-align: -1px;
}
.sw.s1 {
  background: var(--series-1);
}
.sw.s2 {
  background: var(--series-2);
}
.tooltip {
  position: absolute;
  top: 8px;
  right: 8px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  padding: 8px 10px;
  font-size: 0.8rem;
  color: var(--text);
  pointer-events: none;
}
</style>
