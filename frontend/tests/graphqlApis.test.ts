import { describe, expect, it, vi } from 'vitest'
import { NotRegisteredError } from '~/services/auctionApi'
import { BookingConflictError } from '~/services/equipmentApi'
import { createGraphQlAuctionApi } from '~/services/graphql/auctionApi'
import { GraphQlError, type GraphQlClient } from '~/services/graphql/client'
import { createGraphQlEquipmentApi } from '~/services/graphql/equipmentApi'
import { createEscrowApi } from '~/services/graphql/escrowApi'

/** A fake GraphQL client: records every call and answers with whatever the test returns. */
function fakeClient(answer: (query: string, variables: Record<string, unknown>) => unknown) {
  const calls: { query: string; variables: Record<string, unknown>; headers: Record<string, string> }[] = []
  const subscriptions: ((data: unknown) => void)[] = []
  const client = {
    request: vi.fn(async (query: string, variables: Record<string, unknown> = {}, headers: Record<string, string> = {}) => {
      calls.push({ query, variables, headers })
      const result = answer(query, variables)
      if (result instanceof Error) throw result
      return result
    }),
    subscribe: vi.fn((_q: string, _v: Record<string, unknown>, onData: (d: unknown) => void) => {
      subscriptions.push(onData)
      return () => {}
    }),
  } as unknown as GraphQlClient
  return { client, calls, subscriptions }
}

const equipmentDto = {
  id: 'eq-1', title: 'Dozer', category: 'BULLDOZER', make: 'Cat', model: 'D6', year: 2022, hours: 900, location: 'Austin, TX',
  listingType: 'BOTH', salePriceCents: 1, rentalRates: { dailyCents: 1, weeklyCents: 2, monthlyCents: 3 }, description: '',
  specs: [{ name: 'Weight', value: '20 t' }, { name: 'Engine', value: 'C9' }],
  bookings: [{ start: '2026-10-01', end: '2026-10-05', __typename: 'Booking' }],
}

describe('GraphQL equipment API', () => {
  it('turns the ordered spec list into a record and keeps only booking dates', async () => {
    const { client } = fakeClient(() => ({ equipment: [equipmentDto, { ...equipmentDto, id: 'eq-2', bookings: undefined }] }))

    const [first, second] = await createGraphQlEquipmentApi(client).list()

    expect(first!.specs).toEqual({ Weight: '20 t', Engine: 'C9' })
    expect(first!.bookings).toEqual([{ start: '2026-10-01', end: '2026-10-05' }])
    expect(second!.bookings).toEqual([])
  })

  it('gets one machine by id, or null', async () => {
    const { client, calls } = fakeClient((_, v) => ({ equipmentById: v.id === 'eq-1' ? equipmentDto : null }))
    const api = createGraphQlEquipmentApi(client)

    expect((await api.get('eq-1'))!.title).toBe('Dozer')
    expect(await api.get('eq-404')).toBeNull()
    expect(calls[0]!.variables).toEqual({ id: 'eq-1' })
  })

  it('maps the server BOOKING_CONFLICT code to the same error the mock throws', async () => {
    const booking = { start: '2026-10-01', end: '2026-10-02' }
    const conflict = fakeClient(() => new GraphQlError('taken', 'BOOKING_CONFLICT'))
    const other = fakeClient(() => new GraphQlError('boom', 'INTERNAL'))
    const ok = fakeClient(() => ({ createBooking: booking }))

    await expect(createGraphQlEquipmentApi(conflict.client).createBooking('eq-1', booking)).rejects.toBeInstanceOf(BookingConflictError)
    await expect(createGraphQlEquipmentApi(other.client).createBooking('eq-1', booking)).rejects.toThrow('boom')
    expect(await createGraphQlEquipmentApi(ok.client).createBooking('eq-1', booking)).toEqual(booking)
    expect(ok.calls[0]!.variables).toEqual({ input: { equipmentId: 'eq-1', ...booking } })
  })
})

const auctionDto = {
  id: 'au-1', equipment: { id: 'eq-1' }, startingPriceCents: 100, depositCents: 10, hasReserve: true, reserveMet: false,
  startsAt: '2026-10-04T10:00:00Z', endsAt: '2026-10-04T12:00:00Z', softCloseSeconds: 120, currentPriceCents: 300,
  minimumNextBidCents: 400, leaderId: 'u-1', myMaxCents: null, extensions: 1,
  bids: [
    { bidderId: 'u-1', amountCents: 300, auto: false, at: '2026-10-04T11:00:00Z' }, // newest first from the API
    { bidderId: 'u-2', amountCents: 200, auto: true, at: '2026-10-04T10:30:00Z' },
  ],
}
const registrationDto = { auctionId: 'au-1', bidderId: 'u-1', depositCents: 10, status: 'HELD', createdAt: '2026-10-04T10:00:00Z', clientSecret: null }

describe('GraphQL auction API', () => {
  it('maps server auctions to the UI shape: chronological bids, epoch times, reserve kept secret', async () => {
    const { client } = fakeClient(() => ({ auctions: [auctionDto] }))

    const [a] = await createGraphQlAuctionApi(client).list(null)

    expect(a!.equipmentId).toBe('eq-1')
    expect(a!.bids.map((b) => b.bidderId)).toEqual(['u-2', 'u-1'])
    expect(a!.endsAt).toBe(Date.parse('2026-10-04T12:00:00Z'))
    expect(a!.softCloseMs).toBe(120_000)
    expect(a!.reservePriceCents).toBeNull()
    expect(a!.server).toEqual({ hasReserve: true, reserveMet: false, minimumNextBidCents: 400 })
  })

  it('gets an auction and the caller registration, or null', async () => {
    const { client } = fakeClient((q, v) =>
      q.includes('myRegistration') ? { myRegistration: v.a === 'au-1' ? registrationDto : null } : { auction: v.id === 'au-1' ? auctionDto : null })
    const api = createGraphQlAuctionApi(client)

    expect((await api.get('au-1', null))!.id).toBe('au-1')
    expect(await api.get('au-404', null)).toBeNull()
    expect(await api.getHold('au-1', 'u-1')).toMatchObject({ amountCents: 10, status: 'HELD', createdAt: Date.parse('2026-10-04T10:00:00Z') })
    expect(await api.getHold('au-404', 'u-1')).toBeNull()
  })

  it('sends a fresh Idempotency-Key with every deposit registration', async () => {
    const { client, calls } = fakeClient((q) => (q.includes('confirmRegistration') ? { confirmRegistration: registrationDto } : { registerToBid: registrationDto }))
    const api = createGraphQlAuctionApi(client)

    await api.registerToBid('au-1', 'u-1', 'pm_card_visa')
    await api.registerToBid('au-1', 'u-1')
    await api.confirmRegistration!('au-1')

    const keys = calls.slice(0, 2).map((c) => c.headers['Idempotency-Key'])
    expect(keys[0]).toMatch(/^idem-/)
    expect(keys[0]).not.toBe(keys[1])
    expect(calls[1]!.variables.pm).toBeNull()
  })

  it('maps accepted and rejected bids, and NOT_REGISTERED to the mock error', async () => {
    const accepted = fakeClient(() => ({ placeBid: { accepted: true, reason: null, minimumCents: null, leading: true, extended: false, auction: auctionDto } }))
    const rejected = fakeClient(() => ({ placeBid: { accepted: false, reason: 'TOO_LOW', minimumCents: 400, leading: false, extended: false, auction: auctionDto } }))
    const noHold = fakeClient(() => new GraphQlError('register first', 'NOT_REGISTERED'))
    const broken = fakeClient(() => new Error('network'))

    expect(await createGraphQlAuctionApi(accepted.client).placeBid('au-1', 'u-1', 500)).toMatchObject({ ok: true, leading: true, extended: false })
    expect(await createGraphQlAuctionApi(rejected.client).placeBid('au-1', 'u-1', 1)).toEqual({ ok: false, reason: 'TOO_LOW', minimumCents: 400 })
    await expect(createGraphQlAuctionApi(noHold.client).placeBid('au-1', 'u-1', 1)).rejects.toBeInstanceOf(NotRegisteredError)
    await expect(createGraphQlAuctionApi(broken.client).placeBid('au-1', 'u-1', 1)).rejects.toThrow('network')
  })

  it('maps live updates from the subscription', () => {
    const { client, subscriptions } = fakeClient(() => null)
    const seen: string[] = []

    createGraphQlAuctionApi(client).subscribe('au-1', null, (a) => seen.push(a.equipmentId))
    subscriptions[0]!({ auctionUpdated: auctionDto })

    expect(seen).toEqual(['eq-1'])
  })
})

describe('GraphQL escrow API', () => {
  it('calls each operation, with an Idempotency-Key only on the money-moving mutation', async () => {
    const { client, calls } = fakeClient((q) => {
      if (q.includes('paymentConfig')) return { paymentConfig: { gateway: 'SIMULATED', stripePublishableKey: null } }
      if (q.includes('myDeals')) return { myDeals: [] }
      if (q.includes('payBalance')) return { payBalance: { id: 'D-1' } }
      if (q.includes('confirmDelivery')) return { confirmDelivery: { id: 'D-1' } }
      return { trialBalance: { balanced: true } }
    })
    const api = createEscrowApi(client)

    expect((await api.paymentConfig()).gateway).toBe('SIMULATED')
    expect(await api.myDeals()).toEqual([])
    expect((await api.payBalance('D-1', 'pm_card_visa')).id).toBe('D-1')
    expect((await api.confirmDelivery('D-1')).id).toBe('D-1')
    expect((await api.ledgerOverview()).trialBalance.balanced).toBe(true)

    expect(calls[2]!.headers['Idempotency-Key']).toMatch(/^idem-/)
    expect(calls[2]!.variables).toEqual({ d: 'D-1', pm: 'pm_card_visa' })
    expect(calls[3]!.headers['Idempotency-Key']).toBeUndefined()
  })
})
