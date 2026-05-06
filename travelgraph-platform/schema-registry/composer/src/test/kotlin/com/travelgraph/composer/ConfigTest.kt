package com.travelgraph.composer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ConfigTest {

    @Test
    fun `env var expansion uses defaults when var is unset`() {
        val src = "url: \${MISSING_VAR_FOR_TEST:-http://fallback}\n"
        val out = ComposerConfig.expandEnvVars(src)
        assertEquals("url: http://fallback\n", out)
    }

    @Test
    fun `env var expansion handles dollar signs without braces`() {
        val src = "value: \$keepMe\n"
        val out = ComposerConfig.expandEnvVars(src)
        assertEquals("value: \$keepMe\n", out)
    }
}
