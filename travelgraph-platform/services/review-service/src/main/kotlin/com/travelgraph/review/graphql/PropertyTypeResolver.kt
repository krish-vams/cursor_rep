package com.travelgraph.review.graphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Resolves `_entities` representations of `{ __typename: "Property", id: <uuid> }` into the
 * federation-extending [Property] stub. Field-level resolution (reviews, reviewSummary) is
 * batched via the existing review DataLoaders.
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
