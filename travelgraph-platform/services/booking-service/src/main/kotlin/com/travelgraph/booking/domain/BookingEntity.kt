package com.travelgraph.booking.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class BookingStatus { CONFIRMED, CANCELLED, PENDING }

@Entity
@Table(name = "bookings", schema = "booking_schema")
class BookingEntity(
    @Id
    @Column(nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "property_id", nullable = false)
    val propertyId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "check_in", nullable = false)
    val checkIn: LocalDate,

    @Column(name = "check_out", nullable = false)
    val checkOut: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: BookingStatus,

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    val totalAmount: BigDecimal,

    @Column(nullable = false, length = 3)
    val currency: String = "USD",

    @Column(name = "idempotency_key", nullable = false, length = 128, unique = true)
    val idempotencyKey: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
