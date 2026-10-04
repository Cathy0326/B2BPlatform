import { Auth0Client } from '@auth0/auth0-spa-js'

/**
 * Auth0 Single-Page-App login (Authorization Code flow with PKCE, handled by the official SDK).
 * The SPA never sees a password; it receives an ACCESS TOKEN whose audience is our API, and sends it
 * as "Authorization: Bearer ..." to the Spring Boot backend, which validates it.
 *
 * cacheLocation 'localstorage' + refresh tokens: survives page reloads even when browsers block
 * third-party cookies. Trade-off: tokens in localStorage are readable by injected scripts (XSS), so
 * the app must not render untrusted HTML; token lifetimes are kept short in the Auth0 API settings.
 */
let clientPromise: Promise<Auth0Client> | null = null

/*
 * `new Auth0Client()` instead of `createAuth0Client()`: the latter performs a silent session check
 * (a network round-trip to Auth0) BEFORE resolving, and every API call would wait for it, so anonymous
 * visitors would be blocked by Auth0's latency (or by an outage). With the localStorage cache,
 * isAuthenticated() is a local read; Auth0 is contacted only to log in or to refresh a real session.
 */

export function getAuth0Client(): Promise<Auth0Client> | null {
  if (import.meta.server || useAuthMode() !== 'auth0') return null
  const cfg = useRuntimeConfig().public
  clientPromise ??= Promise.resolve(new Auth0Client({
    domain: cfg.auth0Domain as string,
    clientId: cfg.auth0ClientId as string,
    cacheLocation: 'localstorage',
    useRefreshTokens: true,
    authorizationParams: {
      audience: cfg.auth0Audience as string,
      redirect_uri: window.location.origin,
    },
    authorizeTimeoutInSeconds: 10,
  }))
  return clientPromise
}

/** Access token for API calls, or null when not logged in / in demo mode / on the server. */
export async function getAccessToken(): Promise<string | null> {
  const client = getAuth0Client()
  if (!client) return null
  try {
    const c = await client
    return (await c.isAuthenticated()) ? ((await c.getTokenSilently()) ?? null) : null
  } catch {
    return null
  }
}

export function useAuth() {
  const route = useRoute()
  return {
    async login() {
      await (await getAuth0Client())?.loginWithRedirect({ appState: { returnTo: route.fullPath } })
    },
    async logout() {
      await (await getAuth0Client())?.logout({ logoutParams: { returnTo: window.location.origin } })
    },
  }
}
