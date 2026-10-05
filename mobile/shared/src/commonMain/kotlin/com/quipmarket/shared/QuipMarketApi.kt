package com.quipmarket.shared

import com.apollographql.apollo.ApolloClient
import com.quipmarket.shared.graphql.RentalEquipmentQuery

/** What the apps show in the rental list. Kept free of generated GraphQL types so the UI does not depend on them. */
data class RentalListing(
    val id: String,
    val title: String,
    val category: String,
    val subtitle: String,
    val location: String,
    val rates: RentalRates,
)

class ApiException(message: String) : Exception(message)

/** Typed access to the QuipMarket GraphQL API, shared by Android and iOS. */
class QuipMarketApi(serverUrl: String) {
    private val apollo = ApolloClient.Builder().serverUrl(serverUrl).build()

    suspend fun rentalListings(): List<RentalListing> {
        val response = apollo.query(RentalEquipmentQuery()).execute()
        response.exception?.let { throw ApiException("Cannot reach the server: ${it.message}") }
        response.errors?.firstOrNull()?.let { throw ApiException(it.message) }
        val equipment = response.data?.equipment ?: throw ApiException("Empty response")
        return equipment.mapNotNull { e ->
            val r = e.rentalRates ?: return@mapNotNull null
            RentalListing(
                id = e.id,
                title = e.title,
                category = e.category.rawValue.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                subtitle = "${e.year} ${e.make} ${e.model} · ${e.hours} h",
                location = e.location,
                rates = RentalRates(r.dailyCents, r.weeklyCents, r.monthlyCents),
            )
        }
    }

    fun close() = apollo.close()
}
