/**
 * Minimal GraphQL client: one POST per operation, plus graphql-ws for subscriptions.
 * (No Apollo/urql: the app needs ~10 operations, and a small client is easy to read and explain.)
 */
import { createClient, type Client } from 'graphql-ws'

export class GraphQlError extends Error {
  constructor(
    message: string,
    /** extensions.code from the server, e.g. BOOKING_CONFLICT, NOT_REGISTERED */
    public readonly code: string | undefined,
  ) {
    super(message)
  }
}

/** One key per user action (click). Re-sending the same key on retry is what makes it safe. */
export function newIdempotencyKey(): string {
  return `idem-${crypto.randomUUID()}`
}

export interface GraphQlConfig {
  httpUrl: string
  wsUrl: string
  /** Phase 2 demo identity, sent as X-User-Id. Phase 4: Auth0 bearer token. */
  userId: () => string | null
}

export function createGraphQlClient(config: GraphQlConfig) {
  async function request<T>(
    query: string,
    variables: Record<string, unknown> = {},
    extraHeaders: Record<string, string> = {},
  ): Promise<T> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json', ...extraHeaders }
    const user = config.userId()
    if (user) headers['X-User-Id'] = user

    const res = await fetch(config.httpUrl, { method: 'POST', headers, body: JSON.stringify({ query, variables }) })
    if (!res.ok) throw new GraphQlError(`HTTP ${res.status}`, 'HTTP_ERROR')
    const body = (await res.json()) as { data?: T; errors?: { message: string; extensions?: { code?: string } }[] }
    if (body.errors?.length) {
      const e = body.errors[0]!
      throw new GraphQlError(e.message, e.extensions?.code)
    }
    return body.data as T
  }

  let ws: Client | null = null
  function subscribe<T>(query: string, variables: Record<string, unknown>, onData: (data: T) => void): () => void {
    ws ??= createClient({
      url: config.wsUrl,
      connectionParams: () => ({ userId: config.userId() }),
      retryAttempts: Infinity,
      shouldRetry: () => true,
    })
    return ws.subscribe<T>({ query, variables }, {
      next: (msg) => msg.data && onData(msg.data as T),
      error: (err) => console.warn('subscription error', err),
      complete: () => {},
    })
  }

  return { request, subscribe }
}

export type GraphQlClient = ReturnType<typeof createGraphQlClient>
