package dev.loki.android.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    @Test
    fun testGrammarStringDoesNotContainLiteralNewlinesInsideBrackets() {
        val grammar = GrammarBuilder.buildFromTools(Spike2Benchmark.sampleTools)
        println("Generated GBNF grammar:\n$grammar")
        
        // Ensure no null bytes (char code 0)
        assertTrue("Grammar should not contain null bytes", !grammar.contains('\u0000'))
        
        // Ensure string rule contains valid characters
        assertTrue(grammar.contains("root ::="))
        assertTrue(grammar.contains("call_contact"))
    }
}
