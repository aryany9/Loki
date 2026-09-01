package dev.loki.android.core.ui

import android.content.Context
import android.content.ContextWrapper
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.ConversationStore
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ModelCapabilities
import dev.loki.android.core.models.RuntimeConfig
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.theme.ThemeMode
import dev.loki.android.core.theme.ThemeRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: File

    class FakeLlmEngine : LlmEngine {
        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("Qwen-2.5"))
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()
        override val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true)

        var initializeCount = 0

        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean {
            initializeCount++
            return true
        }
        override suspend fun startConversation(agentConfig: AgentConfig): Boolean = true
        override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
            return Result.success("ok")
        }
        override fun cancel() {}
        override fun release() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = Files.createTempDirectory("settings_vm_test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `SettingsViewModel reflects modelState and supports retry`() = runTest(testDispatcher) {
        val fakeLlm = FakeLlmEngine()
        val dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
        }
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val themeRepo = ThemeRepository(dummyContext)
        val viewModel = SettingsViewModel(themeRepository = themeRepo, conversationManager = manager)

        advanceUntilIdle()

        assertTrue(viewModel.modelState.value is LlmModelState.Ready)
        assertEquals("Qwen-2.5", (viewModel.modelState.value as LlmModelState.Ready).modelName)

        viewModel.retryLoadModel()
        advanceUntilIdle()
        assertEquals(1, fakeLlm.initializeCount)
    }

    @Test
    fun `SettingsViewModel setThemeMode updates themeMode`() = runTest(testDispatcher) {
        val fakeLlm = FakeLlmEngine()
        val dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
        }
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val themeRepo = ThemeRepository(dummyContext)
        val viewModel = SettingsViewModel(themeRepository = themeRepo, conversationManager = manager)

        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.DARK)
        advanceUntilIdle()

        viewModel.setThemeMode(ThemeMode.LIGHT)
        advanceUntilIdle()
    }

    @Test
    fun `SettingsViewModel memories CRUD methods update memories state flow`() = runTest(testDispatcher) {
        val fakeLlm = FakeLlmEngine()
        val dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
        }
        val convStore = ConversationStore(File(tempDir, "convs"), ioDispatcher = testDispatcher)
        val memStore = dev.loki.android.core.conversation.MemoryStore(File(tempDir, "memories"), ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = convStore,
            memoryStore = memStore,
            ioDispatcher = testDispatcher
        )
        val themeRepo = ThemeRepository(dummyContext)
        val viewModel = SettingsViewModel(themeRepository = themeRepo, conversationManager = manager)

        advanceUntilIdle()
        assertTrue(viewModel.memories.value.isEmpty())

        // Add
        viewModel.addMemory("My favorite coffee is Espresso")
        advanceUntilIdle()
        assertEquals(1, viewModel.memories.value.size)
        assertEquals("My favorite coffee is Espresso", viewModel.memories.value[0].text)
        val entryId = viewModel.memories.value[0].id

        // Update
        viewModel.updateMemory(entryId, "My favorite coffee is Cortado")
        advanceUntilIdle()
        assertEquals(1, viewModel.memories.value.size)
        assertEquals("My favorite coffee is Cortado", viewModel.memories.value[0].text)

        // Add another
        viewModel.addMemory("Live in Kyoto")
        advanceUntilIdle()
        assertEquals(2, viewModel.memories.value.size)

        // Delete
        viewModel.deleteMemory(entryId)
        advanceUntilIdle()
        assertEquals(1, viewModel.memories.value.size)
        assertEquals("Live in Kyoto", viewModel.memories.value[0].text)

        // Clear
        viewModel.clearMemories()
        advanceUntilIdle()
        assertTrue(viewModel.memories.value.isEmpty())
    }

    @Test
    fun `SettingsViewModel setConversationLanguage persists to repository and updates active config`() = runTest(testDispatcher) {
        val fakeLlm = FakeLlmEngine()
        val dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = tempDir
        }
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val themeRepo = ThemeRepository(dummyContext)
        val agentConfigRepo = object : AgentConfigRepository(dummyContext) {
            private var savedConfig = AgentConfig()
            override fun getAgentConfigFlow(modelId: String?) = kotlinx.coroutines.flow.flowOf(savedConfig)
            override suspend fun getAgentConfig(modelId: String?) = savedConfig
            override suspend fun saveAgentConfig(config: AgentConfig, modelId: String?) {
                savedConfig = config
            }
        }

        val viewModel = SettingsViewModel(
            themeRepository = themeRepo,
            conversationManager = manager,
            agentConfigRepository = agentConfigRepo
        )

        advanceUntilIdle()
        assertEquals("auto", viewModel.conversationLanguage.value)

        viewModel.setConversationLanguage("hi")
        advanceUntilIdle()

        assertEquals("hi", manager.getAgentConfig().conversationLanguage)
        assertEquals("hi", agentConfigRepo.getAgentConfig().conversationLanguage)
    }
}
