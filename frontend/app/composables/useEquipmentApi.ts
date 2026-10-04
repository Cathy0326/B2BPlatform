import { createMockAuctionApi, type AuctionApi } from '~/services/auctionApi'
import { createMockEquipmentApi, type EquipmentApi } from '~/services/equipmentApi'

let equipmentSingleton: EquipmentApi | null = null
let auctionSingleton: AuctionApi | null = null

/**
 * Picks the data source. Phase 1: always the in-browser mock.
 * (Phase 2 switches to GraphQL when runtimeConfig.public.graphqlUrl is set.)
 */
export function useEquipmentApi(): EquipmentApi {
  equipmentSingleton ??= createMockEquipmentApi()
  return equipmentSingleton
}

export function useAuctionApi(): AuctionApi {
  auctionSingleton ??= createMockAuctionApi()
  return auctionSingleton
}
