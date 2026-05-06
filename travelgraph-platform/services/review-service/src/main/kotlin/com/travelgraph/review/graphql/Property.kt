package com.travelgraph.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.await
import java.util.UUID

/**
 * Federation-extending stub for the `Property` entity. Review-service does not OWN Property;
 * property-service does. This stub declares the `reviews(...)` and `reviewSummary` fields the
 * review subgraph contributes back to the federated Property type.
 *
 * Both fields delegate to the existing per-property DataLoaders so that an `_entities`
 * call with N representations issues exactly ONE batched DB roundtrip per field
 * regardless of N.
 */
@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription(
    "Review-service contribution to the federated Property entity. Owned by property-service; " +
        "this subgraph adds `reviews` and `reviewSummary`.",
)
data class Property(
    @GraphQLDescription("Stable unique identifier for the property (key).")
    val id: UUID,
) {

    @GraphQLDescription("Most-recent reviews for this property, newest first.")
    suspend fun reviews(
        @GraphQLDescription("Maximum number of reviews to return (1-50). Defaults to 10.")
        limit: Int = 10,
        env: DataFetchingEnvironment,
    ): List<Review> {
        val all = env
            .getDataLoader<UUID, List<Review>>(ReviewsByPropertyDataLoader.NAME)
            .load(id)
            .await()
        return all.take(limit.coerceIn(1, 50))
    }

    @GraphQLDescription("Aggregated review statistics for this property.")
    suspend fun reviewSummary(env: DataFetchingEnvironment): ReviewSummary =
        env.getDataLoader<UUID, ReviewSummary>(ReviewSummaryDataLoader.NAME).load(id).await()
}
