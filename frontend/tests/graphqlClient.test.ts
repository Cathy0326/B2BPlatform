import { afterEach, describe, expect, it, vi } from 'vitest'

const wsSubscribe = vi.fn()
const createClient = vi.fn(() => ({ subscribe: wsSubscribe }))
vi.mock('graphql-ws', () => ({ createClient }))

const { createGraphQlClient, GraphQlError, newIdempotencyKey } = await import('~/services/graphql/client')

const config = {
  httpUrl: 'http://api.test/graphql',
  wsUrl: 'ws://api.test/graphql-ws',
  authHeaders: async () => ({ Authorization: 'Bearer token-1' }),
  connectionParams: async () => ({ authToken: 'token-1' }),
}

function respond(status: number, body: unknown = {}) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => vi.unstubAllGlobals())

describe('GraphQL client', () => {
  it('POSTs the query with auth and extra headers and returns data', async () => {
    const fetchMock = respond(200, { data: { me: { id: 'u-1' } } })

    const data = await createGraphQlClient(config).request('{ me { id } }', { x: 1 }, { 'Idempotency-Key': 'idem-1' })

    expect(data).toEqual({ me: { id: 'u-1' } })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://api.test/graphql')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json', Authorization: 'Bearer token-1', 'Idempotency-Key': 'idem-1' })
    expect(JSON.parse(init.body as string)).toEqual({ query: '{ me { id } }', variables: { x: 1 } })
  })

  it.each([
    [429, 'RATE_LIMITED', /slow down/],
    [401, 'UNAUTHENTICATED', /log in again/],
    [500, 'HTTP_ERROR', /HTTP 500/],
  ])('turns HTTP %i into a %s error', async (status, code, message) => {
    respond(status)

    const err = await createGraphQlClient(config).request('{ x }').catch((e) => e)

    expect(err).toBeInstanceOf(GraphQlError)
    expect(err.code).toBe(code)
    expect(err.message).toMatch(message)
  })

  it('surfaces the first GraphQL error with its code, even on HTTP 200', async () => {
    respond(200, { errors: [{ message: 'Dates taken', extensions: { code: 'BOOKING_CONFLICT' } }, { message: 'second' }] })
    const err = await createGraphQlClient(config).request('mutation { x }').catch((e) => e)
    expect(err).toMatchObject({ message: 'Dates taken', code: 'BOOKING_CONFLICT' })

    respond(200, { errors: [{ message: 'no code' }] })
    expect(await createGraphQlClient(config).request('{ x }').catch((e) => e.code)).toBeUndefined()
  })

  it('opens one WebSocket for all subscriptions and forwards only messages with data', () => {
    const gql = createGraphQlClient(config)
    const received: unknown[] = []

    gql.subscribe('subscription { a }', {}, (d) => received.push(d))
    gql.subscribe('subscription { b }', {}, () => {})
    const sink = wsSubscribe.mock.calls[0]![1] as { next: (m: unknown) => void; error: (e: unknown) => void; complete: () => void }
    sink.next({ data: { a: 1 } })
    sink.next({})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    sink.error(new Error('dropped'))
    sink.complete()

    expect(createClient).toHaveBeenCalledTimes(1)
    expect(createClient.mock.calls[0]![0]).toMatchObject({ url: 'ws://api.test/graphql-ws', retryAttempts: Infinity })
    expect(received).toEqual([{ a: 1 }])
  })

  it('creates a different idempotency key every time', () => {
    const keys = new Set(Array.from({ length: 50 }, newIdempotencyKey))
    expect(keys.size).toBe(50)
    expect([...keys][0]).toMatch(/^idem-[0-9a-f-]{36}$/)
  })
})
