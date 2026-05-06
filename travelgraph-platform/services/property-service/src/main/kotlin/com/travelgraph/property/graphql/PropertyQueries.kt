package com.travelgraph.property.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import com.travelgraph.property.service.PropertyService
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Component
class PropertyQueries(
    private val propertyService: PropertyService,
) : Query {

    @GraphQLDescription("Look up a single property by its identifier. Returns null if no property exists.")
    fun property(
        @GraphQLDescription("Identifier of the property to look up.")
        id: UUID,
        env: DataFetchingEnvironment,
    ): CompletableFuture<Property?> {
        // Delegate to the DataLoader so multiple `property(id: ...)` lookups in a single
        // operation are coalesced into one repository call.
        return env.getDataLoader<UUID, Property?>(PropertyDataLoader.NAME).load(id)
    }

    @GraphQLDescription("Search for properties in a given city, ordered by rating then name.")
    suspend fun searchProperties(
        @GraphQLDescription("City to search within. Match is case-insensitive.")
        city: String,
        @GraphQLDescription("Maximum number of results to return (1-100). Defaults to 20.")
        limit: Int = 20,
    ): List<Property> =
        propertyService.searchByCity(city, limit).map(Property.Companion::fromEntity)
}
