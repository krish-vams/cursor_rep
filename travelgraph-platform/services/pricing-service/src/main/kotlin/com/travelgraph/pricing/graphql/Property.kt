package com.travelgraph.pricing.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.travelgraph.pricing.service.LoyaltyTier
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.await
import java.time.LocalDate
import java.util.UUID

/**
 * Federation-extending stub for the `Property` entity. Pricing-service does not OWN Property;
 * property-service does. This stub declares the contributed field [price] and implements
 * `_entities` resolution so that the router can call back here with property IDs to populate
 * `price(...)` on a Property fetched from property-service.
 *
 * In federation v2 syntax, no `@extends` or `@external` directives are required -- declaring
 * `@key(fields: "id")` on a non-owning subgraph and contributing additional fields is enough.
 */
@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription(
    "Pricing-service contribution to the federated Property entity. Owned by property-service; " +
        "this subgraph adds the `price(...)` field.",
)
data class Property(
    @GraphQLDescription("Stable unique identifier for the property (key).")
    val id: UUID,
) {

    /**
     * Price quote for a stay at this property. Routes through [PriceDataLoader] so that an
     * `_entities(representations: [...])` call covering N properties produces ONE batched
     * pricing query, not N.
     */
    @GraphQLDescription(
        "Quote the price for a stay at this property. Defaults to a one-night stay starting today " +
            "if checkIn or checkOut is omitted. Weekend, holiday, and loyalty rules are applied " +
            "at query time and the rule list is returned via Price.appliedRules.",
    )
    suspend fun price(
        @GraphQLDescription("Stay start date in ISO-8601 format (YYYY-MM-DD). Optional.")
        checkIn: String? = null,
        @GraphQLDescription("Stay end date in ISO-8601 format (YYYY-MM-DD). Optional.")
        checkOut: String? = null,
        @GraphQLDescription("Loyalty tier of the requesting guest. Defaults to NONE.")
        loyaltyTier: LoyaltyTier = LoyaltyTier.NONE,
        env: DataFetchingEnvironment,
    ): Price? {
        val key = PriceLoaderKey(
            propertyId = id,
            checkIn = checkIn?.let(LocalDate::parse),
            checkOut = checkOut?.let(LocalDate::parse),
            loyalty = loyaltyTier,
        )
        return env.getDataLoader<PriceLoaderKey, Price?>(PriceDataLoader.NAME).load(key).await()
    }
}
