import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Unit tests cover the pure logic in app/utils, so no Nuxt runtime is needed.
export default defineConfig({
  resolve: {
    alias: {
      '~': fileURLToPath(new URL('./app', import.meta.url)),
    },
  },
  test: {
    include: ['tests/**/*.test.ts'],
    environment: 'node',
    // `npm run test:ci` adds coverage + a JUnit XML report for the CI quality dashboard.
    // Scope = the logic layer the unit tests target; pages and components are checked in the browser instead.
    coverage: {
      provider: 'v8',
      include: ['app/utils/**', 'app/services/**'],
      reporter: ['text-summary', 'json-summary', 'html'],
      reportsDirectory: 'reports/coverage',
    },
  },
})
