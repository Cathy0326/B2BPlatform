/**
 * Who is using the app.
 *   demo mode  (no NUXT_PUBLIC_AUTH0_DOMAIN): a fixed demo buyer who also has the admin role, so every
 *              page works without an Auth0 tenant.
 *   auth0 mode : anonymous until login; after login the backend's `me` query provides the public
 *              pseudonymous id (never the email) and roles. See plugins/auth0.client.ts.
 * id === '' means anonymous.
 */
export interface CurrentUser {
  id: string
  displayName: string
  roles: string[]
  authenticated: boolean
}

export function useAuthMode(): 'demo' | 'auth0' {
  return useRuntimeConfig().public.auth0Domain ? 'auth0' : 'demo'
}

export function useCurrentUser() {
  const mode = useAuthMode()
  return useState<CurrentUser>('current-user', () =>
    mode === 'auth0'
      ? { id: '', displayName: 'Guest', roles: [], authenticated: false }
      : { id: 'you', displayName: 'Demo buyer', roles: ['admin'], authenticated: true },
  )
}

export function useIsAdmin() {
  const user = useCurrentUser()
  return computed(() => user.value.roles.includes('admin'))
}

/** Bidders stay anonymous to each other: "b-4821" -> "Bidder 4821". */
export function bidderLabel(bidderId: string, meId: string | null): string {
  if (meId && bidderId === meId) return 'You'
  return `Bidder ${bidderId.replace(/^[bu]-/, '')}`
}
