<script setup lang="ts">
useSeoMeta({
  title: 'How escrow works',
  description: 'How QuipMarket holds deposits, settles auctions, and records every dollar in a double-entry ledger.',
})

const steps = [
  { n: 1, title: 'Register & hold deposit', body: 'Bidder authorizes a refundable deposit (card pre-authorization). Money is reserved, not taken.', tag: 'Stripe: manual capture' },
  { n: 2, title: 'Bid', body: 'Proxy bids compete on price, then time. A late bid extends the auction 2 minutes.', tag: 'Row lock per auction' },
  { n: 3, title: 'Hammer & collect', body: 'Winner\'s deposit is captured and the balance charged. Funds go into escrow, not to the seller. Losing holds are released.', tag: 'Idempotent payment' },
  { n: 4, title: 'Inspect & accept', body: 'Buyer confirms delivery and condition within 48 hours, or opens a dispute.', tag: 'State machine' },
  { n: 5, title: 'Settle & pay out', body: 'Escrow releases to the seller minus the platform fee. Every movement is a balanced ledger entry.', tag: 'Double-entry ledger' },
]

// Example: $105,000 hammer price, 5% seller fee.
const entries = [
  {
    when: '③ Hammer: buyer pays $105,000 into escrow',
    lines: [
      { account: 'platform_cash (asset)', dr: 10_500_000, cr: 0 },
      { account: 'escrow_buyer_funds (liability)', dr: 0, cr: 10_500_000 },
    ],
  },
  {
    when: '⑤ Settle: escrow released, 5% platform fee',
    lines: [
      { account: 'escrow_buyer_funds (liability)', dr: 10_500_000, cr: 0 },
      { account: 'seller_payable (liability)', dr: 0, cr: 9_975_000 },
      { account: 'platform_revenue (income)', dr: 0, cr: 525_000 },
    ],
  },
  {
    when: '⑤ Payout: seller receives $99,750',
    lines: [
      { account: 'seller_payable (liability)', dr: 9_975_000, cr: 0 },
      { account: 'platform_cash (asset)', dr: 0, cr: 9_975_000 },
    ],
  },
]
const total = (lines: { dr: number; cr: number }[], k: 'dr' | 'cr') => lines.reduce((s, l) => s + l[k], 0)
</script>

<template>
  <div class="stack">
    <div>
      <h1>How escrow works</h1>
      <p class="muted">Sellers get paid only after buyers accept the machine. Buyers' money never sits with a stranger.</p>
    </div>

    <ol class="flow">
      <li v-for="s in steps" :key="s.n" class="card">
        <span class="step">{{ s.n }}</span>
        <h3>{{ s.title }}</h3>
        <p class="muted">{{ s.body }}</p>
        <span class="badge">{{ s.tag }}</span>
      </li>
    </ol>

    <section class="card stack">
      <h2>Every dollar is a balanced ledger entry</h2>
      <p class="muted">
        No account has an editable <code>balance</code> column. Balances are the sum of append-only journal lines,
        and every entry's debits equal its credits. Here is a $105,000 auction, settled:
      </p>
      <div v-for="e in entries" :key="e.when" class="table-wrap entry" tabindex="0" role="region" :aria-label="`Journal entry: ${e.when}`">
        <table>
          <caption>{{ e.when }}</caption>
          <thead>
            <tr><th>Account</th><th class="r">Debit</th><th class="r">Credit</th></tr>
          </thead>
          <tbody>
            <tr v-for="l in e.lines" :key="l.account">
              <td>{{ l.account }}</td>
              <td class="r num">{{ l.dr ? formatCents(l.dr) : '' }}</td>
              <td class="r num">{{ l.cr ? formatCents(l.cr) : '' }}</td>
            </tr>
            <tr class="sum">
              <td>Total <span class="badge badge-good">balanced</span></td>
              <td class="r num">{{ formatCents(total(e.lines, 'dr')) }}</td>
              <td class="r num">{{ formatCents(total(e.lines, 'cr')) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="subtle">Losing bidders: their deposit holds are released; nothing was captured, so nothing hits the ledger.</p>
    </section>
  </div>
</template>

<style scoped>
.flow {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  counter-reset: none;
}
.flow li {
  display: flex;
  flex-direction: column;
  gap: 6px;
  position: relative;
}
.step {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--brand);
  color: var(--brand-ink);
  display: grid;
  place-items: center;
  font-weight: 700;
}
.flow .badge {
  align-self: flex-start;
  margin-top: auto;
}
caption {
  text-align: left;
  font-weight: 600;
  padding: 8px 0;
}
.entry + .entry {
  margin-top: 8px;
}
.sum td {
  font-weight: 700;
  border-bottom: none;
}
code {
  font-family: var(--mono);
  font-size: 0.85em;
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
}
</style>
