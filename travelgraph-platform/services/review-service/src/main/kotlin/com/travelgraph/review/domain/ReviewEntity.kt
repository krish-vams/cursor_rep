package com.travelgraph.review.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(
    name = "reviews",
    schema = "review_schema",
    uniqueConstraints = [UniqueConstraint(name = "reviews_user_property_unique", columnNames = ["user_id", "property_id"])],
)
class ReviewEntity(
    @Id
    @Column(nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "property_id", nullable = false)
    val propertyId: UUID,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(nullable = false)
    val rating: Short,

    @Column(nullable = false, columnDefinition = "text")
    val comment: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
