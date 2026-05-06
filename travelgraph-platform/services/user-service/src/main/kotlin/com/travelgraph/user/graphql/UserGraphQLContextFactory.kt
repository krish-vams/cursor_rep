package com.travelgraph.user.graphql

import com.expediagroup.graphql.server.spring.execution.SpringGraphQLContextFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * Builds the per-request GraphQL context for user-service.
 *
 * For Phase 1 the only thing we surface is the caller's identity, taken from the
 * `x-user-id` request header. Real authentication arrives in Phase 5 (JWT at the
 * router edge with identity propagation to subgraphs); until then this header
 * stand-in is enough to drive the `me` query and exercise context plumbing.
 *
 * The header value is exposed under [GraphQLContextKeys.X_USER_ID] and consumed by
 * [UserQueries.me].
 */
@Component
class UserGraphQLContextFactory : SpringGraphQLContextFactory() {

    override suspend fun generateContextMap(request: ServerRequest): Map<*, Any> {
        val userId = request.headers().firstHeader(GraphQLContextKeys.X_USER_ID_HEADER)
        return if (userId.isNullOrBlank()) {
            emptyMap<String, Any>()
        } else {
            mapOf(GraphQLContextKeys.X_USER_ID to userId)
        }
    }
}

object GraphQLContextKeys {
    /** Wire-format header name the gateway / clients send. */
    const val X_USER_ID_HEADER: String = "x-user-id"

    /** Internal key used to stash the caller's user id in the GraphQL context map. */
    const val X_USER_ID: String = "callerUserId"
}
