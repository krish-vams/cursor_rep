package com.travelgraph.user.graphql

import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.federation.FederatedSchemaGeneratorConfig
import com.expediagroup.graphql.generator.federation.FederatedSchemaGeneratorHooks
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import com.expediagroup.graphql.generator.federation.toFederatedSchema
import com.expediagroup.graphql.server.operations.Mutation
import com.expediagroup.graphql.server.operations.Query
import com.expediagroup.graphql.server.operations.Subscription
import graphql.Scalars
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import graphql.schema.visibility.BlockedFields
import graphql.schema.visibility.GraphqlFieldVisibility
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import kotlin.reflect.KType

@Configuration
class GraphQLConfiguration(
    @Value("\${graphql.introspection.enabled:false}")
    private val introspectionEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(GraphQLConfiguration::class.java)

    @Bean
    fun schemaConfig(resolvers: List<FederatedTypeResolver>): SchemaGeneratorConfig {
        val hooks = object : FederatedSchemaGeneratorHooks(resolvers, optInFederationV2 = true) {
            override fun willGenerateGraphQLType(type: KType): GraphQLType? = when (type.classifier) {
                UUID::class -> Scalars.GraphQLID
                else -> super.willGenerateGraphQLType(type)
            }
        }
        return FederatedSchemaGeneratorConfig(
            supportedPackages = listOf("com.travelgraph.user"),
            hooks = hooks,
        )
    }

    @Bean
    fun schema(
        config: SchemaGeneratorConfig,
        queries: List<Query>,
        mutations: List<Mutation>,
        subscriptions: List<Subscription>,
    ): GraphQLSchema {
        val q = queries.map { TopLevelObject(it) }
        val m = mutations.map { TopLevelObject(it) }
        val s = subscriptions.map { TopLevelObject(it) }
        return toFederatedSchema(config as FederatedSchemaGeneratorConfig, q, m, s)
    }

    @Bean
    fun introspectionGuard(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
            if (bean !is GraphQLSchema) return bean
            return if (introspectionEnabled) {
                log.warn("GraphQL introspection is ENABLED. This must only be true in dev/test profiles.")
                bean
            } else {
                log.info("GraphQL introspection is DISABLED (production default).")
                val blocked: GraphqlFieldVisibility = BlockedFields.newBlock()
                    .addPattern("__schema.*")
                    .addPattern("__type.*")
                    .addPattern("__Schema.*")
                    .addPattern("__Type.*")
                    .build()
                val newRegistry = bean.codeRegistry.transform { it.fieldVisibility(blocked) }
                GraphQLSchema.newSchema(bean).codeRegistry(newRegistry).build()
            }
        }
    }
}
