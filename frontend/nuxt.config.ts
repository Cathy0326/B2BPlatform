// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2026-10-01',
  devtools: { enabled: true },
  css: ['~/assets/css/main.css'],
  typescript: { strict: true },

  app: {
    head: {
      htmlAttrs: { lang: 'en' },
      title: 'QuipFinance',
      titleTemplate: '%s · QuipFinance',
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        { name: 'description', content: 'Buy, sell, rent, and finance heavy equipment.' },
        { name: 'theme-color', content: '#111827' },
      ],
      link: [{ rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    },
  },

  runtimeConfig: {
    public: {
      // Empty = use the in-browser mock data (Phase 1).
      // Set NUXT_PUBLIC_GRAPHQL_URL=http://localhost:8080/graphql to use the Spring Boot API (Phase 2).
      graphqlUrl: '',
    },
  },
})
