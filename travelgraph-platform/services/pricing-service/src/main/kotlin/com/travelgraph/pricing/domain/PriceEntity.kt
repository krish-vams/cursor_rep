package com.travelgraph.pricing.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class Season { STANDARD, PEAK, SHOULDER, OFF }

@Entity
@Table(name = "prices", schema = "pricing_schema")
class PriceEntity(
    @Id
    @Column(name = "property_id", nullable = false, updatable = false)
    val propertyId: UUID,

    @Column(name = "base_price", nullable = false, precision = 10, scale = 2)
    var basePrice: BigDecimal,

    @Column(name = "tax_rate", nullable = false, precision = 6, scale = 4)
    var taxRate: BigDecimal,

    @Column(nullable = false, length = 3)
    var currency: String = "USD",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var season: Season = Season.STANDARD,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
