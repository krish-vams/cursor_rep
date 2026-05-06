package com.travelgraph.property.service

import com.travelgraph.property.domain.PropertyEntity
import com.travelgraph.property.domain.PropertyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PropertyService(
    private val repository: PropertyRepository,
) {

    @Transactional(readOnly = true)
    suspend fun findById(id: UUID): PropertyEntity? = withContext(Dispatchers.IO) {
        repository.findById(id).orElse(null)
    }

    @Transactional(readOnly = true)
    suspend fun findAllByIds(ids: Collection<UUID>): Map<UUID, PropertyEntity> = withContext(Dispatchers.IO) {
        repository.findAllByIdIn(ids).associateBy { it.id }
    }

    @Transactional(readOnly = true)
    suspend fun searchByCity(city: String, limit: Int): List<PropertyEntity> = withContext(Dispatchers.IO) {
        // Bound the page size defensively to prevent client-driven over-fetch.
        val safeLimit = limit.coerceIn(1, 100)
        repository.searchByCity(city, PageRequest.of(0, safeLimit))
    }
}
