import { defineConfig, devices } from '@playwright/test'

// Browser tests against the production build (`npm run build`) in demo-data mode: no backend, so every run sees the
// same catalog, auctions and escrow deals. CI runs them on every push; locally: npm run build && npm run test:e2e
const PORT = 3000

export default defineConfig({
  testDir: 'e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0, // a flaky browser test is a bug to fix, not to retry away
  reporter: [['list'], ['html', { outputFolder: 'reports/playwright', open: 'never' }], ['junit', { outputFile: 'reports/e2e-junit.xml' }]],
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: {
    command: 'node .output/server/index.mjs',
    url: `http://localhost:${PORT}`,
    env: { PORT: String(PORT), NUXT_PUBLIC_GRAPHQL_URL: '' },
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
})
