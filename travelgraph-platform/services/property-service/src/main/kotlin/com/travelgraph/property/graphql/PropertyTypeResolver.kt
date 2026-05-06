package com.travelgraph.property.graphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID
import kotlinx.coroutines.future.await

/**
 * Federated type resolver for the [Property] entity.
 *
 * Resolves entity references shaped like `{ __typename: "Property", id: "..." }` that arrive on
 * the `_entities` query. We delegate to [PropertyDataLoader] so that multiple representations in
 * the same `_entities` call are batched into one repository round trip — this is the
 * router's lever to avoid N+1 fan-out at the subgraph level.
 */
@Component
class PropertyTypeResolver : FederatedTypeSuspendResolver<Property> {

    override val typeName: String = "Property"

    override suspend fun resolve(
        environment: DataFetchingEnvironment,
        representation: Map<String, Any>,
    ): Property? {
        val raw = representation["id"] as? String ?: return null
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return environment
            .getDataLoader<UUID, Property?>(PropertyDataLoader.NAME)
            .load(id)
            .await()
    }
}
