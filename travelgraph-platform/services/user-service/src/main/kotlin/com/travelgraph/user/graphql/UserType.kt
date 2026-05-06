package com.travelgraph.user.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import com.travelgraph.user.domain.LoyaltyStatus
import com.travelgraph.user.domain.UserEntity
import java.util.UUID

@GraphQLDescription("Loyalty programme tier for a user. Higher tiers receive larger discounts.")
enum class LoyaltyStatusGql {
    @GraphQLDescription("Entry-level tier. No loyalty discount.")
    BRONZE,

    @GraphQLDescription("Mid-tier. 5% loyalty discount in pricing-service.")
    SILVER,

    @GraphQLDescription("Upper-tier. 10% loyalty discount in pricing-service.")
    GOLD,

    @GraphQLDescription("Top-tier. 15% loyalty discount in pricing-service.")
    PLATINUM,
    ;

    companion object {
        fun fromDomain(s: LoyaltyStatus): LoyaltyStatusGql = when (s) {
            LoyaltyStatus.BRONZE -> BRONZE
            LoyaltyStatus.SILVER -> SILVER
            LoyaltyStatus.GOLD -> GOLD
            LoyaltyStatus.PLATINUM -> PLATINUM
        }
    }
}

@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("A registered guest of the TravelGraph platform.")
data class User(
    @GraphQLDescription("Stable unique identifier of the user.")
    val id: UUID,

    @GraphQLDescription("Display name (full name).")
    val name: String,

    @GraphQLDescription("Email address. Globally unique within the platform.")
    val email: String,

    @GraphQLDescription("Current loyalty tier.")
    val loyaltyStatus: LoyaltyStatusGql,

    @GraphQLDescription("ISO 4217 currency code the user prefers prices to be displayed in.")
    val preferredCurrency: String,

    @GraphQLDescription("Property IDs the user has saved to their wishlist.")
    val savedPropertyIds: List<UUID>,
) {
    companion object {
        fun fromEntity(e: UserEntity): User = User(
            id = e.id,
            name = e.name,
            email = e.email,
            loyaltyStatus = LoyaltyStatusGql.fromDomain(e.loyaltyStatus),
            preferredCurrency = e.preferredCurrency,
            savedPropertyIds = e.savedPropertyIds,
        )
    }
}
