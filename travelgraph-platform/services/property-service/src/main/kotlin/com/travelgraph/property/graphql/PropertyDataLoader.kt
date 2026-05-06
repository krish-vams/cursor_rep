package com.travelgraph.property.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.travelgraph.property.service.PropertyService
import graphql.GraphQLContext
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

@Component
class PropertyDataLoader(
    private val propertyService: PropertyService,
) : KotlinDataLoader<UUID, Property?> {

    override val dataLoaderName: String = NAME

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, Property?> {
        val batchLoader = BatchLoader<UUID, Property?> { ids ->
            // graphql-kotlin requires the result list to align positionally with the input ids.
            CompletableFuture.supplyAsync {
                runBlocking {
                    val byId = propertyService.findAllByIds(ids)
                    ids.map { id -> byId[id]?.let(Property.Companion::fromEntity) }
                }
            }
        }
        return DataLoaderFactory.newDataLoader(
            batchLoader,
            DataLoaderOptions.newOptions().setCachingEnabled(true).setBatchingEnabled(true),
        )
    }

    companion object {
        const val NAME: String = "PropertyDataLoader"
    }
}
