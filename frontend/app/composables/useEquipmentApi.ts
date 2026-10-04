import { createMockEquipmentApi, type EquipmentApi } from '~/services/equipmentApi'

let mockSingleton: EquipmentApi | null = null

/**
 * Picks the data source. Phase 1: always the mock.
 * (Phase 2 switches to GraphQL when runtimeConfig.public.graphqlUrl is set.)
 */
export function useEquipmentApi(): EquipmentApi {
  mockSingleton ??= createMockEquipmentApi()
  return mockSingleton
}
