package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Mutation result for `createBooking`. Per the Phase 1.4 spec the union is
 * `Booking | BookingConflictError | PropertyUnavailableError`, so [Booking] itself
 * is a union member alongside the two error types.
 *
 * graphql-kotlin generates the GraphQL union from this Kotlin sealed interface.
 */
@GraphQLDescription(
    "Result of attempting to create a booking. Either a Booking on success, a " +
        "BookingConflictError when the dates conflict with an existing confirmed booking, " +
        "or a PropertyUnavailableError when the property cannot be booked.",
)
sealed interface CreateBookingPayload

@GraphQLDescription("Couldn't create the booking because another confirmed booking overlaps these dates.")
data class BookingConflictError(
    @GraphQLDescription("Human-readable message describing the conflict.")
    val message: String,

    @GraphQLDescription("Identifier of the existing booking that overlaps the requested range.")
    val conflictingBookingId: UUID,
) : CreateBookingPayload

@GraphQLDescription("Couldn't create the booking because the property cannot accept reservations.")
data class PropertyUnavailableError(
    @GraphQLDescription("Human-readable message describing why the property is unavailable.")
    val message: String,

    @GraphQLDescription("Identifier of the property that is unavailable.")
    val propertyId: UUID,
) : CreateBookingPayload
