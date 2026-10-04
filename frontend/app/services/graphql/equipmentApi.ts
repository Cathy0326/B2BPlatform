import type { Booking, Equipment } from '~/types/equipment'
import { BookingConflictError, type EquipmentApi } from '~/services/equipmentApi'
import { GraphQlError, type GraphQlClient } from './client'

/** Fields the UI needs. Server returns specs as an ordered list; the UI uses a record. */
export const EQUIPMENT_FIELDS = `
  id title category make model year hours location listingType salePriceCents
  rentalRates { dailyCents weeklyCents monthlyCents }
  description
  specs { name value }
`

interface EquipmentDto extends Omit<Equipment, 'specs' | 'bookings'> {
  specs: { name: string; value: string }[]
  bookings?: Booking[]
}

export function toEquipment(dto: EquipmentDto): Equipment {
  return {
    ...dto,
    specs: Object.fromEntries(dto.specs.map((s) => [s.name, s.value])),
    bookings: (dto.bookings ?? []).map((b) => ({ start: b.start, end: b.end })),
  }
}

export function createGraphQlEquipmentApi(gql: GraphQlClient): EquipmentApi {
  return {
    async list() {
      // One round trip; the server batches bookings for all machines into a single SQL query.
      const data = await gql.request<{ equipment: EquipmentDto[] }>(`{ equipment { ${EQUIPMENT_FIELDS} bookings { start end } } }`)
      return data.equipment.map(toEquipment)
    },
    async get(id) {
      const data = await gql.request<{ equipmentById: EquipmentDto | null }>(
        `query($id: ID!) { equipmentById(id: $id) { ${EQUIPMENT_FIELDS} bookings { start end } } }`,
        { id },
      )
      return data.equipmentById ? toEquipment(data.equipmentById) : null
    },
    async createBooking(equipmentId, booking) {
      try {
        const data = await gql.request<{ createBooking: Booking }>(
          `mutation($input: CreateBookingInput!) { createBooking(input: $input) { start end } }`,
          { input: { equipmentId, start: booking.start, end: booking.end } },
        )
        return data.createBooking
      } catch (e) {
        if (e instanceof GraphQlError && e.code === 'BOOKING_CONFLICT') throw new BookingConflictError([])
        throw e
      }
    },
  }
}
