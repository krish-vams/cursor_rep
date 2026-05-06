package com.travelgraph.pricing.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import com.travelgraph.pricing.service.LoyaltyTier
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Component
class PriceQueries : Query {

    @GraphQLDescription(
        "Quote the price for a stay at a property. If checkIn or checkOut is omitted a one-night " +
            "quote starting today is returned. Weekend, holiday, and loyalty rules are applied at " +
            "query time and the rule list is returned via Price.appliedRules.",
    )
    fun price(
        @GraphQLDescription("Identifier of the property to price.")
        propertyId: UUID,
        @GraphQLDescription("Stay start date in ISO-8601 format (YYYY-MM-DD). Optional.")
        checkIn: String? = null,
        @GraphQLDescription("Stay end date in ISO-8601 format (YYYY-MM-DD). Optional.")
        checkOut: String? = null,
        @GraphQLDescription("Loyalty tier of the requesting guest. Defaults to NONE.")
        loyaltyTier: LoyaltyTier = LoyaltyTier.NONE,
        env: DataFetchingEnvironment,
    ): CompletableFuture<Price?> {
        val key = PriceLoaderKey(
            propertyId = propertyId,
            checkIn = checkIn?.let(LocalDate::parse),
            checkOut = checkOut?.let(LocalDate::parse),
            loyalty = loyaltyTier,
        )
        return env.getDataLoader<PriceLoaderKey, Price?>(PriceDataLoader.NAME).load(key)
    }
}
