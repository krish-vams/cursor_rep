package com.travelgraph.user.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findAllByIdIn(ids: Collection<UUID>): List<UserEntity>
    fun findByEmail(email: String): UserEntity?
}
