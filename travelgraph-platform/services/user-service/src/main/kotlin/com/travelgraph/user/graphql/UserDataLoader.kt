package com.travelgraph.user.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.travelgraph.user.service.UserService
import graphql.GraphQLContext
import kotlinx.coroutines.runBlocking
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Component
class UserDataLoader(
    private val userService: UserService,
) : KotlinDataLoader<UUID, User?> {

    override val dataLoaderName: String = NAME

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, User?> {
        val batchLoader = BatchLoader<UUID, User?> { ids ->
            CompletableFuture.supplyAsync {
                runBlocking {
                    val byId = userService.findAllByIds(ids)
                    ids.map { id -> byId[id]?.let(User.Companion::fromEntity) }
                }
            }
        }
        return DataLoaderFactory.newDataLoader(
            batchLoader,
            DataLoaderOptions.newOptions().setCachingEnabled(true).setBatchingEnabled(true),
        )
    }

    companion object {
        const val NAME: String = "UserDataLoader"
    }
}
