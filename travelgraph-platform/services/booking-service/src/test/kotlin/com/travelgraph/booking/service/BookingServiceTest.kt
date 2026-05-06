package com.travelgraph.booking.service

import com.travelgraph.booking.domain.BookingEntity
import com.travelgraph.booking.domain.BookingRepository
import com.travelgraph.booking.domain.BookingStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID

/**
 * Verifies the idempotency contract for [BookingService.createBooking]:
 * - Submitting the same idempotencyKey twice returns the same booking, marked as a replay.
 * - Repository is never asked to insert a second row when the key is already present.
 * - Conflict detection wins over a fresh insert when overlapping confirmed bookings exist.
 */
class BookingServiceTest {

    private val propertyId = UUID.fromString("11111111-1111-1111-1111-000000000001")
    private val userId = UUID.fromString("22222222-2222-2222-2222-000000000001")

    private fun sampleEntity(idempotencyKey: String, status: BookingStatus = BookingStatus.CONFIRMED) =
        BookingEntity(
            id = UUID.randomUUID(),
            propertyId = propertyId,
            userId = userId,
            checkIn = LocalDate.of(2026, 6, 10),
            checkOut = LocalDate.of(2026, 6, 13),
            status = status,
            totalAmount = BigDecimal("600.00"),
            currency = "USD",
            idempotencyKey = idempotencyKey,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now(),
        )

    @Test
    fun `duplicate idempotency key returns existing booking and never inserts a second row`() = runBlocking {
        val repo: BookingRepository = mock()
        val existing = sampleEntity("idem-A")
        whenever(repo.findByIdempotencyKey("idem-A")).thenReturn(existing)

        val service = BookingService(repo)

        val outcome = service.createBooking(
            propertyId = propertyId,
            userId = userId,
            checkIn = existing.checkIn,
            checkOut = existing.checkOut,
            totalAmount = existing.totalAmount,
            currency = existing.currency,
            idempotencyKey = "idem-A",
        )

        assertTrue(outcome is CreateBookingOutcome.Created, "expected Created outcome but got $outcome")
        outcome as CreateBookingOutcome.Created
        assertSame(existing, outcome.booking)
        assertTrue(outcome.replayed, "outcome must be marked as a replay")

        // Critically: saveAndFlush must never be called.
        org.mockito.kotlin.verify(repo, org.mockito.kotlin.never()).saveAndFlush(any<BookingEntity>())
    }

    @Test
    fun `overlapping confirmed booking causes a Conflict outcome`() = runBlocking {
        val repo: BookingRepository = mock()
        val overlap = sampleEntity("prior-key")
        whenever(repo.findByIdempotencyKey("idem-B")).thenReturn(null)
        whenever(repo.findOverlappingConfirmed(any(), any(), any())).thenReturn(listOf(overlap))

        val service = BookingService(repo)

        val outcome = service.createBooking(
            propertyId = propertyId,
            userId = userId,
            checkIn = LocalDate.of(2026, 6, 11),
            checkOut = LocalDate.of(2026, 6, 12),
            totalAmount = BigDecimal("220.00"),
            currency = "USD",
            idempotencyKey = "idem-B",
        )

        assertTrue(outcome is CreateBookingOutcome.Conflict, "expected Conflict outcome but got $outcome")
        outcome as CreateBookingOutcome.Conflict
        assertEquals(overlap.id, outcome.conflictingBookingId)
        org.mockito.kotlin.verify(repo, org.mockito.kotlin.never()).saveAndFlush(any<BookingEntity>())
    }

    @Test
    fun `new booking with no overlap and unused idempotency key is created`() = runBlocking {
        val repo: BookingRepository = mock()
        whenever(repo.findByIdempotencyKey("idem-C")).thenReturn(null)
        whenever(repo.findOverlappingConfirmed(any(), any(), any())).thenReturn(emptyList())
        whenever(repo.saveAndFlush(any<BookingEntity>())).thenAnswer { it.arguments[0] as BookingEntity }

        val service = BookingService(repo)

        val outcome = service.createBooking(
            propertyId = propertyId,
            userId = userId,
            checkIn = LocalDate.of(2026, 6, 11),
            checkOut = LocalDate.of(2026, 6, 12),
            totalAmount = BigDecimal("220.00"),
            currency = "USD",
            idempotencyKey = "idem-C",
        )

        assertTrue(outcome is CreateBookingOutcome.Created, "expected Created outcome but got $outcome")
        outcome as CreateBookingOutcome.Created
        assertEquals("idem-C", outcome.booking.idempotencyKey)
        assertEquals(false, outcome.replayed)
    }

    @Test
    fun `cancelling a not-found booking returns NotFound`() = runBlocking {
        val repo: BookingRepository = mock()
        val missingId = UUID.randomUUID()
        whenever(repo.findById(missingId)).thenReturn(Optional.empty())

        val service = BookingService(repo)

        val outcome = service.cancelBooking(missingId)
        assertTrue(outcome is CancelBookingOutcome.NotFound)
        outcome as CancelBookingOutcome.NotFound
        assertEquals(missingId, outcome.bookingId)
    }

    @Test
    fun `cancelling an already-cancelled booking returns AlreadyCancelled`() = runBlocking {
        val repo: BookingRepository = mock()
        val cancelled = sampleEntity("idem-D", status = BookingStatus.CANCELLED)
        whenever(repo.findById(cancelled.id)).thenReturn(Optional.of(cancelled))

        val service = BookingService(repo)

        val outcome = service.cancelBooking(cancelled.id)
        assertTrue(outcome is CancelBookingOutcome.AlreadyCancelled)
        outcome as CancelBookingOutcome.AlreadyCancelled
        assertEquals(cancelled.id, outcome.booking.id)
        assertNotEquals(BookingStatus.CONFIRMED, outcome.booking.status)
    }
}
