import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

// Automated WCAG 2.1 A/AA checks (axe-core) on every page, in light and dark mode. Automated checks find roughly a
// third to a half of accessibility problems; keyboard and screen-reader passes are still done by hand.
const pages = ['/', '/equipment/eq-1001', '/auctions', '/auctions/au-2001', '/financing', '/escrow', '/deals', '/ledger']

for (const colorScheme of ['light', 'dark'] as const) {
  test.describe(`${colorScheme} mode`, () => {
    test.use({ colorScheme })
    for (const path of pages) {
      test(`${path} has no WCAG A/AA violations`, async ({ page }) => {
        await page.goto(path)
        await page.waitForLoadState('networkidle')
        const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
        const summary = results.violations.map((v) => `${v.impact} ${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
        expect(summary).toEqual([])
      })
    }
  })
}
