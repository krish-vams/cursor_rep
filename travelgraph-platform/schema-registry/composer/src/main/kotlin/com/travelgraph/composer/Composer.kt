package com.travelgraph.composer

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/**
 * Composition driver. Writes each subgraph's SDL to a temp file and shells out to the bundled
 * Node script (`scripts/compose.js`), which calls `@apollo/composition`'s `composeServices`.
 *
 * Why a Node subprocess? `@apollo/composition` is the reference, spec-conformant Apollo
 * Federation v2 composer. There is no comparable Kotlin-native composer; reimplementing one
 * is out of scope for this phase. Cosmo's composer is also viable but is currently Go and
 * less ubiquitously installed than Node. Node is widely available in CI and dev sandboxes.
 */
class Composer(
    private val nodeBinary: String,
    private val composeScript: Path,
) {

    private val log = LoggerFactory.getLogger(Composer::class.java)

    data class Result(
        val supergraphSdl: String?,
        val errors: List<String>,
    ) {
        fun successful(): Boolean = supergraphSdl != null && errors.isEmpty()
    }

    fun compose(subgraphSdls: Map<String, String>): Result {
        val workdir = Files.createTempDirectory("travelgraph-compose-")
        try {
            val args = mutableListOf(nodeBinary, composeScript.toAbsolutePath().toString())
            for ((name, sdl) in subgraphSdls) {
                val sdlFile = workdir.resolve("$name.graphql")
                Files.writeString(
                    sdlFile,
                    sdl,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                args += "$name=${sdlFile.toAbsolutePath()}"
            }

            log.info("invoking composer: {}", args.joinToString(" "))
            val proc = ProcessBuilder(args)
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                throw IOException("composer process did not finish in 60s")
            }

            val exit = proc.exitValue()
            if (exit != 0) {
                val errLines = stderr.lineSequence().filter { it.isNotBlank() }.toList()
                return Result(supergraphSdl = null, errors = errLines)
            }
            return Result(supergraphSdl = stdout, errors = emptyList())
        } finally {
            // Best-effort cleanup; not fatal on Windows where deletion can race.
            runCatching {
                Files.walk(workdir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
