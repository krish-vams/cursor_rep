package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.math.BigDecimal
import java.util.UUID

@GraphQLDescription("Input for the createBooking mutation.")
data class CreateBookingInput(
    @GraphQLDescription("Identifier of the property to book.")
    val propertyId: UUID,

    @GraphQLDescription("Identifier of the user placing the booking.")
    val userId: UUID,

    @GraphQLDescription("Stay start date in ISO-8601 format (YYYY-MM-DD).")
    val checkIn: String,

    @GraphQLDescription("Stay end date in ISO-8601 format (YYYY-MM-DD).")
    val checkOut: String,

    @GraphQLDescription("Total amount charged including taxes and discounts.")
    val totalAmount: BigDecimal,

    @GraphQLDescription("ISO 4217 currency code (e.g. USD).")
    val currency: String = "USD",

    @GraphQLDescription(
        "Client-supplied idempotency key. The same key always returns the same booking; " +
            "duplicate submissions are safe and never produce duplicate bookings.",
    )
    val idempotencyKey: String,
)

@GraphQLDescription("Input for the cancelBooking mutation.")
data class CancelBookingInput(
    @GraphQLDescription("Identifier of the booking to cancel.")
    val bookingId: UUID,
)
