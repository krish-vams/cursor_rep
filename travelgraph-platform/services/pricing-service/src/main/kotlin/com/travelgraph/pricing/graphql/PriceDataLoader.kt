package com.travelgraph.pricing.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.travelgraph.pricing.service.LoyaltyTier
import com.travelgraph.pricing.service.PricingService
import graphql.GraphQLContext
import kotlinx.coroutines.runBlocking
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Batches `price(propertyId: ...)` lookups when the same date range and loyalty
 * tier are requested across multiple properties. Date range is encoded in the
 * loader key so distinct stays are loaded separately.
 */
data class PriceLoaderKey(
    val propertyId: UUID,
    val checkIn: LocalDate?,
    val checkOut: LocalDate?,
    val loyalty: LoyaltyTier = LoyaltyTier.NONE,
)

@Component
class PriceDataLoader(
    private val pricingService: PricingService,
) : KotlinDataLoader<PriceLoaderKey, Price?> {

    override val dataLoaderName: String = NAME

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<PriceLoaderKey, Price?> {
        val batchLoader = BatchLoader<PriceLoaderKey, Price?> { keys ->
            CompletableFuture.supplyAsync {
                runBlocking {
                    val rowsById = pricingService.findBaseRows(keys.map { it.propertyId }.toSet())
                    keys.map { key ->
                        rowsById[key.propertyId]?.let { row ->
                            Price.fromQuote(
                                pricingService.quoteFromRow(row, key.checkIn, key.checkOut, key.loyalty),
                            )
                        }
                    }
                }
            }
        }
        return DataLoaderFactory.newDataLoader(
            batchLoader,
            DataLoaderOptions.newOptions().setCachingEnabled(true).setBatchingEnabled(true),
        )
    }

    companion object {
        const val NAME: String = "PriceDataLoader"
    }
}
