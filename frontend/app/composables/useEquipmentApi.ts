import { createMockAuctionApi, type AuctionApi } from '~/services/auctionApi'
import { createMockEquipmentApi, type EquipmentApi } from '~/services/equipmentApi'
import { createGraphQlClient, type GraphQlClient } from '~/services/graphql/client'
import { createGraphQlAuctionApi } from '~/services/graphql/auctionApi'
import { createGraphQlEquipmentApi } from '~/services/graphql/equipmentApi'

/**
 * Picks the data source ONCE, based on runtime config:
 *   NUXT_PUBLIC_GRAPHQL_URL empty -> in-browser mock (Phase 1 demo, works with no backend)
 *   NUXT_PUBLIC_GRAPHQL_URL set   -> Spring Boot GraphQL API (Phase 2)
 * Pages and components never know which one they got.
 */
let gql: GraphQlClient | null = null
let mockEquipment: EquipmentApi | null = null
let mockAuctions: AuctionApi | null = null

/** The GraphQL client, or null in mock mode. */
export function useGraphQlClient(): GraphQlClient | null {
  return graphQl()
}

function graphQl(): GraphQlClient | null {
  const config = useRuntimeConfig().public
  if (!config.graphqlUrl) return null
  if (import.meta.client && gql) return gql
  const httpUrl = config.graphqlUrl as string
  const wsUrl = (config.graphqlWsUrl as string) || httpUrl.replace(/^http/, 'ws').replace(/\/graphql$/, '/graphql-ws')
  const user = useCurrentUser()
  const client = createGraphQlClient({ httpUrl, wsUrl, userId: () => user.value.id })
  // Cache only in the browser. On the server every request gets its own client, so one
  // visitor's identity can never leak into another visitor's SSR request.
  if (import.meta.client) gql = client
  return client
}

export function useEquipmentApi(): EquipmentApi {
  const client = graphQl()
  if (client) return createGraphQlEquipmentApi(client)
  mockEquipment ??= createMockEquipmentApi()
  return mockEquipment
}

export function useAuctionApi(): AuctionApi {
  const client = graphQl()
  if (client) return createGraphQlAuctionApi(client)
  mockAuctions ??= createMockAuctionApi()
  return mockAuctions
}

export function useDataSourceLabel(): 'mock' | 'graphql' {
  return useRuntimeConfig().public.graphqlUrl ? 'graphql' : 'mock'
}
