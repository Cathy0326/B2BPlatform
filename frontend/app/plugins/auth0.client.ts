/**
 * Runs once in the browser (auth0 mode only):
 *   1. if Auth0 redirected back with ?code=...&state=..., finish the login
 *   2. if logged in, ask OUR backend who we are (`me`): public pseudonymous id + roles
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
  const me = gql ? (await gql.request<{ me: { id: string; roles: string[] } | null }>('{ me { id roles } }')).me : null
  useCurrentUser().value = {
    id: me?.id ?? '',
    displayName: profile?.name ?? profile?.email ?? 'Signed in',
    roles: me?.roles ?? [],
    authenticated: !!me,
  }
})
