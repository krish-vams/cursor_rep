package com.travelgraph.property.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PropertyRepository : JpaRepository<PropertyEntity, UUID> {

    @Query(
        """
        SELECT p FROM PropertyEntity p
        WHERE LOWER(p.city) = LOWER(:city)
        ORDER BY p.rating DESC, p.name ASC
        """,
    )
    fun searchByCity(@Param("city") city: String, pageable: org.springframework.data.domain.Pageable): List<PropertyEntity>

    fun findAllByIdIn(ids: Collection<UUID>): List<PropertyEntity>
}
