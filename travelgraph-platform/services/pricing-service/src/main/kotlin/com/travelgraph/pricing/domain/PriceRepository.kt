package com.travelgraph.pricing.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface PriceRepository : JpaRepository<PriceEntity, UUID> {
    fun findAllByPropertyIdIn(ids: Collection<UUID>): List<PriceEntity>
}
