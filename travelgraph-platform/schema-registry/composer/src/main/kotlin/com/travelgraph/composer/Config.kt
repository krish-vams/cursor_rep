package com.travelgraph.composer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/** Single subgraph entry in the composer config. */
data class SubgraphEntry(val name: String, val url: String)

/**
 * Composer configuration. The `output` path may be relative -- it is resolved against the
 * config file's directory by [ComposerConfig.resolveOutput].
 */
data class ComposerConfig(
    val subgraphs: List<SubgraphEntry>,
    val output: String,
    val fetchTimeoutMs: Long = 5_000,
) {

    fun resolveOutput(baseDir: Path): Path =
        if (Path.of(output).isAbsolute) Path.of(output) else baseDir.resolve(output).normalize()

    companion object {
        private val mapper = ObjectMapper(YAMLFactory()).registerModule(kotlinModule())

        fun load(path: Path): ComposerConfig {
            val raw = Files.readString(path)
            val expanded = expandEnvVars(raw)
            val parsed = mapper.readValue<ComposerConfig>(expanded)
            require(parsed.subgraphs.isNotEmpty()) { "config has no subgraphs" }
            require(parsed.subgraphs.map { it.name }.toSet().size == parsed.subgraphs.size) {
                "duplicate subgraph names in config"
            }
            return parsed
        }

        /**
         * Expand `${VAR}` and `${VAR:-default}` references against the current environment.
         * Mirrors the simple expansion used by the router config.
         */
        internal fun expandEnvVars(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '$' && i + 1 < s.length && s[i + 1] == '{') {
                    val end = s.indexOf('}', i + 2)
                    if (end < 0) {
                        out.append(s.substring(i)); break
                    }
                    val expr = s.substring(i + 2, end)
                    val (name, default) = if (":-" in expr) {
                        val idx = expr.indexOf(":-"); expr.substring(0, idx) to expr.substring(idx + 2)
                    } else expr to ""
                    out.append(System.getenv(name) ?: default)
                    i = end + 1
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}
