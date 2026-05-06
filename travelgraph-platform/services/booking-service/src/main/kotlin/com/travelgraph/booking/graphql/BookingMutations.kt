package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import com.travelgraph.booking.service.BookingService
import com.travelgraph.booking.service.CancelBookingOutcome
import com.travelgraph.booking.service.CreateBookingOutcome
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class BookingMutations(
    private val bookingService: BookingService,
) : Mutation {

    @GraphQLDescription(
        "Create a booking. Idempotent on `input.idempotencyKey`: submitting the same key " +
            "returns the existing booking and never creates a duplicate. Returns a union " +
            "describing the outcome.",
    )
    suspend fun createBooking(
        @GraphQLDescription("Booking creation input.")
        input: CreateBookingInput,
    ): CreateBookingPayload {
        val outcome = bookingService.createBooking(
            propertyId = input.propertyId,
            userId = input.userId,
            checkIn = LocalDate.parse(input.checkIn),
            checkOut = LocalDate.parse(input.checkOut),
            totalAmount = input.totalAmount,
            currency = input.currency,
            idempotencyKey = input.idempotencyKey,
        )
        return when (outcome) {
            is CreateBookingOutcome.Created -> Booking.fromEntity(outcome.booking)
            is CreateBookingOutcome.Conflict -> BookingConflictError(
                message = outcome.message,
                conflictingBookingId = outcome.conflictingBookingId,
            )
            is CreateBookingOutcome.PropertyUnavailable -> PropertyUnavailableError(
                message = outcome.message,
                propertyId = outcome.propertyId,
            )
        }
    }

    @GraphQLDescription(
        "Cancel an existing booking. Returns a union with the cancelled Booking on success, " +
            "BookingNotFoundError if no booking exists with the supplied id, or " +
            "BookingAlreadyCancelledError if the booking was already cancelled.",
    )
    suspend fun cancelBooking(
        @GraphQLDescription("Cancellation input.")
        input: CancelBookingInput,
    ): CancelBookingPayload {
        return when (val outcome = bookingService.cancelBooking(input.bookingId)) {
            is CancelBookingOutcome.Cancelled -> Booking.fromEntity(outcome.booking)
            is CancelBookingOutcome.NotFound -> BookingNotFoundError(
                message = "No booking exists with id ${outcome.bookingId}.",
                bookingId = outcome.bookingId,
            )
            is CancelBookingOutcome.AlreadyCancelled -> BookingAlreadyCancelledError(
                message = "Booking ${outcome.booking.id} was already cancelled.",
                bookingId = outcome.booking.id,
            )
        }
    }
}
