package com.travelgraph.property.graphql

import com.expediagroup.graphql.generator.hooks.SchemaGeneratorHooks
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

/**
 * GraphQL configuration for property-service.
 *
 * Disables introspection unless `graphql.introspection.enabled=true` is set
 * (only true in the `dev` profile per `application-dev.yml`).
 *
 * Implementation note: graphql-kotlin auto-configures the `GraphQLSchema` bean.
 * We post-process that bean to layer a [BlockedFields] field visibility on top of
 * the existing code registry so `__schema` / `__type` queries are rejected.
 */
@Configuration
class GraphQLConfiguration(
    @Value("\${graphql.introspection.enabled:false}")
    private val introspectionEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(GraphQLConfiguration::class.java)

    /**
     * Map Kotlin [UUID] to the GraphQL `ID` scalar so cross-service identifiers match the
     * shared GraphQL conventions (`docs/graphql-conventions.md` rule TG-007 + TG-500). Without
     * this hook graphql-kotlin generates a custom `UUID` scalar instead of `ID`.
     */
    @Bean
    fun schemaGeneratorHooks(): SchemaGeneratorHooks = object : SchemaGeneratorHooks {
        override fun willGenerateGraphQLType(type: KType): GraphQLType? = when (type.classifier) {
            UUID::class -> Scalars.GraphQLID
            else -> null
        }
    }

    @Bean
    fun introspectionGuard(): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any? {
            if (bean !is GraphQLSchema) return bean
            return if (introspectionEnabled) {
                log.warn(
                    "GraphQL introspection is ENABLED. This must only be true in dev/test profiles. " +
                        "Persisted queries are the production contract.",
                )
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
