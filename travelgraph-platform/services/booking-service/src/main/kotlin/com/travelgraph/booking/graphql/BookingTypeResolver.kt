package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Federated type resolver for [Booking]. Routes through [BookingDataLoader] so that all
 * representations from a single `_entities` call hit one repository round trip.
 */
@Component
class BookingTypeResolver : FederatedTypeSuspendResolver<Booking> {

    override val typeName: String = "Booking"

    override suspend fun resolve(
        environment: DataFetchingEnvironment,
        representation: Map<String, Any>,
    ): Booking? {
        val raw = representation["id"] as? String ?: return null
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return environment
            .getDataLoader<UUID, Booking?>(BookingDataLoader.NAME)
            .load(id)
            .await()
    }
}
