package com.travelgraph.review.graphql

import com.expediagroup.graphql.dataloader.KotlinDataLoader
import com.travelgraph.review.service.ReviewService
import graphql.GraphQLContext
import kotlinx.coroutines.runBlocking
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderFactory
import org.dataloader.DataLoaderOptions
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Batches `reviews(propertyId: ...)` lookups so multiple property nodes in a single
 * operation share one repository call. The loader returns a list per property id.
 */
@Component
class ReviewsByPropertyDataLoader(
    private val reviewService: ReviewService,
) : KotlinDataLoader<UUID, List<Review>> {

    override val dataLoaderName: String = NAME

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, List<Review>> {
        val batchLoader = BatchLoader<UUID, List<Review>> { ids ->
            CompletableFuture.supplyAsync {
                runBlocking {
                    val byProperty = reviewService.listByProperties(ids)
                    ids.map { id ->
                        byProperty[id]?.map(Review.Companion::fromEntity) ?: emptyList()
                    }
                }
            }
        }
        return DataLoaderFactory.newDataLoader(
            batchLoader,
            DataLoaderOptions.newOptions().setCachingEnabled(true).setBatchingEnabled(true),
        )
    }

    companion object {
        const val NAME: String = "ReviewsByPropertyDataLoader"
    }
}

/** Batches `reviewSummary(propertyId: ...)` lookups. */
@Component
class ReviewSummaryDataLoader(
    private val reviewService: ReviewService,
) : KotlinDataLoader<UUID, ReviewSummary> {

    override val dataLoaderName: String = NAME

    override fun getDataLoader(graphQLContext: GraphQLContext): DataLoader<UUID, ReviewSummary> {
        val batchLoader = BatchLoader<UUID, ReviewSummary> { ids ->
            CompletableFuture.supplyAsync {
                runBlocking {
                    val byId = reviewService.summariseByProperties(ids)
                    ids.map { id ->
                        val agg = byId.getValue(id)
                        ReviewSummary(
                            propertyId = agg.propertyId,
                            average = agg.average,
                            count = agg.count.toInt(),
                        )
                    }
                }
            }
        }
        return DataLoaderFactory.newDataLoader(
            batchLoader,
            DataLoaderOptions.newOptions().setCachingEnabled(true).setBatchingEnabled(true),
        )
    }

    companion object {
        const val NAME: String = "ReviewSummaryDataLoader"
    }
}
