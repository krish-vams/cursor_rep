package com.travelgraph.booking.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface BookingRepository : JpaRepository<BookingEntity, UUID> {

    fun findByIdempotencyKey(idempotencyKey: String): BookingEntity?

    fun findAllByUserIdOrderByCheckInDesc(userId: UUID): List<BookingEntity>

    /**
     * Returns confirmed bookings that overlap the requested date range for a property.
     * Two bookings overlap when `existing.check_in < requested.check_out` AND
     * `existing.check_out > requested.check_in`.
     */
    @Query(
        """
        SELECT b FROM BookingEntity b
        WHERE b.propertyId = :propertyId
          AND b.status = com.travelgraph.booking.domain.BookingStatus.CONFIRMED
          AND b.checkIn  < :checkOut
          AND b.checkOut > :checkIn
        """,
    )
    fun findOverlappingConfirmed(
        @Param("propertyId") propertyId: UUID,
        @Param("checkIn") checkIn: LocalDate,
        @Param("checkOut") checkOut: LocalDate,
    ): List<BookingEntity>
}
