<script setup lang="ts">
useSeoMeta({ title: 'Ledger & audit trail' })

const isAdmin = useIsAdmin()
const api = useEscrowApi()
const demo = useDataSourceLabel() === 'mock'
// The API also enforces this (FORBIDDEN): hiding the page is UX, the server check is security.
const { data, status, refresh } = useAsyncData('ledger', async () => (isAdmin.value ? api.ledgerOverview() : null), {
  server: false,
  watch: [isAdmin],
})

const typeOrder = ['ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE']
const accounts = computed(() =>
  [...(data.value?.trialBalance.accounts ?? [])].sort((a, b) => typeOrder.indexOf(a.type) - typeOrder.indexOf(b.type) || a.code.localeCompare(b.code)),
)
const short = (h: string) => `${h.slice(0, 10)}…`
</script>

<template>
  <div class="stack">
    <div class="spread">
      <div>
        <h1>Ledger &amp; audit trail</h1>
        <p class="muted">Every money movement is a balanced, append-only journal entry. Every event is hash-chained.</p>
      </div>
      <button class="btn" @click="refresh()">Refresh</button>
    </div>

    <p v-if="demo" class="alert demo-note" role="note">
      <strong>Demo mode:</strong> this ledger and audit chain are computed in your browser from the demo deals
      (try paying a balance on <NuxtLink to="/deals">My deals</NuxtLink>, then refresh). In the full stack they live in PostgreSQL,
      where triggers reject unbalanced or edited entries.
    </p>

    <div v-if="!isAdmin" class="card">
      <p><strong>Administrator role required.</strong> The ledger shows every customer's money.</p>
    </div>
    <p v-else-if="(status === 'idle' || status === 'pending') && !data" class="muted">Loading ledger…</p>

    <template v-if="data">
      <div class="tiles">
        <div class="card tile">
          <span class="eyebrow">Trial balance</span>
          <span class="big-number">{{ data.trialBalance.balanced ? 'Balanced' : 'OUT OF BALANCE' }}</span>
          <span class="subtle num">Σ debits {{ formatCents(data.trialBalance.totalDebitsCents) }} = Σ credits {{ formatCents(data.trialBalance.totalCreditsCents) }}</span>
        </div>
        <div class="card tile">
          <span class="eyebrow">Audit hash chain</span>
          <span class="big-number" :class="data.verifyAuditChain.valid ? 'ok' : 'bad'">
            {{ data.verifyAuditChain.valid ? 'Intact ✓' : `Broken at #${data.verifyAuditChain.firstBrokenSeq}` }}
          </span>
          <span class="subtle">{{ data.verifyAuditChain.entries }} records recomputed with SHA-256{{ data.verifyAuditChain.reason ? ` · ${data.verifyAuditChain.reason}` : '' }}</span>
        </div>
      </div>

      <section class="card stack">
        <h2>Accounts</h2>
        <div class="table-wrap" tabindex="0" role="region" aria-label="Accounts">
          <table>
            <thead>
              <tr><th>Account</th><th>Type</th><th class="r">Debits</th><th class="r">Credits</th><th class="r">Balance</th></tr>
            </thead>
            <tbody>
              <tr v-for="a in accounts" :key="a.code">
                <td><span class="mono">{{ a.code }}</span><div class="subtle">{{ a.name }}</div></td>
                <td><span class="badge">{{ a.type }}</span></td>
                <td class="r num">{{ formatCents(a.debitsCents) }}</td>
                <td class="r num">{{ formatCents(a.creditsCents) }}</td>
                <td class="r num"><strong>{{ formatCents(a.balanceCents) }}</strong></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="subtle">Balances are computed from journal lines on every read. There is no balance column to tamper with.</p>
      </section>

      <section class="card stack">
        <h2>Recent journal entries</h2>
        <JournalTable :entries="data.journalEntries" />
      </section>

      <section class="card stack">
        <h2>Audit trail (latest)</h2>
        <div class="table-wrap" tabindex="0" role="region" aria-label="Audit trail">
          <table>
            <thead><tr><th>#</th><th>Event</th><th>Subject</th><th>Actor</th><th>prev_hash</th><th>hash</th></tr></thead>
            <tbody>
              <tr v-for="r in data.auditLog" :key="r.seq">
                <td class="num">{{ r.seq }}</td>
                <td>{{ r.eventType }}</td>
                <td class="mono">{{ r.subject }}</td>
                <td>{{ r.actor }}</td>
                <td class="mono subtle">{{ short(r.prevHash) }}</td>
                <td class="mono">{{ short(r.hash) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="subtle">Each record's hash covers the previous record's hash, so editing or deleting any row breaks every hash after it.</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}
.tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ok {
  color: var(--good);
}
.bad {
  color: var(--bad);
}
.demo-note {
  margin: 0;
}
.mono {
  font-family: var(--mono);
  font-size: 0.8rem;
}
code {
  font-family: var(--mono);
}
</style>
