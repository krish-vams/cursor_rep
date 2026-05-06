package com.travelgraph.review.service

import com.travelgraph.review.domain.ReviewAggregate
import com.travelgraph.review.domain.ReviewEntity
import com.travelgraph.review.domain.ReviewRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Verifies the addReview / reviewSummary contracts:
 * - rating outside 1..5 returns Validation, not Created.
 * - blank comment returns Validation.
 * - existing (user, property) review returns Duplicate, never inserts.
 * - empty property still summarises cleanly with average=0, count=0.
 */
class ReviewServiceTest {

    private val propertyId = UUID.fromString("11111111-1111-1111-1111-000000000001")
    private val userId = UUID.fromString("33333333-3333-3333-3333-000000000001")

    @Test
    fun `rating outside 1 to 5 returns Validation outcome`() = runBlocking {
        val repo: ReviewRepository = mock()
        val service = ReviewService(repo)

        val low = service.addReview(propertyId, userId, rating = 0, comment = "fine")
        val high = service.addReview(propertyId, userId, rating = 6, comment = "fine")

        listOf(low, high).forEach { outcome ->
            assertTrue(outcome is AddReviewOutcome.Validation, "expected Validation but got $outcome")
            outcome as AddReviewOutcome.Validation
            assertTrue(outcome.fieldErrors.any { it.field == "rating" })
        }
        verify(repo, never()).saveAndFlush(any<ReviewEntity>())
    }

    @Test
    fun `blank comment returns Validation outcome`() = runBlocking {
        val repo: ReviewRepository = mock()
        val service = ReviewService(repo)

        val outcome = service.addReview(propertyId, userId, rating = 4, comment = "   ")
        assertTrue(outcome is AddReviewOutcome.Validation)
        outcome as AddReviewOutcome.Validation
        assertTrue(outcome.fieldErrors.any { it.field == "comment" })
        verify(repo, never()).saveAndFlush(any<ReviewEntity>())
    }

    @Test
    fun `existing review for the same user and property returns Duplicate`() = runBlocking {
        val repo: ReviewRepository = mock()
        val existing = ReviewEntity(
            id = UUID.randomUUID(),
            propertyId = propertyId,
            userId = userId,
            rating = 5,
            comment = "Loved it",
            createdAt = OffsetDateTime.now().minusDays(7),
        )
        whenever(repo.findByUserIdAndPropertyId(userId, propertyId)).thenReturn(existing)

        val service = ReviewService(repo)
        val outcome = service.addReview(propertyId, userId, rating = 4, comment = "Decent stay")

        assertTrue(outcome is AddReviewOutcome.Duplicate)
        outcome as AddReviewOutcome.Duplicate
        assertEquals(existing.id, outcome.existingReviewId)
        verify(repo, never()).saveAndFlush(any<ReviewEntity>())
    }

    @Test
    fun `valid first review is Created`() = runBlocking {
        val repo: ReviewRepository = mock()
        whenever(repo.findByUserIdAndPropertyId(userId, propertyId)).thenReturn(null)
        whenever(repo.saveAndFlush(any<ReviewEntity>())).thenAnswer { it.arguments[0] as ReviewEntity }

        val service = ReviewService(repo)
        val outcome = service.addReview(propertyId, userId, rating = 5, comment = "Great")

        assertTrue(outcome is AddReviewOutcome.Created)
        outcome as AddReviewOutcome.Created
        assertEquals(propertyId, outcome.review.propertyId)
        assertEquals(userId, outcome.review.userId)
        assertEquals(5.toShort(), outcome.review.rating)
    }

    @Test
    fun `empty property summarises to count 0 and average 0`() = runBlocking {
        val repo: ReviewRepository = mock()
        whenever(repo.aggregateByPropertyIds(listOf(propertyId))).thenReturn(emptyList())

        val service = ReviewService(repo)
        val summary = service.summariseByProperty(propertyId)

        assertEquals(0L, summary.count)
        assertEquals(0.0, summary.average)
    }

    @Test
    fun `summariseByProperties backfills properties with no reviews`() = runBlocking {
        val repo: ReviewRepository = mock()
        val withReviews = UUID.randomUUID()
        val withoutReviews = UUID.randomUUID()
        whenever(repo.aggregateByPropertyIds(listOf(withReviews, withoutReviews)))
            .thenReturn(listOf(ReviewAggregate(propertyId = withReviews, count = 3L, average = 4.5)))

        val service = ReviewService(repo)
        val summaries = service.summariseByProperties(listOf(withReviews, withoutReviews))

        assertEquals(3L, summaries.getValue(withReviews).count)
        assertEquals(4.5, summaries.getValue(withReviews).average)
        assertEquals(0L, summaries.getValue(withoutReviews).count)
        assertEquals(0.0, summaries.getValue(withoutReviews).average)
    }
}
