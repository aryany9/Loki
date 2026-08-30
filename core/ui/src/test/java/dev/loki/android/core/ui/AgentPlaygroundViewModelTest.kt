package dev.loki.android.core.ui

import android.content.Context
import android.content.ContextWrapper
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ExecutionBackend
import dev.loki.android.core.models.GenerationConfig
import dev.loki.android.core.models.ModelCapabilities
import dev.loki.android.core.models.RuntimeConfig
import dev.loki.android.core.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentPlaygroundViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    class FakeLlmEngine : LlmEngine {
        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("Gemma-4-E4B-it"))
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()

        override val capabilities: ModelCapabilities = ModelCapabilities(
            supportsText = true,
            supportsToolCalling = true,
            supportsAudioInput = true,
            supportsVisionInput = false
        )

        var lastAgentConfig: AgentConfig? = null
        var lastGeneratedPrompt: String? = null

        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean = true
        override suspend fun startConversation(agentConfig: AgentConfig): Boolean {
            lastAgentConfig = agentConfig
            return true
        }

        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> {
            lastGeneratedPrompt = prompt
            return Result.success("""{"response": "Test output for: $prompt"}""")
        }

        override fun cancel() {}
        override fun release() {}
    }

    class FakeAgentConfigRepository(initialConfig: AgentConfig = AgentConfig()) : AgentConfigRepository(ContextWrapper(null)) {
        var currentConfig: AgentConfig = initialConfig
        var resetCalled = false

        override fun getAgentConfigFlow(modelId: String?): Flow<AgentConfig> = flowOf(currentConfig)
        override suspend fun getAgentConfig(modelId: String?): AgentConfig = currentConfig
        override suspend fun saveAgentConfig(config: AgentConfig, modelId: String?) {
            currentConfig = config
        }
        override suspend fun resetDefaults(modelId: String?) {
            currentConfig = AgentConfig()
            resetCalled = true
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preset selection correctly updates generation parameters`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = FakeLlmEngine()
        val manager = ConversationManager(dummyContext, fakeLlm, ToolRegistry(), ttsEngine = null)
        val fakeRepo = FakeAgentConfigRepository()

        val viewModel = AgentPlaygroundViewModel(dummyContext, manager, fakeRepo)
        advanceUntilIdle()

        // Select FAST preset
        viewModel.selectPreset(ResponseBehaviorPreset.FAST)
        assertEquals(ResponseBehaviorPreset.FAST, viewModel.uiState.value.preset)
        assertEquals(0.8f, viewModel.uiState.value.temperature, 0.001f)
        assertEquals(40, viewModel.uiState.value.topK)
        assertEquals(0.9f, viewModel.uiState.value.topP, 0.001f)
        assertEquals(Integer.valueOf(128), viewModel.uiState.value.maxOutputTokens)

        // Select PRECISE preset
        viewModel.selectPreset(ResponseBehaviorPreset.PRECISE)
        assertEquals(ResponseBehaviorPreset.PRECISE, viewModel.uiState.value.preset)
        assertEquals(0.2f, viewModel.uiState.value.temperature, 0.001f)
        assertEquals(10, viewModel.uiState.value.topK)
        assertEquals(0.8f, viewModel.uiState.value.topP, 0.001f)
        assertEquals(Integer.valueOf(256), viewModel.uiState.value.maxOutputTokens)

        // Select BALANCED preset
        viewModel.selectPreset(ResponseBehaviorPreset.BALANCED)
        assertEquals(ResponseBehaviorPreset.BALANCED, viewModel.uiState.value.preset)
        assertEquals(0.7f, viewModel.uiState.value.temperature, 0.001f)
        assertEquals(40, viewModel.uiState.value.topK)
        assertEquals(0.95f, viewModel.uiState.value.topP, 0.001f)
        assertEquals(Integer.valueOf(256), viewModel.uiState.value.maxOutputTokens)
    }

    @Test
    fun `modifying advanced parameter migrates preset to CUSTOM`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = FakeLlmEngine()
        val manager = ConversationManager(dummyContext, fakeLlm, ToolRegistry(), ttsEngine = null)
        val fakeRepo = FakeAgentConfigRepository()

        val viewModel = AgentPlaygroundViewModel(dummyContext, manager, fakeRepo)
        advanceUntilIdle()

        viewModel.selectPreset(ResponseBehaviorPreset.BALANCED)
        assertEquals(ResponseBehaviorPreset.BALANCED, viewModel.uiState.value.preset)

        viewModel.updateTemperature(1.2f)
        assertEquals(ResponseBehaviorPreset.CUSTOM, viewModel.uiState.value.preset)
        assertEquals(1.2f, viewModel.uiState.value.temperature, 0.001f)

        viewModel.updateTopK(80)
        assertEquals(ResponseBehaviorPreset.CUSTOM, viewModel.uiState.value.preset)
        assertEquals(80, viewModel.uiState.value.topK)
    }

    @Test
    fun `saveConfiguration validates bounds and persists AgentConfig`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = FakeLlmEngine()
        val manager = ConversationManager(dummyContext, fakeLlm, ToolRegistry(), ttsEngine = null)
        val fakeRepo = FakeAgentConfigRepository()

        val viewModel = AgentPlaygroundViewModel(dummyContext, manager, fakeRepo)
        advanceUntilIdle()

        viewModel.updateSystemPrompt("You are an expert tutor.")
        viewModel.updateTemperature(0.5f)
        viewModel.updateTopK(25)
        viewModel.updateTopP(0.85f)
        viewModel.updateBackend(ExecutionBackend.GPU)
        viewModel.updateKvCapacity(4096)

        viewModel.directSave()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.validationError)
        assertEquals("Configuration saved successfully", viewModel.uiState.value.statusMessage)

        val saved = fakeRepo.currentConfig
        assertEquals("You are an expert tutor.", saved.systemInstruction)
        assertEquals(0.5f, saved.generationConfig.temperature, 0.001f)
        assertEquals(25, saved.generationConfig.topK)
        assertEquals(0.85f, saved.generationConfig.topP, 0.001f)
        assertEquals(ExecutionBackend.GPU, saved.runtimeConfig.backend)
        assertEquals(Integer.valueOf(4096), saved.runtimeConfig.contextKvCapacity)
    }

    @Test
    fun `resetDefaults restores default AgentConfig`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = FakeLlmEngine()
        val manager = ConversationManager(dummyContext, fakeLlm, ToolRegistry(), ttsEngine = null)
        val fakeRepo = FakeAgentConfigRepository(
            initialConfig = AgentConfig(systemInstruction = "Custom instruction")
        )

        val viewModel = AgentPlaygroundViewModel(dummyContext, manager, fakeRepo)
        advanceUntilIdle()

        viewModel.resetDefaults()
        advanceUntilIdle()

        assertTrue(fakeRepo.resetCalled)
        assertEquals(AgentConfig.DEFAULT_SYSTEM_PROMPT, viewModel.uiState.value.systemPrompt)
        assertEquals(ResponseBehaviorPreset.BALANCED, viewModel.uiState.value.preset)
        assertEquals(0.7f, viewModel.uiState.value.temperature, 0.001f)
        assertEquals(40, viewModel.uiState.value.topK)
    }

    @Test
    fun `runTestPrompt executes utterance and records diagnostics`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = FakeLlmEngine()
        val manager = ConversationManager(dummyContext, fakeLlm, ToolRegistry(), ttsEngine = null)
        val fakeRepo = FakeAgentConfigRepository()

        val viewModel = AgentPlaygroundViewModel(dummyContext, manager, fakeRepo)
        advanceUntilIdle()

        viewModel.updateTestPromptInput("What is the capital of France?")
        viewModel.runTestPrompt()

        var attempts = 0
        while (viewModel.uiState.value.isTestRunning && attempts < 20) {
            Thread.sleep(50)
            advanceUntilIdle()
            attempts++
        }

        assertFalse(viewModel.uiState.value.isTestRunning)
        assertNotNull(viewModel.uiState.value.testPromptOutput)
        assertTrue(viewModel.uiState.value.testPromptOutput?.contains("What is the capital of France?") == true)
        assertTrue(fakeLlm.lastGeneratedPrompt?.contains("What is the capital of France?") == true)
    }
}
