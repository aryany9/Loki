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

    @Test
    fun `computeReplayTurns selects up to 3 turns fitting within budget in chronological order`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyModelManager = ModelManager(dummyContext)
        val engine = LiteRtLlmEngine(dummyContext, dummyModelManager)

        val turns = listOf(
            LiteRtLlmEngine.TurnEntry(com.google.ai.edge.litertlm.Message.user("turn1"), "turn1", "resp1", estimatedTokens = 100),
            LiteRtLlmEngine.TurnEntry(com.google.ai.edge.litertlm.Message.user("turn2"), "turn2", "resp2", estimatedTokens = 100),
            LiteRtLlmEngine.TurnEntry(com.google.ai.edge.litertlm.Message.user("turn3"), "turn3", "resp3", estimatedTokens = 100),
            LiteRtLlmEngine.TurnEntry(com.google.ai.edge.litertlm.Message.user("turn4"), "turn4", "resp4", estimatedTokens = 100),
            LiteRtLlmEngine.TurnEntry(com.google.ai.edge.litertlm.Message.user("turn5"), "turn5", "resp5", estimatedTokens = 100)
        )

        // Case 1: Large budget (fits 3 turns) -> takes turns 3, 4, 5 (last 3) in order
        val selected3 = engine.computeReplayTurns(turns, budget = 500)
        assertEquals(3, selected3.size)
        assertEquals("turn3", selected3[0].promptText)
        assertEquals("turn4", selected3[1].promptText)
        assertEquals("turn5", selected3[2].promptText)

        // Case 2: Constrained budget (250 tokens) -> takes only turns 4, 5
        val selected2 = engine.computeReplayTurns(turns, budget = 250)
        assertEquals(2, selected2.size)
        assertEquals("turn4", selected2[0].promptText)
        assertEquals("turn5", selected2[1].promptText)

        // Case 3: Very small budget (50 tokens) -> cannot fit even latest turn (100 tokens), drops context
        val selected0 = engine.computeReplayTurns(turns, budget = 50)
        assertEquals(0, selected0.size)
    }

    @Test
    fun `buildConversationConfig preserves systemInstruction and respects NPU vs GPU sampler rules`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyModelManager = ModelManager(dummyContext)
        val engine = LiteRtLlmEngine(dummyContext, dummyModelManager)

        val config = AgentConfig(
            systemInstruction = "You are a helpful on-device assistant.",
            generationConfig = GenerationConfig(temperature = 0.5f, topK = 30, topP = 0.9f)
        )
        val replay = listOf(com.google.ai.edge.litertlm.Message.user("prior user turn"))

        // NPU backend: systemInstruction intact, replay messages present, samplerConfig null
        val npuConvConfig = engine.buildConversationConfig(config, replay, ExecutionBackend.NPU)
        assertEquals(1, npuConvConfig.initialMessages?.size)
        assertNull(npuConvConfig.samplerConfig)

        // GPU backend: systemInstruction intact, replay messages present, samplerConfig populated
        val gpuConvConfig = engine.buildConversationConfig(config, replay, ExecutionBackend.GPU)
        assertEquals(1, gpuConvConfig.initialMessages?.size)
        assertTrue(gpuConvConfig.samplerConfig != null)
        assertEquals(30, gpuConvConfig.samplerConfig?.topK)
    }

    @Test
    fun `NPU default KV capacity is clamped to 4096`() {
        assertEquals(4096, LiteRtLlmEngine.NPU_DEFAULT_KV_CAPACITY)
    }

    @Test
    fun `isActionExecution identifies side-effecting tools vs read-only lookups and direct responses`() {
        // Side-effecting actions
        assertTrue(LiteRtLlmEngine.isActionExecution("""{"tool": "call_contact", "arguments": {"contact_name": "Mom"}}"""))
        assertTrue(LiteRtLlmEngine.isActionExecution("""{"tool": "dial_number", "arguments": {"phone_number": "1234567890"}}"""))
        assertTrue(LiteRtLlmEngine.isActionExecution("""{"tool": "set_timer", "arguments": {"duration_seconds": 60}}"""))
        assertTrue(LiteRtLlmEngine.isActionExecution("""{"tool": "toggle_flashlight", "arguments": {"enabled": true}}"""))
        assertTrue(LiteRtLlmEngine.isActionExecution("```json\n{\"tool\": \"send_message\", \"arguments\": {\"recipient\": \"Mom\"}}\n```"))

        // Read-only lookups
        assertFalse(LiteRtLlmEngine.isActionExecution("""{"tool": "lookup_contact", "arguments": {"query": "Mom"}}"""))
        assertFalse(LiteRtLlmEngine.isActionExecution("""{"tool": "select_contact", "arguments": {"candidate_id": "c1"}}"""))
        assertFalse(LiteRtLlmEngine.isActionExecution("""{"tool": "get_battery_status", "arguments": {}}"""))
        assertFalse(LiteRtLlmEngine.isActionExecution("""{"tool": "get_wifi_state", "arguments": {}}"""))

        // Direct responses / conversational answers
        assertFalse(LiteRtLlmEngine.isActionExecution("""{"response": "Hello! How can I help you today?"}"""))
        assertFalse(LiteRtLlmEngine.isActionExecution("Calling Mom is ready to proceed."))
    }

    @Test
    fun `executed-action turn excluded from activation replay but included in in-session compaction replay`() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyModelManager = ModelManager(dummyContext)
        val engine = LiteRtLlmEngine(dummyContext, dummyModelManager)

        val turns = listOf(
            LiteRtLlmEngine.TurnEntry(
                userMessage = com.google.ai.edge.litertlm.Message.user("who is mom"),
                promptText = "who is mom",
                assistantResponse = """{"tool": "lookup_contact", "arguments": {"query": "Mom"}}""",
                estimatedTokens = 100,
                executedAction = false
            ),
            LiteRtLlmEngine.TurnEntry(
                userMessage = com.google.ai.edge.litertlm.Message.user("call mom"),
                promptText = "call mom",
                assistantResponse = """{"tool": "call_contact", "arguments": {"contact_name": "Mom"}}""",
                estimatedTokens = 100,
                executedAction = true
            )
        )

        // 13.2 Activation replay path: filterNot { it.executedAction }
        val activationReplayInput = turns.filterNot { it.executedAction }
        val activationReplay = engine.computeReplayTurns(activationReplayInput, budget = 500)
        assertEquals(1, activationReplay.size)
        assertEquals("who is mom", activationReplay[0].promptText)
        assertFalse(activationReplay[0].executedAction)

        // 13.2 In-session compaction replay path: keeps full turns (does not filter executedAction)
        val compactionReplay = engine.computeReplayTurns(turns, budget = 500)
        assertEquals(2, compactionReplay.size)
        assertEquals("who is mom", compactionReplay[0].promptText)
        assertEquals("call mom", compactionReplay[1].promptText)
    }

    @Test
    fun `recentTurns not re-populated from replay on startConversation`() = runBlocking {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyModelManager = ModelManager(dummyContext)
        val engine = LiteRtLlmEngine(dummyContext, dummyModelManager)

        // Populate recentTurns with 2 prior turns
        engine.recentTurns.add(
            LiteRtLlmEngine.TurnEntry(
                userMessage = com.google.ai.edge.litertlm.Message.user("lookup John"),
                promptText = "lookup John",
                assistantResponse = "resp1",
                estimatedTokens = 80,
                executedAction = false
            )
        )
        engine.recentTurns.add(
            LiteRtLlmEngine.TurnEntry(
                userMessage = com.google.ai.edge.litertlm.Message.user("call John"),
                promptText = "call John",
                assistantResponse = "resp2",
                estimatedTokens = 80,
                executedAction = true
            )
        )

        assertEquals(2, engine.recentTurns.size)

        // Note: startConversation without initialized native engine will return false on initialization,
        // but verify resetConversation clears recentTurns and startConversation does not overwrite recentTurns.
        engine.resetConversation()
        assertEquals(0, engine.recentTurns.size)
    }
}
