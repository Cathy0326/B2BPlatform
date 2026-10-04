import type { Auction, BidRejection } from '~/types/auction'
import type { DepositHold } from '~/services/auctionApi'
import { NotRegisteredError } from '~/services/auctionApi'
import { auctionStatus, minimumNextBidCents, reserveMet, settleResult } from '~/utils/auction'
import { formatCents } from '~/utils/money'

const REJECTION_TEXT: Record<BidRejection, string> = {
  NOT_STARTED: 'This auction has not started yet.',
  ENDED: 'This auction has ended.',
  TOO_LOW: 'Your maximum is below the minimum next bid.',
  NOT_ABOVE_OWN_MAX: 'You are already leading. A new maximum must be higher than your current one.',
}

/**
 * Everything the auction room needs.
 * Flow: load (client-only) -> subscribe to live updates -> register (deposit hold) -> place proxy bids.
 */
export function useAuctionRoom(auctionId: string) {
  const api = useAuctionApi()
  const equipmentApi = useEquipmentApi()
  const user = useCurrentUser()
  const now = useNow()

  // server:false -> auction state is live and clock-dependent, so render it only in the browser.
  const { data, status: loadStatus } = useAsyncData(
    `auction-${auctionId}`,
    async () => {
      const auction = await api.get(auctionId, user.value.id)
      if (!auction) return null
      const [equipment, hold] = await Promise.all([
        equipmentApi.get(auction.equipmentId),
        api.getHold(auctionId, user.value.id),
      ])
      return { auction, equipment, hold }
    },
    { server: false },
  )

  const auction = computed<Auction | null>(() => data.value?.auction ?? null)
  const equipment = computed(() => data.value?.equipment ?? null)
  const hold = ref<DepositHold | null>(null)
  watch(data, (d) => (hold.value = d?.hold ?? null), { immediate: true })

  let unsubscribe: (() => void) | null = null
  const flash = ref(false)
  watch(
    auction,
    (a) => {
      if (!a || unsubscribe) return
      unsubscribe = api.subscribe(auctionId, user.value.id, (next) => {
        if (data.value) data.value = { ...data.value, auction: next }
        flash.value = true
        setTimeout(() => (flash.value = false), 600)
      })
    },
    { immediate: true },
  )
  onBeforeUnmount(() => unsubscribe?.())

  const status = computed(() => (auction.value && now.value ? auctionStatus(auction.value, now.value) : null))
  const msLeft = computed(() => (auction.value ? Math.max(0, auction.value.endsAt - now.value) : 0))
  const msToStart = computed(() => (auction.value ? Math.max(0, auction.value.startsAt - now.value) : 0))
  const minimumNext = computed(() => (auction.value ? minimumNextBidCents(auction.value) : 0))
  const isLeader = computed(() => auction.value?.leaderId === user.value.id)
  const hasReserve = computed(() => auction.value?.server?.hasReserve ?? auction.value?.reservePriceCents != null)
  const isReserveMet = computed(() => (auction.value ? reserveMet(auction.value) : false))
  const result = computed(() => (auction.value && now.value ? settleResult(auction.value, now.value) : null))
  const myMax = computed(() => (isLeader.value ? auction.value?.leaderMaxCents ?? null : null))

  const busy = ref(false)
  const message = ref<{ kind: 'success' | 'error' | 'warn'; text: string } | null>(null)

  function setHold(h: DepositHold | null) {
    hold.value = h
    // Keep the cached payload in sync, otherwise the next live update would reset `hold` to null.
    if (data.value) data.value = { ...data.value, hold: h }
  }

  function describeHold(h: DepositHold) {
    if (h.status === 'HELD') return { kind: 'success' as const, text: `Deposit hold of ${formatCents(h.amountCents)} authorized. You can bid now.` }
    return { kind: 'warn' as const, text: 'Your bank requires extra authentication (3-D Secure) before the hold is active.' }
  }

  /** paymentMethodId: Stripe PaymentMethod id or a test token; ignored by the in-browser mock. */
  async function register(paymentMethodId?: string | null) {
    busy.value = true
    message.value = null
    try {
      const h = await api.registerToBid(auctionId, user.value.id, paymentMethodId)
      setHold(h)
      message.value = describeHold(h)
    } catch (e) {
      // e.g. PAYMENT_DECLINED: the server already removed the registration, so the user can try another card.
      message.value = { kind: 'error', text: (e as Error).message || 'The deposit could not be authorized.' }
    } finally {
      busy.value = false
    }
  }

  async function confirmRegistration() {
    if (!api.confirmRegistration) return
    busy.value = true
    try {
      const h = await api.confirmRegistration(auctionId)
      if (h) {
        setHold(h)
        message.value = describeHold(h)
      }
    } catch (e) {
      message.value = { kind: 'error', text: (e as Error).message }
    } finally {
      busy.value = false
    }
  }

  async function bid(maxCents: number) {
    busy.value = true
    message.value = null
    try {
      const r = await api.placeBid(auctionId, user.value.id, maxCents)
      if (!r.ok) {
        const extra = r.minimumCents ? ` Minimum: ${formatCents(r.minimumCents)}.` : ''
        message.value = { kind: 'error', text: REJECTION_TEXT[r.reason] + extra }
        return false
      }
      if (data.value) data.value = { ...data.value, auction: r.auction }
      message.value = r.leading
        ? { kind: 'success', text: `You're the high bidder at ${formatCents(r.auction.currentPriceCents)}.${r.extended ? ' Auction extended 2 minutes (soft close).' : ''}` }
        : { kind: 'warn', text: `Outbid immediately: another bidder's maximum is higher. Current price ${formatCents(r.auction.currentPriceCents)}.` }
      return true
    } catch (e) {
      message.value = { kind: 'error', text: e instanceof NotRegisteredError ? e.message : 'Bid failed. Please retry.' }
      return false
    } finally {
      busy.value = false
    }
  }

  const iWon = computed(() => !!result.value?.sold && result.value.winnerId === user.value.id)

  return {
    user, now, auction, equipment, hold, loadStatus, status, msLeft, msToStart, minimumNext, isLeader,
    hasReserve, isReserveMet, result, myMax, busy, message, flash, register, confirmRegistration, bid, iWon,
  }
}
