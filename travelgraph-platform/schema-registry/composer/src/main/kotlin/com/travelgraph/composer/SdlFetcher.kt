package com.travelgraph.composer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches a subgraph's federated SDL by issuing the standard
 * `query { _service { sdl } }` query against its `/graphql` endpoint.
 *
 * This is the canonical federation discovery mechanism: every Phase 3.1 subgraph implements
 * `_service` because graphql-kotlin generates it when the schema opts into federation.
 */
class SdlFetcher(timeoutMs: Long) {

    private val log = LoggerFactory.getLogger(SdlFetcher::class.java)
    private val mapper = ObjectMapper().registerModule(kotlinModule())
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()
    private val timeout = Duration.ofMillis(timeoutMs)

    fun fetch(entry: SubgraphEntry): String {
        val payload = """{"query":"query SdlIntrospection { _service { sdl } }","operationName":"SdlIntrospection"}"""
        val req = HttpRequest.newBuilder(URI.create(entry.url))
            .timeout(timeout)
            .header("content-type", "application/json")
            .header("accept", "application/json")
            .header("apollographql-client-name", "travelgraph-composer")
            .header("apollographql-client-version", "0.1.0")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        log.info("fetching SDL for subgraph '{}' from {}", entry.name, entry.url)
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(res.statusCode() == 200) {
            "subgraph '${entry.name}' returned HTTP ${res.statusCode()} from ${entry.url}: ${res.body().take(500)}"
        }

        val parsed: GraphQLResponse = mapper.readValue(res.body())
        if (parsed.errors != null && parsed.errors.isNotEmpty()) {
            throw IllegalStateException(
                "subgraph '${entry.name}' returned GraphQL errors when querying _service.sdl: " +
                    parsed.errors.joinToString("; ") { it.message },
            )
        }
        val sdl = parsed.data?.get("_service")?.get("sdl")?.asText()
            ?: throw IllegalStateException(
                "subgraph '${entry.name}' did not return data._service.sdl. Did you enable federation?",
            )
        log.info("subgraph '{}' SDL fetched ({} bytes)", entry.name, sdl.length)
        return sdl
    }
}

private data class GraphQLResponse(
    val data: Map<String, com.fasterxml.jackson.databind.JsonNode>?,
    val errors: List<GraphQLError>?,
)

private data class GraphQLError(
    val message: String,
    val path: List<Any>? = null,
    val extensions: Map<String, Any>? = null,
)
