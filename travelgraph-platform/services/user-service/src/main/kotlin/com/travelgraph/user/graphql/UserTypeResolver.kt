package com.travelgraph.user.graphql

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Federated type resolver for the [User] entity. Batches across all representations in a single
 * `_entities` call by routing every lookup through [UserDataLoader].
 */
@Component
class UserTypeResolver : FederatedTypeSuspendResolver<User> {

    override val typeName: String = "User"

    override suspend fun resolve(
        environment: DataFetchingEnvironment,
        representation: Map<String, Any>,
    ): User? {
        val raw = representation["id"] as? String ?: return null
        val id = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
        return environment
            .getDataLoader<UUID, User?>(UserDataLoader.NAME)
            .load(id)
            .await()
    }
}
