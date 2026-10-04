<script setup lang="ts">
useSeoMeta({
  title: 'Equipment financing calculator',
  description: 'Estimate monthly payments, total interest and a full amortization schedule for heavy-equipment loans.',
})
</script>

<template>
  <div class="stack">
    <div>
      <h1>Equipment financing calculator</h1>
      <p class="muted">Fixed-rate, fully amortizing loan. All money math runs in integer cents, so totals reconcile to the penny.</p>
    </div>
    <div class="card">
      <LoanCalculator :price-cents="16_450_000" />
    </div>
    <details class="card">
      <summary><strong>How the numbers are calculated</strong></summary>
      <div class="stack how">
        <p><code>M = P · r · (1 + r)^n / ((1 + r)^n − 1)</code>, where <code>r</code> = APR / 12 and <code>n</code> = months. At 0% APR, <code>M = P / n</code>.</p>
        <p>Each month: interest = round(balance × r), principal = payment − interest. Rounding leaves a few cents over the life of the loan, so the final payment is adjusted to land the balance on exactly $0.00, the same thing real lenders do.</p>
        <p>Why integer cents? In floating point, <code>0.1 + 0.2 = 0.30000000000000004</code>. Integers are exact, so no amount ever drifts.</p>
      </div>
    </details>
  </div>
</template>

<style scoped>
summary {
  cursor: pointer;
}
.how {
  margin-top: 12px;
}
code {
  font-family: var(--mono);
  font-size: 0.85em;
  background: var(--surface-2);
  padding: 1px 5px;
  border-radius: 4px;
}
</style>
