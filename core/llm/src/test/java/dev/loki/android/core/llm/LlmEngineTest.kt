package dev.loki.android.core.llm

import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.models.GenerationConfig
import dev.loki.android.core.models.MetadataConfidence
import dev.loki.android.core.models.ModelCapabilities
import dev.loki.android.core.models.ModelMetadataField
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRecordCapabilities
import dev.loki.android.core.models.RuntimeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `AgentConfig default initialization`() {
        val config = AgentConfig()
        assertEquals(AgentConfig.DEFAULT_SYSTEM_PROMPT, config.systemInstruction)
        assertEquals(0.7f, config.generationConfig.temperature, 0.001f)
        assertEquals(40, config.generationConfig.topK)
        assertEquals(0.95f, config.generationConfig.topP, 0.001f)
        assertNull(config.generationConfig.seed)
        assertNull(config.generationConfig.maxOutputTokens)
        assertEquals(ExecutionBackend.AUTOMATIC, config.runtimeConfig.backend)
        assertNull(config.runtimeConfig.contextKvCapacity)
    }

    @Test
    fun `AgentConfig custom configuration creation`() {
        val genConfig = GenerationConfig(
            temperature = 0.2f,
            topK = 20,
            topP = 0.8f,
            seed = 42,
            maxOutputTokens = 512
        )
        val runtimeConfig = RuntimeConfig(
            backend = ExecutionBackend.GPU,
            contextKvCapacity = 4096
        )
        val agentConfig = AgentConfig(
            systemInstruction = "Custom instruction",
            generationConfig = genConfig,
            runtimeConfig = runtimeConfig
        )

        assertEquals("Custom instruction", agentConfig.systemInstruction)
        assertEquals(0.2f, agentConfig.generationConfig.temperature, 0.001f)
        assertEquals(20, agentConfig.generationConfig.topK)
        assertEquals(0.8f, agentConfig.generationConfig.topP, 0.001f)
        assertEquals(Integer.valueOf(42), agentConfig.generationConfig.seed)
        assertEquals(Integer.valueOf(512), agentConfig.generationConfig.maxOutputTokens)
        assertEquals(ExecutionBackend.GPU, agentConfig.runtimeConfig.backend)
        assertEquals(Integer.valueOf(4096), agentConfig.runtimeConfig.contextKvCapacity)
    }

    @Test
    fun `ModelCapabilities reports reliable capability defaults`() {
        val capabilities = ModelCapabilities(
            supportsText = true,
            supportsToolCalling = true,
            supportsAudioInput = false,
            supportsVisionInput = false
        )
        assertTrue(capabilities.supportsText)
        assertTrue(capabilities.supportsToolCalling)
        assertFalse(capabilities.supportsAudioInput)
        assertFalse(capabilities.supportsVisionInput)
    }

    @Test
    fun `LlmEngine default startConversation delegates to AgentConfig`() = runBlocking {
        var capturedInstruction: String? = null
        var capturedAudioBytes: ByteArray? = null

        val testEngine = object : LlmEngine {
            override val modelState: StateFlow<LlmModelState> = MutableStateFlow(LlmModelState.Ready())
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun startConversation(agentConfig: AgentConfig): Boolean {
                capturedInstruction = agentConfig.systemInstruction
                return true
            }
            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                capturedAudioBytes = audioBytes
                return Result.success("ok: $prompt")
            }
            override fun cancel() {}
            override fun release() {}
        }

        val success = testEngine.startConversation("Test system prompt")
        assertTrue(success)
        assertEquals("Test system prompt", capturedInstruction)
        assertTrue(testEngine.capabilities.supportsText)
        assertTrue(testEngine.capabilities.supportsToolCalling)

        val result = testEngine.generate("Hello", byteArrayOf(1, 2, 3))
        assertTrue(result.isSuccess)
        assertEquals(3, capturedAudioBytes?.size)
    }

    @Test
    fun `LlmEngine default initializeAsync forwards to modelPath overload`() = runBlocking {
        var capturedPath: String? = null
        var capturedForce: Boolean? = null
        var capturedRuntime: RuntimeConfig? = null

        val testEngine = object : LlmEngine {
            override val modelState: StateFlow<LlmModelState> = MutableStateFlow(LlmModelState.Ready())
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(
                modelPath: String?,
                runtimeConfig: RuntimeConfig,
                force: Boolean
            ): Boolean {
                capturedPath = modelPath
                capturedRuntime = runtimeConfig
                capturedForce = force
                return true
            }
            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> = Result.success("ok")
            override fun cancel() {}
            override fun release() {}
        }

        val success = testEngine.initializeAsync("/path/to/model.litertlm", RuntimeConfig(backend = ExecutionBackend.GPU), force = true)
        assertTrue(success)
        assertEquals("/path/to/model.litertlm", capturedPath)
        assertEquals(ExecutionBackend.GPU, capturedRuntime?.backend)
        assertEquals(true, capturedForce)

        val successDefault = testEngine.initializeAsync("/path/to/default.litertlm")
        assertTrue(successDefault)
        assertEquals("/path/to/default.litertlm", capturedPath)
        assertEquals(false, capturedForce)
    }
}
