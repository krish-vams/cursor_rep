package com.travelgraph.review.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Mutation result for `addReview`. Per spec the union is
 * `Review | ReviewValidationError | DuplicateReviewError`. [Review] itself is the
 * success member (declared in [ReviewType.kt] as `: AddReviewPayload`).
 */
@GraphQLDescription(
    "Result of attempting to add a review. Either the new Review on success, a " +
        "ReviewValidationError when input fails validation (rating out of range, blank " +
        "comment, etc.), or a DuplicateReviewError when this user already has a review " +
        "for this property.",
)
sealed interface AddReviewPayload

@GraphQLDescription("Couldn't add the review because input failed validation.")
data class ReviewValidationError(
    @GraphQLDescription("Human-readable summary message.")
    val message: String,

    @GraphQLDescription("Per-field validation messages.")
    val fieldErrors: List<FieldError>,
) : AddReviewPayload

@GraphQLDescription("A single field-level validation error.")
data class FieldError(
    @GraphQLDescription("Name of the input field that failed validation.")
    val field: String,

    @GraphQLDescription("Human-readable message describing why the field is invalid.")
    val message: String,
)

@GraphQLDescription("Couldn't add the review because this user already reviewed this property.")
data class DuplicateReviewError(
    @GraphQLDescription("Human-readable message.")
    val message: String,

    @GraphQLDescription("Identifier of the existing review by this user for this property.")
    val existingReviewId: UUID,
) : AddReviewPayload
