package com.travelgraph.booking.service

import com.travelgraph.booking.domain.BookingEntity
import com.travelgraph.booking.domain.BookingRepository
import com.travelgraph.booking.domain.BookingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Outcome wrapper for [BookingService.createBooking]. The GraphQL layer maps each
 * variant to a member of the `CreateBookingPayload` union (errors-as-data per the
 * shared GraphQL conventions).
 */
sealed interface CreateBookingOutcome {
    data class Created(val booking: BookingEntity, val replayed: Boolean) : CreateBookingOutcome
    data class Conflict(val conflictingBookingId: UUID, val message: String) : CreateBookingOutcome
    data class PropertyUnavailable(val propertyId: UUID, val message: String) : CreateBookingOutcome
}

sealed interface CancelBookingOutcome {
    data class Cancelled(val booking: BookingEntity) : CancelBookingOutcome
    data class NotFound(val bookingId: UUID) : CancelBookingOutcome
    data class AlreadyCancelled(val booking: BookingEntity) : CancelBookingOutcome
}

@Service
class BookingService(
    private val repository: BookingRepository,
) {
    private val log = LoggerFactory.getLogger(BookingService::class.java)

    @Transactional(readOnly = true)
    suspend fun findByUser(userId: UUID): List<BookingEntity> = withContext(Dispatchers.IO) {
        repository.findAllByUserIdOrderByCheckInDesc(userId)
    }

    @Transactional(readOnly = true)
    suspend fun findById(id: UUID): BookingEntity? = withContext(Dispatchers.IO) {
        repository.findById(id).orElse(null)
    }

    /**
     * Create a booking with idempotency-key safety.
     *
     * Order of operations (deliberate, must not be reordered):
     *   1. Look up by idempotency key. If found, return the existing booking with `replayed=true`.
     *      A duplicate POST never produces a second booking, never produces a different result.
     *   2. Run conflict detection on confirmed overlapping bookings.
     *   3. Insert. The DB also has a UNIQUE constraint on `idempotency_key` so a concurrent
     *      duplicate request that races past step 1 is rejected at insert time -- in that case
     *      we re-fetch and return the winning row.
     */
    @Transactional
    suspend fun createBooking(
        propertyId: UUID,
        userId: UUID,
        checkIn: LocalDate,
        checkOut: LocalDate,
        totalAmount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): CreateBookingOutcome = withContext(Dispatchers.IO) {

        require(checkOut.isAfter(checkIn)) { "checkOut must be after checkIn" }
        require(totalAmount.signum() >= 0) { "totalAmount must be non-negative" }

        // Step 1: idempotency replay.
        repository.findByIdempotencyKey(idempotencyKey)?.let { existing ->
            log.info("Idempotent replay for key {} -> booking {}", idempotencyKey, existing.id)
            return@withContext CreateBookingOutcome.Created(existing, replayed = true)
        }

        // Step 2: conflict detection against confirmed bookings.
        val overlaps = repository.findOverlappingConfirmed(propertyId, checkIn, checkOut)
        if (overlaps.isNotEmpty()) {
            val conflict = overlaps.first()
            return@withContext CreateBookingOutcome.Conflict(
                conflictingBookingId = conflict.id,
                message = "Property $propertyId has an overlapping confirmed booking on " +
                    "${conflict.checkIn}..${conflict.checkOut}.",
            )
        }

        // Step 3: insert. Catch the unique-constraint violation as a final idempotency safety net.
        val candidate = BookingEntity(
            id = UUID.randomUUID(),
            propertyId = propertyId,
            userId = userId,
            checkIn = checkIn,
            checkOut = checkOut,
            status = BookingStatus.CONFIRMED,
            totalAmount = totalAmount,
            currency = currency,
            idempotencyKey = idempotencyKey,
        )
        try {
            val saved = repository.saveAndFlush(candidate)
            CreateBookingOutcome.Created(saved, replayed = false)
        } catch (e: DataIntegrityViolationException) {
            log.warn("Idempotency-key race for {}; falling back to existing row", idempotencyKey)
            val winner = repository.findByIdempotencyKey(idempotencyKey)
                ?: throw IllegalStateException("Unique violation but no row exists for $idempotencyKey", e)
            CreateBookingOutcome.Created(winner, replayed = true)
        }
    }

    @Transactional
    suspend fun cancelBooking(bookingId: UUID): CancelBookingOutcome = withContext(Dispatchers.IO) {
        val existing = repository.findById(bookingId).orElse(null)
            ?: return@withContext CancelBookingOutcome.NotFound(bookingId)
        if (existing.status == BookingStatus.CANCELLED) {
            return@withContext CancelBookingOutcome.AlreadyCancelled(existing)
        }
        existing.status = BookingStatus.CANCELLED
        existing.updatedAt = java.time.OffsetDateTime.now()
        CancelBookingOutcome.Cancelled(repository.save(existing))
    }

    /**
     * Returns the room types currently available at the property for the given range.
     * In Phase 1 this is a synthetic catalogue (no inventory model yet) -- a room type is
     * considered available if there are fewer overlapping confirmed bookings than its
     * `maxInventory`.
     */
    @Transactional(readOnly = true)
    suspend fun availableRooms(
        propertyId: UUID,
        checkIn: LocalDate,
        checkOut: LocalDate,
    ): List<RoomTypeAvailability> = withContext(Dispatchers.IO) {
        val booked = repository.findOverlappingConfirmed(propertyId, checkIn, checkOut).size
        SyntheticRoomCatalogue.types.mapNotNull { rt ->
            val remaining = (rt.maxInventory - booked).coerceAtLeast(0)
            if (remaining > 0) {
                RoomTypeAvailability(
                    id = rt.id,
                    name = rt.name,
                    description = rt.description,
                    maxOccupancy = rt.maxOccupancy,
                    basePrice = rt.basePrice,
                    available = remaining,
                )
            } else null
        }
    }
}

data class RoomTypeAvailability(
    val id: String,
    val name: String,
    val description: String,
    val maxOccupancy: Int,
    val basePrice: BigDecimal,
    val available: Int,
)

/**
 * Phase-1 placeholder room catalogue. A real inventory model belongs in property-service
 * and is wired in via federation in a later phase.
 */
private object SyntheticRoomCatalogue {
    data class RoomType(
        val id: String,
        val name: String,
        val description: String,
        val maxOccupancy: Int,
        val basePrice: BigDecimal,
        val maxInventory: Int,
    )

    val types: List<RoomType> = listOf(
        RoomType("STANDARD_KING",  "Standard King",  "King bed, 320 sq ft, city view.",     2, BigDecimal("0.00"), 12),
        RoomType("STANDARD_DOUBLE","Standard Double","Two double beds, 350 sq ft.",          4, BigDecimal("0.00"), 10),
        RoomType("DELUXE_KING",    "Deluxe King",    "King bed, balcony, 400 sq ft.",       2, BigDecimal("0.00"), 6),
        RoomType("SUITE",          "Junior Suite",   "Living area, king bed, 600 sq ft.",    3, BigDecimal("0.00"), 4),
    )
}
