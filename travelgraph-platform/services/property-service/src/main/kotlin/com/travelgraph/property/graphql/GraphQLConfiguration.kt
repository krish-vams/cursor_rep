package com.travelgraph.property.graphql

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

/**
 * GraphQL configuration for property-service.
 *
 * Phase 3 wires graphql-kotlin federation: the generated schema includes `_service { sdl }` and
 * `_entities(representations: [_Any!]!): [_Entity]!`, and the [Property] type carries a federation
 * `@key(fields: "id")` directive. The Phase 2 hand-rolled router consumes that SDL via the new
 * Phase 3.2 composer.
 *
 * Introspection (`__schema`, `__type`, etc.) is still gated by `graphql.introspection.enabled`
 * (true only in the `dev` profile). Federation queries (`_service`, `_entities`) are NOT
 * introspection and remain available in every profile -- that is how the supergraph composer
 * (Phase 3.2) and router planner (Phase 3.3) discover the schema.
 */
@Configuration
class GraphQLConfiguration(
    @Value("\${graphql.introspection.enabled:false}")
    private val introspectionEnabled: Boolean,
) {

    private val log = LoggerFactory.getLogger(GraphQLConfiguration::class.java)

    /**
     * Federated schema generator config. Replaces the auto-configured non-federated config
     * provided by `graphql-kotlin-spring-server`. We keep the `UUID` -> `ID` scalar mapping
     * (rule TG-007 in `docs/graphql-conventions.md`) by composing our hook on top of the
     * federated hooks.
     */
    @Bean
    fun schemaConfig(resolvers: List<FederatedTypeResolver>): SchemaGeneratorConfig {
        val hooks = object : FederatedSchemaGeneratorHooks(resolvers, optInFederationV2 = true) {
            override fun willGenerateGraphQLType(type: KType): GraphQLType? = when (type.classifier) {
                UUID::class -> Scalars.GraphQLID
                else -> super.willGenerateGraphQLType(type)
            }
        }
        return FederatedSchemaGeneratorConfig(
            supportedPackages = listOf("com.travelgraph.property"),
            hooks = hooks,
        )
    }

    /**
     * Override the auto-configured `schema` bean to call [toFederatedSchema] instead of the
     * default non-federated `toSchema`. This is what wires `_service` / `_entities` into
     * generation.
     */
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

    /**
     * After the schema bean is created, layer a [BlockedFields] visibility on top so that
     * `__schema` / `__type` queries are rejected when introspection is disabled. We
     * deliberately do NOT block `_service` or `_entities` -- those are the federation
     * contract surface and must always be reachable.
     */
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
