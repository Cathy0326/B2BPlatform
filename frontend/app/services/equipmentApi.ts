import type { Booking, Equipment } from '~/types/equipment'
import { MOCK_EQUIPMENT } from '~/data/equipment'
import { findConflicts } from '~/utils/dateRange'

/**
 * The UI talks to this interface only, never to fetch() or mock arrays directly.
 * Phase 1 ships the mock implementation; Phase 2 adds a GraphQL implementation
 * with the same shape, so no page or component has to change.
 * (This is the "Dependency Inversion" idea: depend on an interface, not a concrete source.)
 */
export interface EquipmentApi {
  list(): Promise<Equipment[]>
  get(id: string): Promise<Equipment | null>
  createBooking(equipmentId: string, booking: Booking): Promise<Booking>
}

export class BookingConflictError extends Error {
  constructor(public readonly conflicts: Booking[]) {
    super('Those dates overlap an existing booking.')
  }
}

/** In-memory implementation. Clones data so callers cannot mutate the "database". */
export function createMockEquipmentApi(seed: Equipment[] = MOCK_EQUIPMENT): EquipmentApi {
  const db = structuredClone(seed)
  return {
    async list() {
      return structuredClone(db)
    },
    async get(id) {
      const found = db.find((e) => e.id === id)
      return found ? structuredClone(found) : null
    },
    async createBooking(equipmentId, booking) {
      const item = db.find((e) => e.id === equipmentId)
      if (!item) throw new Error(`Equipment ${equipmentId} not found`)
      const conflicts = findConflicts(booking, item.bookings)
      if (conflicts.length) throw new BookingConflictError(conflicts)
      item.bookings.push({ ...booking })
      return { ...booking }
    },
  }
}
