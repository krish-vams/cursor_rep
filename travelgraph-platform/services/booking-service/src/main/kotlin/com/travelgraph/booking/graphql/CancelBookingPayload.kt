package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Mutation result for `cancelBooking`. Mirrors the createBooking pattern: the
 * Booking is a union member on success and there are explicit error types for
 * the documented failure modes.
 */
@GraphQLDescription(
    "Result of attempting to cancel a booking. Either the cancelled Booking, a " +
        "BookingNotFoundError when no booking exists with that id, or a " +
        "BookingAlreadyCancelledError when the booking was already in CANCELLED state.",
)
sealed interface CancelBookingPayload

@GraphQLDescription("Couldn't cancel the booking because no booking exists with the supplied id.")
data class BookingNotFoundError(
    @GraphQLDescription("Human-readable message.")
    val message: String,

    @GraphQLDescription("Identifier that was looked up.")
    val bookingId: UUID,
) : CancelBookingPayload

@GraphQLDescription("Couldn't cancel the booking because it was already in CANCELLED state.")
data class BookingAlreadyCancelledError(
    @GraphQLDescription("Human-readable message.")
    val message: String,

    @GraphQLDescription("Identifier of the booking that was already cancelled.")
    val bookingId: UUID,
) : CancelBookingPayload
