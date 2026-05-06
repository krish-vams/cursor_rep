package com.travelgraph.composer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Entry point for the supergraph composer CLI.
 *
 * ```
 *   travelgraph-composer [path/to/composer-config.yaml]
 * ```
 *
 * Behavior:
 *  1. Loads the YAML config (defaults to `composer-config.yaml` in the working dir).
 *  2. Fetches `_service.sdl` from each configured subgraph in parallel.
 *  3. Invokes the bundled Node script which calls `@apollo/composition::composeServices`.
 *  4. Writes the composed `supergraph.graphql` (with a generated header banner) to the
 *     configured output path on success.
 *  5. On composition failure, prints each error to stderr and exits non-zero.
 *
 * The CLI is the single source of truth for the supergraph SDL; the router (Phase 3.3) reads
 * that file at startup.
 */
object Main

private val log = LoggerFactory.getLogger(Main::class.java)

fun main(rawArgs: Array<String>) {
    val configArg = rawArgs.firstOrNull() ?: "composer-config.yaml"
    val configPath = Path.of(configArg).toAbsolutePath().normalize()
    if (!Files.exists(configPath)) {
        System.err.println("[error] config not found at $configPath")
        exitProcess(2)
    }

    val config = ComposerConfig.load(configPath)
    val baseDir = configPath.parent ?: Path.of(".")
    val outputPath = config.resolveOutput(baseDir)

    val nodeBinary = System.getenv("NODE_BIN")?.takeIf { it.isNotBlank() } ?: "node"
    val composeScript = baseDir.resolve("scripts").resolve("compose.js")
    require(Files.exists(composeScript)) {
        "compose script not found at $composeScript"
    }

    log.info("composing {} subgraphs -> {}", config.subgraphs.size, outputPath)

    val sdls: Map<String, String> = runBlocking {
        val fetcher = SdlFetcher(config.fetchTimeoutMs)
        config.subgraphs.map { entry ->
            async(Dispatchers.IO) {
                runCatching { entry.name to fetcher.fetch(entry) }
            }
        }.awaitAll()
    }.let { results ->
        val failures = results.mapNotNull { it.exceptionOrNull() }
        if (failures.isNotEmpty()) {
            failures.forEach { System.err.println("[error] ${it.message}") }
            exitProcess(2)
        }
        results.map { it.getOrThrow() }.toMap(LinkedHashMap())
    }

    val composer = Composer(nodeBinary = nodeBinary, composeScript = composeScript)
    val result = composer.compose(sdls)
    if (!result.successful()) {
        System.err.println("[error] composition failed:")
        result.errors.forEach { System.err.println("  $it") }
        exitProcess(1)
    }

    val supergraph = result.supergraphSdl!!
    val banner = buildString {
        appendLine("# travelgraph supergraph SDL")
        appendLine("# Generated at ${Instant.now()} by travelgraph-composer.")
        appendLine("# Source subgraphs:")
        for ((name, _) in sdls) appendLine("#   - $name")
        appendLine("# DO NOT EDIT BY HAND. Run `make compose` (or `gradle :run`) to regenerate.")
        appendLine()
    }
    writeOutput(outputPath, banner + supergraph)
    log.info("wrote supergraph SDL ({} bytes) to {}", supergraph.length, outputPath)
}

private fun writeOutput(path: Path, contents: String) {
    val parent = path.parent
    if (parent != null) Files.createDirectories(parent)
    Files.writeString(
        path,
        contents,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
}
