package com.travelgraph.booking.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.travelgraph.booking.domain.BookingRepository
import graphql.GraphQLContext
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Batches bookings-by-id lookups (used when nested fields resolve back to a Booking).
 * Per-user batching is intentionally not done here -- `bookings(userId:)` is itself
 * a single repository call.
 */
@Component
class BookingDataLoader(
    private val repository: BookingRepository,
) : KotlinDataLoader<UUID, Booking?> {

    override val dataLoaderName: String = NAME

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, Booking?> {
        val batchLoader = BatchLoader<UUID, Booking?> { ids ->
            CompletableFuture.supplyAsync {
                val byId = repository.findAllById(ids).associateBy { it.id }
                ids.map { id -> byId[id]?.let(Booking.Companion::fromEntity) }
            }
        }
        return DataLoaderFactory.newDataLoader(
            batchLoader,
            DataLoaderOptions.newOptions().setCachingEnabled(true).setBatchingEnabled(true),
        )
    }

    companion object {
        const val NAME: String = "BookingDataLoader"
    }
}
