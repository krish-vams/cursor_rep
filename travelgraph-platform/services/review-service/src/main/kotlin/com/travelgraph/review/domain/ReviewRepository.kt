package com.travelgraph.review.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ReviewRepository : JpaRepository<ReviewEntity, UUID> {

    fun findAllByPropertyIdOrderByCreatedAtDesc(propertyId: UUID, pageable: Pageable): List<ReviewEntity>

    fun findAllByPropertyIdInOrderByCreatedAtDesc(propertyIds: Collection<UUID>): List<ReviewEntity>

    fun findByUserIdAndPropertyId(userId: UUID, propertyId: UUID): ReviewEntity?

    /** Aggregates: per-property (count, average) pulled in one query. */
    @Query(
        """
        SELECT new com.travelgraph.review.domain.ReviewAggregate(
            r.propertyId,
            COUNT(r),
            COALESCE(AVG(r.rating), 0.0)
        )
        FROM ReviewEntity r
        WHERE r.propertyId IN :propertyIds
        GROUP BY r.propertyId
        """,
    )
    fun aggregateByPropertyIds(@Param("propertyIds") propertyIds: Collection<UUID>): List<ReviewAggregate>
}

data class ReviewAggregate(
    val propertyId: UUID,
    val count: Long,
    val average: Double,
)
