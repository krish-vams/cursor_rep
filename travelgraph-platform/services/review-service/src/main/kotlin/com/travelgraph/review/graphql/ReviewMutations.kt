package com.travelgraph.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import com.travelgraph.review.service.AddReviewOutcome
import com.travelgraph.review.service.ReviewService
import org.springframework.stereotype.Component
import java.util.UUID

@GraphQLDescription("Input for the addReview mutation.")
data class AddReviewInput(
    @GraphQLDescription("Identifier of the property being reviewed.")
    val propertyId: UUID,

    @GraphQLDescription("Identifier of the user leaving the review.")
    val userId: UUID,

    @GraphQLDescription("Rating from 1 (worst) to 5 (best).")
    val rating: Int,

    @GraphQLDescription("Free-text review body. Up to 4000 characters.")
    val comment: String,
)

@Component
class ReviewMutations(
    private val reviewService: ReviewService,
) : Mutation {

    @GraphQLDescription(
        "Add a review for a property. At most one review per (user, property) is allowed; " +
            "submitting a second one returns a DuplicateReviewError. Validation failures are " +
            "returned as ReviewValidationError. Errors are part of the schema, never the top-level " +
            "errors array.",
    )
    suspend fun addReview(input: AddReviewInput): AddReviewPayload {
        return when (val outcome = reviewService.addReview(
            propertyId = input.propertyId,
            userId = input.userId,
            rating = input.rating,
            comment = input.comment,
        )) {
            is AddReviewOutcome.Created -> Review.fromEntity(outcome.review)
            is AddReviewOutcome.Duplicate -> DuplicateReviewError(
                message = "User ${input.userId} has already reviewed property ${input.propertyId}.",
                existingReviewId = outcome.existingReviewId,
            )
            is AddReviewOutcome.Validation -> ReviewValidationError(
                message = "Review input failed validation. See fieldErrors for details.",
                fieldErrors = outcome.fieldErrors.map { FieldError(it.field, it.message) },
            )
        }
    }
}
