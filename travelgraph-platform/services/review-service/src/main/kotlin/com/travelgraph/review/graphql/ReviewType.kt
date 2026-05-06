package com.travelgraph.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.travelgraph.review.domain.ReviewEntity
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("A guest review of a property.")
data class Review(
    @GraphQLDescription("Stable unique identifier of the review.")
    val id: UUID,

    @GraphQLDescription("Identifier of the reviewed property.")
    val propertyId: UUID,

    @GraphQLDescription("Identifier of the user who left the review.")
    val userId: UUID,

    @GraphQLDescription("Rating from 1 (worst) to 5 (best).")
    val rating: Int,

    @GraphQLDescription("Free-text review body.")
    val comment: String,

    @GraphQLDescription("UTC timestamp the review was created.")
    val createdAt: OffsetDateTime,
) : AddReviewPayload {
    companion object {
        fun fromEntity(e: ReviewEntity): Review = Review(
            id = e.id,
            propertyId = e.propertyId,
            userId = e.userId,
            rating = e.rating.toInt(),
            comment = e.comment,
            createdAt = e.createdAt,
        )
    }
}

@GraphQLDescription("Aggregated review statistics for a property.")
data class ReviewSummary(
    @GraphQLDescription("Identifier of the property the summary applies to.")
    val propertyId: UUID,

    @GraphQLDescription("Mean rating across all reviews. Zero if there are no reviews.")
    val average: Double,

    @GraphQLDescription("Total number of reviews used to compute the average.")
    val count: Int,
)
