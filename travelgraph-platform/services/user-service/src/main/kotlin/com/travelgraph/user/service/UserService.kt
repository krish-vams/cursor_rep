package com.travelgraph.user.service

import com.travelgraph.user.domain.UserEntity
import com.travelgraph.user.domain.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val repository: UserRepository,
) {
    @Transactional(readOnly = true)
    suspend fun findById(id: UUID): UserEntity? = withContext(Dispatchers.IO) {
        repository.findById(id).orElse(null)
    }

    @Transactional(readOnly = true)
    suspend fun findAllByIds(ids: Collection<UUID>): Map<UUID, UserEntity> = withContext(Dispatchers.IO) {
        repository.findAllByIdIn(ids).associateBy { it.id }
    }
}
