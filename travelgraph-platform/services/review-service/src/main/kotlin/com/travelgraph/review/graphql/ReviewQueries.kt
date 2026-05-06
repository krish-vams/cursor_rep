package com.travelgraph.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import com.travelgraph.review.service.ReviewService
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Component
class ReviewQueries(
    private val reviewService: ReviewService,
) : Query {

    @GraphQLDescription("List recent reviews for a property, newest first. Bounded to a maximum of 100 per call.")
    suspend fun reviews(
        @GraphQLDescription("Identifier of the property whose reviews are being requested.")
        propertyId: UUID,
        @GraphQLDescription("Maximum number of reviews to return (1-100). Defaults to 10.")
        limit: Int = 10,
    ): List<Review> =
        reviewService.listByProperty(propertyId, limit).map(Review.Companion::fromEntity)

    @GraphQLDescription(
        "Aggregated review statistics (average rating + count) for a property. Returns " +
            "average=0 and count=0 for properties with no reviews.",
    )
    fun reviewSummary(
        @GraphQLDescription("Identifier of the property to summarise.")
        propertyId: UUID,
        env: DataFetchingEnvironment,
    ): CompletableFuture<ReviewSummary> =
        env.getDataLoader<UUID, ReviewSummary>(ReviewSummaryDataLoader.NAME).load(propertyId)
}
