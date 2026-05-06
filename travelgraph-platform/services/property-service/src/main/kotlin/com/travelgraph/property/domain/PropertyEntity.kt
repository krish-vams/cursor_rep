package com.travelgraph.property.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "properties", schema = "property_schema")
class PropertyEntity(
    @Id
    @Column(nullable = false, updatable = false)
    val id: UUID,

    @Column(nullable = false, length = 200)
    var name: String,

    @Column(nullable = false, columnDefinition = "text")
    var description: String,

    @Column(nullable = false, length = 300)
    var location: String,

    @Column(nullable = false, length = 100)
    var city: String,

    @Column(nullable = false, length = 100)
    var country: String,

    @Column(nullable = false)
    var rating: Float,

    @Column(nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    var amenities: List<String> = emptyList(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
