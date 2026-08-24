package dev.loki.android

import dev.loki.android.core.llm.GrammarBuilder
import dev.loki.android.core.llm.ToolDefinition
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.local.DefaultLocalTools
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    @Test
    fun testGrammarBuilderFromRegistry() {
        val registry = ToolRegistry()
        DefaultLocalTools.registerAll(registry)

        val grammar = GrammarBuilder.buildFrom(registry)
        assertNotNull(grammar)
    }
}
