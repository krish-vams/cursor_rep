package com.travelgraph.pricing.graphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves `_entities` representations of `{ __typename: "Property", id: <uuid> }` into the
 * federation-extending [Property] stub. We don't fetch any property data here -- the only
 * field this subgraph contributes is [Property.price], which itself routes through
 * [PriceDataLoader] for batched per-request pricing.
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
        return Property(id = id)
    }
}
