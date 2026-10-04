import { sha256Hex } from '~/utils/auditChain'

/**
 * Runs once in the browser (auth0 mode only):
 *   1. if Auth0 redirected back with ?code=...&state=..., finish the login
 *   2. if logged in, ask OUR backend who we are (`me`): public pseudonymous id + roles
 *      Demo deployment without a backend (mock data): derive a pseudonymous id from the Auth0 subject in the
 *      browser, the same idea as the backend (never expose the email), and grant the demo admin role so the
 *      ledger page works. Safe only because every "record" lives in this visitor's own browser.
 */
export default defineNuxtPlugin(async () => {
  const clientPromise = getAuth0Client()
  if (!clientPromise) return
  const client = await clientPromise
  const route = useRoute()

  if (route.query.code && route.query.state) {
    try {
      const { appState } = await client.handleRedirectCallback()
      await navigateTo((appState?.returnTo as string) || '/', { replace: true })
    } catch (e) {
      console.warn('Auth0 callback failed', e)
    }
  }

  if (!(await client.isAuthenticated())) return
  const profile = await client.getUser()
  const gql = useGraphQlClient()
  const me = gql
    ? (await gql.request<{ me: { id: string; roles: string[] } | null }>('{ me { id roles } }')).me
    : profile?.sub
      ? { id: `u-${(await sha256Hex(profile.sub)).slice(0, 12)}`, roles: ['admin'] }
      : null
  useCurrentUser().value = {
    id: me?.id ?? '',
    displayName: profile?.name ?? profile?.email ?? 'Signed in',
    roles: me?.roles ?? [],
    authenticated: !!me,
  }
})
