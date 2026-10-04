/**
 * Phase 1: a fixed demo buyer. Phase 4 replaces this with the Auth0 user (sub claim).
 */
export function useCurrentUser() {
  return useState('current-user', () => ({ id: 'you', displayName: 'Demo Buyer', role: 'BUYER' as const }))
}

/** Bidders stay anonymous to each other: "b-4821" -> "Bidder 4821". */
export function bidderLabel(bidderId: string, meId: string | null): string {
  if (bidderId === meId) return 'You'
  return `Bidder ${bidderId.replace(/^b-/, '')}`
}
