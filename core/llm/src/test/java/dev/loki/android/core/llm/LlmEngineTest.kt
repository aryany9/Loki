package dev.loki.android.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmEngineTest {

    @Test
    fun `LlmModelState default ready state`() {
        val state = LlmModelState.Ready("Qwen3.8-4B")
        assertEquals("Qwen3.8-4B", state.modelName)
    }

    @Test
    fun `LlmModelState default loading state`() {
        val state = LlmModelState.Loading("Qwen3.8-4B")
        assertEquals("Qwen3.8-4B", state.modelName)
    }
}
