<script setup lang="ts">
const links = [
  { to: '/', label: 'Equipment' },
  { to: '/auctions', label: 'Auctions' },
  { to: '/financing', label: 'Financing' },
  { to: '/escrow', label: 'Escrow' },
]
const source = useDataSourceLabel()
</script>

<template>
  <div class="shell">
    <header class="site-header">
      <div class="container header-inner">
        <NuxtLink to="/" class="logo" aria-label="QuipMarket home">
          <img src="/favicon.svg" alt="" width="28" height="28" />
          <span>Quip<strong>Market</strong></span>
        </NuxtLink>
        <nav aria-label="Main">
          <NuxtLink v-for="l in links" :key="l.to" :to="l.to" class="nav-link">{{ l.label }}</NuxtLink>
        </nav>
      </div>
    </header>
    <main class="container page">
      <slot />
    </main>
    <footer class="site-footer">
      <div class="container spread">
        <span>QuipMarket · heavy-equipment auctions, rentals &amp; escrow settlement</span>
        <span>
          Data: <strong>{{ source === 'graphql' ? 'live GraphQL API' : 'in-browser demo data' }}</strong>
          · Nuxt 4 · Spring Boot GraphQL · PostgreSQL
        </span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
main {
  flex: 1;
}
.site-header {
  background: #111827;
  color: #fff;
  position: sticky;
  top: 0;
  z-index: 10;
}
.header-inner {
  display: flex;
  align-items: center;
  gap: 24px;
  min-height: 60px;
  flex-wrap: wrap;
}
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-size: 1.1rem;
}
.logo strong {
  color: var(--brand);
}
.logo:hover {
  text-decoration: none;
}
nav {
  display: flex;
  gap: 4px;
  overflow-x: auto;
  flex: 1;
}
.nav-link {
  color: #cbd5e1;
  padding: 6px 10px;
  border-radius: 6px;
  font-weight: 500;
  white-space: nowrap;
}
.nav-link:hover {
  color: #fff;
  text-decoration: none;
  background: rgb(255 255 255 / 0.08);
}
.nav-link.router-link-exact-active,
.nav-link.router-link-active:not([href='/']) {
  color: #fff;
  background: rgb(255 255 255 / 0.12);
}
@media (max-width: 640px) {
  .header-inner {
    gap: 4px;
    padding-top: 8px;
  }
  nav {
    flex-basis: 100%;
    padding-bottom: 8px;
    margin: 0 -10px;
  }
}
.site-footer {
  border-top: 1px solid var(--border);
  padding: 20px 0;
  color: var(--text-3);
  font-size: 0.8rem;
}
</style>
