package com.travelgraph.property.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.travelgraph.property.domain.PropertyEntity
import java.util.UUID

@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("A travel property such as a hotel, vacation rental, or motel.")
data class Property(
    @GraphQLDescription("Stable unique identifier for the property.")
    val id: UUID,

    @GraphQLDescription("Display name of the property.")
    val name: String,

    @GraphQLDescription("Long-form marketing description.")
    val description: String,

    @GraphQLDescription("Street-level address of the property.")
    val location: String,

    @GraphQLDescription("City the property is located in.")
    val city: String,

    @GraphQLDescription("Country the property is located in.")
    val country: String,

    @GraphQLDescription("Average guest rating on a 0.0 to 5.0 scale.")
    val rating: Float,

    @GraphQLDescription("Amenities offered at this property (e.g. pool, gym).")
    val amenities: List<String>,
) {
    companion object {
        fun fromEntity(e: PropertyEntity): Property = Property(
            id = e.id,
            name = e.name,
            description = e.description,
            location = e.location,
            city = e.city,
            country = e.country,
            rating = e.rating,
            amenities = e.amenities,
        )
    }
}
