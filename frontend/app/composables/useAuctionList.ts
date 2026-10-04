/** All auctions + their equipment. Client-only because auction state is live. */
export function useAuctionList() {
  const api = useAuctionApi()
  const equipmentApi = useEquipmentApi()
  const user = useCurrentUser()

  const { data, status } = useAsyncData(
    'auction-list',
    async () => {
      const [auctions, equipment] = await Promise.all([api.list(user.value.id), equipmentApi.list()])
      const byId = new Map(equipment.map((e) => [e.id, e]))
      return auctions.map((a) => ({ auction: a, equipment: byId.get(a.equipmentId) ?? null }))
    },
    { server: false, default: () => [] },
  )

  /** equipmentId -> auctionId, for "Auction" badges on the catalog. */
  const auctionByEquipment = computed(() => new Map(data.value.map((r) => [r.auction.equipmentId, r.auction.id])))

  return { rows: data, status, auctionByEquipment }
}
