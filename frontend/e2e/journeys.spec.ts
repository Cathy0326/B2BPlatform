import { expect, test } from '@playwright/test'
import { MOCK_EQUIPMENT } from '../app/data/equipment'
import { formatCents } from '../app/utils/money'
import { quoteRental } from '../app/utils/rentalPricing'

// The paths a buyer actually takes, clicked through in a real browser against the production build.

const isoDaysFromToday = (days: number) => {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

test('catalog: searching and filtering narrows the list, and a card opens the machine', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText(`${MOCK_EQUIPMENT.length} of ${MOCK_EQUIPMENT.length} machines`)).toBeVisible()

  await page.getByRole('checkbox', { name: 'Excavator' }).check()
  const excavators = MOCK_EQUIPMENT.filter((e) => e.category === 'EXCAVATOR')
  await expect(page.getByText(`${excavators.length} of ${MOCK_EQUIPMENT.length} machines`)).toBeVisible()

  await page.getByRole('link', { name: new RegExp(excavators[0]!.title) }).first().click()
  await expect(page.getByRole('heading', { level: 1, name: excavators[0]!.title })).toBeVisible()
})

test('rental: the quote shown is the cheapest day/week/month mix for the chosen dates', async ({ page }) => {
  const machine = MOCK_EQUIPMENT.find((e) => e.rentalRates && e.salePriceCents == null) ?? MOCK_EQUIPMENT.find((e) => e.rentalRates)!
  await page.goto(`/equipment/${machine.id}`)
  const rentTab = page.getByRole('tab', { name: 'Rent' })
  if (await rentTab.count()) await rentTab.click()

  // Far enough ahead to avoid the demo bookings; 31 days = 1 month + 3 days, or cheaper with a week.
  await page.getByLabel('Pick-up').fill(isoDaysFromToday(200))
  await page.getByLabel('Return').fill(isoDaysFromToday(231))

  const expected = quoteRental(31, machine.rentalRates!)
  await expect(page.getByText('31 days')).toBeVisible()
  await expect(page.getByText(formatCents(expected.totalCents), { exact: true })).toBeVisible()
})

test('auction: register a deposit hold, then a proxy bid takes the lead', async ({ page }) => {
  await page.goto('/auctions')
  await page.locator('a[href^="/auctions/"]').first().click()
  await page.getByRole('button', { name: /register/i }).click()

  const bid = page.getByLabel('Your maximum bid (USD)')
  await expect(bid).toBeVisible()
  await bid.fill('2000000') // far above any demo bot
  await page.getByRole('button', { name: 'Place proxy bid' }).click()
  await expect(page.getByText("You're the high bidder").first()).toBeVisible()
})

test('escrow: paying the balance moves the deal forward and the ledger stays balanced', async ({ page }) => {
  await page.goto('/deals')
  const pay = page.getByRole('button', { name: /^Pay .* into escrow$/ })
  await expect(pay).toBeVisible()
  await pay.click()
  await expect(page.getByRole('button', { name: /confirm delivery/i })).toBeVisible()

  await page.goto('/ledger')
  // Every journal entry carries a badge computed from its own lines: all "balanced", none "UNBALANCED".
  await expect(page.getByText('balanced', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('UNBALANCED', { exact: true })).toHaveCount(0)
})
