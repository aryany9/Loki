package dev.loki.android.core.models

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentConfigSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `AgentConfig serializes and deserializes conversationLanguage correctly`() {
        val config = AgentConfig(
            conversationLanguage = "hi"
        )
        val serialized = json.encodeToString(config)
        val deserialized = json.decodeFromString<AgentConfig>(serialized)
        assertEquals("hi", deserialized.conversationLanguage)
    }

    @Test
    fun `AgentConfig deserializes pre-change JSON without conversationLanguage defaulting to auto`() {
        val oldJson = """
            {
                "systemInstruction": "Custom prompt",
                "generationConfig": {
                    "temperature": 0.7,
                    "topK": 40,
                    "topP": 0.95
                },
                "runtimeConfig": {
                    "backend": "AUTOMATIC"
                }
            }
        """.trimIndent()

        val deserialized = json.decodeFromString<AgentConfig>(oldJson)
        assertEquals("auto", deserialized.conversationLanguage)
        assertEquals("Custom prompt", deserialized.systemInstruction)
    }
}
