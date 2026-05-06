package com.travelgraph.review.service

import com.travelgraph.review.domain.ReviewAggregate
import com.travelgraph.review.domain.ReviewEntity
import com.travelgraph.review.domain.ReviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Outcome of an `addReview` mutation. Mapped to a GraphQL union by [com.travelgraph.review.graphql.ReviewMutations].
 */
sealed interface AddReviewOutcome {
    data class Created(val review: ReviewEntity) : AddReviewOutcome

    /** Validation failed (rating out of range, comment empty, etc.). */
    data class Validation(val fieldErrors: List<FieldError>) : AddReviewOutcome

    /** A review by this user for this property already exists. */
    data class Duplicate(val existingReviewId: UUID) : AddReviewOutcome
}

data class FieldError(val field: String, val message: String)

@Service
class ReviewService(
    private val repository: ReviewRepository,
) {
    private val log = LoggerFactory.getLogger(ReviewService::class.java)

    @Transactional(readOnly = true)
    suspend fun listByProperty(propertyId: UUID, limit: Int): List<ReviewEntity> = withContext(Dispatchers.IO) {
        val safe = limit.coerceIn(1, 100)
        repository.findAllByPropertyIdOrderByCreatedAtDesc(propertyId, PageRequest.of(0, safe))
    }

    @Transactional(readOnly = true)
    suspend fun summariseByProperty(propertyId: UUID): ReviewAggregate = withContext(Dispatchers.IO) {
        repository.aggregateByPropertyIds(listOf(propertyId)).firstOrNull()
            ?: ReviewAggregate(propertyId = propertyId, count = 0L, average = 0.0)
    }

    @Transactional(readOnly = true)
    suspend fun summariseByProperties(propertyIds: Collection<UUID>): Map<UUID, ReviewAggregate> =
        withContext(Dispatchers.IO) {
            val aggregates = repository.aggregateByPropertyIds(propertyIds).associateBy { it.propertyId }
            // Backfill zero-aggregates for properties with no reviews so the loader returns
            // a non-null entry for every requested property id.
            propertyIds.associateWith { id ->
                aggregates[id] ?: ReviewAggregate(propertyId = id, count = 0L, average = 0.0)
            }
        }

    @Transactional(readOnly = true)
    suspend fun listByProperties(propertyIds: Collection<UUID>): Map<UUID, List<ReviewEntity>> =
        withContext(Dispatchers.IO) {
            repository.findAllByPropertyIdInOrderByCreatedAtDesc(propertyIds)
                .groupBy { it.propertyId }
        }

    /**
     * Validate input, then attempt to insert. Order of checks (deliberate):
     *   1. Validate rating is in 1..5 and comment is non-blank.
     *   2. Look up an existing (userId, propertyId) review. If present, return Duplicate.
     *   3. Insert. If a concurrent insert wins the unique constraint race, fall back to
     *      reading the winning row and return Duplicate.
     */
    @Transactional
    suspend fun addReview(
        propertyId: UUID,
        userId: UUID,
        rating: Int,
        comment: String,
    ): AddReviewOutcome = withContext(Dispatchers.IO) {

        val errors = mutableListOf<FieldError>()
        if (rating < 1 || rating > 5) {
            errors += FieldError("rating", "Rating must be between 1 and 5 (inclusive); got $rating.")
        }
        if (comment.isBlank()) {
            errors += FieldError("comment", "Comment must not be blank.")
        } else if (comment.length > 4000) {
            errors += FieldError("comment", "Comment must be at most 4000 characters; got ${comment.length}.")
        }
        if (errors.isNotEmpty()) {
            return@withContext AddReviewOutcome.Validation(errors)
        }

        repository.findByUserIdAndPropertyId(userId, propertyId)?.let { existing ->
            return@withContext AddReviewOutcome.Duplicate(existingReviewId = existing.id)
        }

        val candidate = ReviewEntity(
            id = UUID.randomUUID(),
            propertyId = propertyId,
            userId = userId,
            rating = rating.toShort(),
            comment = comment,
            createdAt = OffsetDateTime.now(),
        )
        try {
            AddReviewOutcome.Created(repository.saveAndFlush(candidate))
        } catch (e: DataIntegrityViolationException) {
            log.warn("Race on reviews_user_property_unique for ({}, {}); returning duplicate", userId, propertyId)
            val winner = repository.findByUserIdAndPropertyId(userId, propertyId)
                ?: throw IllegalStateException("Unique violation but no row exists for ($userId, $propertyId)", e)
            AddReviewOutcome.Duplicate(existingReviewId = winner.id)
        }
    }
}
