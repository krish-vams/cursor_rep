package com.travelgraph.booking.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import com.travelgraph.booking.service.BookingService
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

@Component
class BookingQueries(
    private val bookingService: BookingService,
) : Query {

    @GraphQLDescription("List all bookings for a given user, newest stay first.")
    suspend fun bookings(
        @GraphQLDescription("Identifier of the user whose bookings are being requested.")
        userId: UUID,
    ): List<Booking> =
        bookingService.findByUser(userId).map(Booking.Companion::fromEntity)

    @GraphQLDescription(
        "Return the room types currently available at a property for the given date range. " +
            "In Phase 1 this is computed from a synthetic catalogue; later phases will federate " +
            "with a real inventory service.",
    )
    suspend fun availableRooms(
        @GraphQLDescription("Identifier of the property to check availability for.")
        propertyId: UUID,
        @GraphQLDescription("Stay start date in ISO-8601 format (YYYY-MM-DD).")
        checkIn: String,
        @GraphQLDescription("Stay end date in ISO-8601 format (YYYY-MM-DD).")
        checkOut: String,
    ): List<RoomType> =
        bookingService.availableRooms(
            propertyId = propertyId,
            checkIn = LocalDate.parse(checkIn),
            checkOut = LocalDate.parse(checkOut),
        ).map { rt ->
            RoomType(
                id = rt.id,
                name = rt.name,
                description = rt.description,
                maxOccupancy = rt.maxOccupancy,
                basePrice = rt.basePrice,
                available = rt.available,
            )
        }
}
