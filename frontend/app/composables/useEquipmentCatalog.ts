import type { EquipmentFilter } from '~/types/equipment'
import { applyFilter, filterFromQuery, filterToQuery } from '~/utils/filters'

/**
 * Catalog page state.
 * Source of truth for filters = the URL query string (shareable, survives refresh, back button works).
 * Data flow:  URL query --(filterFromQuery)--> filter --(applyFilter)--> visible items
 *             user edits --(filterToQuery)--> router.replace --> URL query (loop closes)
 */
export function useEquipmentCatalog() {
  const api = useEquipmentApi()
  const route = useRoute()
  const router = useRouter()

  const { data, status, error, refresh } = useAsyncData('equipment-list', () => api.list(), {
    default: () => [],
  })

  const filter = computed<EquipmentFilter>(() => filterFromQuery(route.query))
  const items = computed(() => applyFilter(data.value, filter.value))

  function updateFilter(patch: Partial<EquipmentFilter>) {
    const next = { ...filter.value, ...patch }
    router.replace({ query: filterToQuery(next) })
  }

  function resetFilter() {
    router.replace({ query: {} })
  }

  const makes = computed(() => [...new Set(data.value.map((e) => e.make))].sort())

  return { all: data, items, filter, status, error, refresh, updateFilter, resetFilter, makes }
}
