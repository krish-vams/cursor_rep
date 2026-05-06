package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.travelgraph.booking.domain.BookingEntity
import com.travelgraph.booking.domain.BookingStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@GraphQLDescription("Lifecycle status of a Booking.")
enum class BookingStatusGql {
    @GraphQLDescription("Booking is confirmed and the property is reserved for the dates.")
    CONFIRMED,

    @GraphQLDescription("Booking has been cancelled and the dates are released.")
    CANCELLED,

    @GraphQLDescription("Booking is awaiting confirmation (e.g. pending payment).")
    PENDING,
    ;

    companion object {
        fun fromDomain(s: BookingStatus): BookingStatusGql = when (s) {
            BookingStatus.CONFIRMED -> CONFIRMED
            BookingStatus.CANCELLED -> CANCELLED
            BookingStatus.PENDING -> PENDING
        }
    }
}

@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("A reservation made by a user against a property for a date range.")
data class Booking(
    @GraphQLDescription("Stable unique identifier of the booking.")
    val id: UUID,

    @GraphQLDescription("Identifier of the booked property.")
    val propertyId: UUID,

    @GraphQLDescription("Identifier of the user who placed the booking.")
    val userId: UUID,

    @GraphQLDescription("Stay start date in ISO-8601 format (YYYY-MM-DD).")
    val checkIn: LocalDate,

    @GraphQLDescription("Stay end date in ISO-8601 format (YYYY-MM-DD).")
    val checkOut: LocalDate,

    @GraphQLDescription("Current lifecycle status of the booking.")
    val status: BookingStatusGql,

    @GraphQLDescription("Total amount charged including taxes and discounts.")
    val totalAmount: BigDecimal,

    @GraphQLDescription("ISO 4217 currency code (e.g. USD).")
    val currency: String,
) : CreateBookingPayload, CancelBookingPayload {
    companion object {
        fun fromEntity(e: BookingEntity): Booking = Booking(
            id = e.id,
            propertyId = e.propertyId,
            userId = e.userId,
            checkIn = e.checkIn,
            checkOut = e.checkOut,
            status = BookingStatusGql.fromDomain(e.status),
            totalAmount = e.totalAmount,
            currency = e.currency,
        )
    }
}

@GraphQLDescription("A bookable room type at a property, with current availability.")
data class RoomType(
    @GraphQLDescription("Stable identifier of the room type.")
    val id: String,

    @GraphQLDescription("Display name (e.g. Standard King).")
    val name: String,

    @GraphQLDescription("Marketing description of the room type.")
    val description: String,

    @GraphQLDescription("Maximum number of guests this room type accommodates.")
    val maxOccupancy: Int,

    @GraphQLDescription("Base nightly price before taxes and discounts. May be zero in Phase 1 (pricing-service is authoritative).")
    val basePrice: BigDecimal,

    @GraphQLDescription("Number of rooms of this type still available for the requested dates.")
    val available: Int,
)
