import type { Booking, Equipment } from '~/types/equipment'
import { BookingConflictError } from '~/services/equipmentApi'
import { addDays, daysBetween, findConflicts, mergeBookings, nextAvailableStart, todayIso } from '~/utils/dateRange'
import { quoteRental, type RentalQuote } from '~/utils/rentalPricing'

/**
 * Rental panel state for one machine.
 *   dates -> rentalDays -> (conflicts?, cheapest quote via DP) -> UI
 * Everything below `start`/`end` is derived (computed), so there is a single source of truth.
 */
export function useRentalQuote(equipment: Ref<Equipment | null | undefined>) {
  const api = useEquipmentApi()

  const start = ref(addDays(todayIso(), 1))
  const end = ref(addDays(todayIso(), 8))
  const submitting = ref(false)
  const message = ref<{ kind: 'success' | 'error'; text: string } | null>(null)

  const bookings = computed<Booking[]>(() => equipment.value?.bookings ?? [])
  const bookedBlocks = computed(() => mergeBookings(bookings.value))

  const rentalDays = computed(() => {
    try {
      return daysBetween(start.value, end.value)
    } catch {
      return 0
    }
  })

  const dateError = computed(() => {
    if (rentalDays.value <= 0) return 'Return date must be after the pick-up date.'
    if (start.value < todayIso()) return 'Pick-up date cannot be in the past.'
    if (rentalDays.value > 365) return 'Rentals longer than a year need a custom quote.'
    return null
  })

  const conflicts = computed(() =>
    dateError.value ? [] : findConflicts({ start: start.value, end: end.value }, bookings.value),
  )

  const quote = computed<RentalQuote | null>(() => {
    const rates = equipment.value?.rentalRates
    if (!rates || dateError.value) return null
    return quoteRental(rentalDays.value, rates)
  })

  const suggestedStart = computed(() =>
    conflicts.value.length ? nextAvailableStart(start.value, rentalDays.value, bookings.value) : null,
  )

  function applySuggestion() {
    if (!suggestedStart.value) return
    const days = rentalDays.value
    start.value = suggestedStart.value
    end.value = addDays(suggestedStart.value, days)
  }

  async function requestBooking() {
    if (!equipment.value || dateError.value || conflicts.value.length) return
    submitting.value = true
    message.value = null
    try {
      const booking = await api.createBooking(equipment.value.id, { start: start.value, end: end.value })
      // Optimistic local update so the "booked" list refreshes instantly.
      equipment.value.bookings = [...equipment.value.bookings, booking]
      message.value = { kind: 'success', text: `Request sent for ${booking.start} → ${booking.end}.` }
    } catch (e) {
      const text = e instanceof BookingConflictError ? e.message : 'Something went wrong. Please try again.'
      message.value = { kind: 'error', text }
    } finally {
      submitting.value = false
    }
  }

  return {
    start,
    end,
    rentalDays,
    dateError,
    conflicts,
    bookedBlocks,
    quote,
    suggestedStart,
    applySuggestion,
    requestBooking,
    submitting,
    message,
  }
}
