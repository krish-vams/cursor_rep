package com.travelgraph.user.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import com.travelgraph.user.service.UserService
import graphql.schema.DataFetchingEnvironment
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Component
class UserQueries(
    private val userService: UserService,
) : Query {

    @GraphQLDescription("Look up a single user by their identifier. Returns null if no user exists with that id.")
    fun user(
        @GraphQLDescription("Identifier of the user to look up.")
        id: UUID,
        env: DataFetchingEnvironment,
    ): CompletableFuture<User?> =
        env.getDataLoader<UUID, User?>(UserDataLoader.NAME).load(id)

    @GraphQLDescription(
        "Return the user identified by the request context. In Phase 1 the identity is read " +
            "from the `x-user-id` header; in Phase 5 it will be derived from a verified JWT at " +
            "the router edge. Returns null if no header was supplied or the referenced user does " +
            "not exist.",
    )
    suspend fun me(env: DataFetchingEnvironment): User? {
        val rawId = env.graphQlContext.get<String?>(GraphQLContextKeys.X_USER_ID) ?: return null
        val parsed = runCatching { UUID.fromString(rawId) }.getOrNull() ?: return null
        return userService.findById(parsed)?.let(User.Companion::fromEntity)
    }
}
