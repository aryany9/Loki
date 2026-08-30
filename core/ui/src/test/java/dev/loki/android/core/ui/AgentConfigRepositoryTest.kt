package dev.loki.android.core.ui

import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.models.GenerationConfig
import dev.loki.android.core.models.RuntimeConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConfigRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Test
    fun `AgentConfig default JSON serialization roundtrip`() {
        val original = AgentConfig()
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<AgentConfig>(encoded)

        assertEquals(original.systemInstruction, decoded.systemInstruction)
        assertEquals(original.generationConfig.temperature, decoded.generationConfig.temperature, 0.001f)
        assertEquals(original.generationConfig.topK, decoded.generationConfig.topK)
        assertEquals(original.generationConfig.topP, decoded.generationConfig.topP, 0.001f)
        assertNull(decoded.generationConfig.seed)
        assertNull(decoded.generationConfig.maxOutputTokens)
        assertEquals(ExecutionBackend.AUTOMATIC, decoded.runtimeConfig.backend)
        assertNull(decoded.runtimeConfig.contextKvCapacity)
    }

    @Test
    fun `AgentConfig customized JSON serialization roundtrip`() {
        val custom = AgentConfig(
            systemInstruction = "You are a specialized coding assistant.",
            generationConfig = GenerationConfig(
                temperature = 0.2f,
                topK = 10,
                topP = 0.8f,
                seed = 12345,
                maxOutputTokens = 1024
            ),
            runtimeConfig = RuntimeConfig(
                backend = ExecutionBackend.GPU,
                contextKvCapacity = 16384
            )
        )

        val encoded = json.encodeToString(custom)
        val decoded = json.decodeFromString<AgentConfig>(encoded)

        assertEquals("You are a specialized coding assistant.", decoded.systemInstruction)
        assertEquals(0.2f, decoded.generationConfig.temperature, 0.001f)
        assertEquals(10, decoded.generationConfig.topK)
        assertEquals(0.8f, decoded.generationConfig.topP, 0.001f)
        assertEquals(Integer.valueOf(12345), decoded.generationConfig.seed)
        assertEquals(Integer.valueOf(1024), decoded.generationConfig.maxOutputTokens)
        assertEquals(ExecutionBackend.GPU, decoded.runtimeConfig.backend)
        assertEquals(Integer.valueOf(16384), decoded.runtimeConfig.contextKvCapacity)
    }

    @Test
    fun `JSON deserialization handles unknown keys gracefully`() {
        val jsonWithExtra = """
            {
                "systemInstruction": "Test extra",
                "generationConfig": {
                    "temperature": 0.5,
                    "topK": 30,
                    "topP": 0.9,
                    "unknownFutureField": "future_value"
                },
                "runtimeConfig": {
                    "backend": "CPU",
                    "contextKvCapacity": 4096
                },
                "anotherUnknownField": 123
            }
        """.trimIndent()

        val decoded = json.decodeFromString<AgentConfig>(jsonWithExtra)
        assertEquals("Test extra", decoded.systemInstruction)
        assertEquals(0.5f, decoded.generationConfig.temperature, 0.001f)
        assertEquals(ExecutionBackend.CPU, decoded.runtimeConfig.backend)
        assertEquals(Integer.valueOf(4096), decoded.runtimeConfig.contextKvCapacity)
    }
}
