package com.travelgraph.pricing.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.travelgraph.pricing.service.PriceQuote
import java.math.BigDecimal
import java.util.UUID

@GraphQLDescription("Computed price quote for a stay at a property.")
data class Price(
    @GraphQLDescription("Identifier of the property this quote applies to.")
    val propertyId: UUID,

    @GraphQLDescription("Per-night charge after weekend and holiday uplifts but before loyalty discount.")
    val amount: BigDecimal,

    @GraphQLDescription("ISO 4217 currency code (e.g. USD).")
    val currency: String,

    @GraphQLDescription("Total tax charged across the full stay.")
    val taxes: BigDecimal,

    @GraphQLDescription("Loyalty discount applied to the stay subtotal (zero if no tier).")
    val discount: BigDecimal,

    @GraphQLDescription("Final amount paid by the guest including taxes and discounts.")
    val totalAmount: BigDecimal,

    @GraphQLDescription("Number of nights covered by this quote.")
    val nights: Int,

    @GraphQLDescription("Human-readable list of pricing rules that affected this quote.")
    val appliedRules: List<String>,
) {
    companion object {
        fun fromQuote(q: PriceQuote): Price = Price(
            propertyId = q.propertyId,
            amount = q.amount,
            currency = q.currency,
            taxes = q.taxes,
            discount = q.discount,
            totalAmount = q.totalAmount,
            nights = q.nights,
            appliedRules = q.appliedRules,
        )
    }
}
