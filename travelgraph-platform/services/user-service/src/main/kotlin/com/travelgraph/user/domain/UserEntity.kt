package com.travelgraph.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.util.UUID

enum class LoyaltyStatus { BRONZE, SILVER, GOLD, PLATINUM }

@Entity
@Table(name = "users", schema = "user_schema")
class UserEntity(
    @Id
    @Column(nullable = false, updatable = false)
    val id: UUID,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(nullable = false, length = 320, unique = true)
    var email: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "loyalty_status", nullable = false, length = 20)
    var loyaltyStatus: LoyaltyStatus,

    @Column(name = "preferred_currency", nullable = false, length = 3)
    var preferredCurrency: String = "USD",

    @Column(name = "saved_property_ids", nullable = false, columnDefinition = "uuid[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    var savedPropertyIds: List<UUID> = emptyList(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
