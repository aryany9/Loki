package dev.loki.android.core.ui

import android.content.ContextWrapper
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.ConversationStore
import dev.loki.android.core.conversation.ConversationTurn
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.assistant.VoiceUnavailableReason
import dev.loki.android.core.models.AgentConfig
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelCapabilities
import dev.loki.android.core.models.ModelCatalog
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRegistry
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelRuntimeController
import dev.loki.android.core.models.ModelStorage
import dev.loki.android.core.models.RuntimeConfig
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    class StreamingFakeLlmEngine : LlmEngine {
        private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("TestModel"))
        override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()
        override val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true)

        var responseText: String = "Hello there, I am Loki."
        var tokenListToEmit: List<String> = listOf("{\"response\": ", "\"Hello", " there", ", I", " am", " Loki.\"}")

        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean = true
        override suspend fun startConversation(agentConfig: AgentConfig): Boolean = true

        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> {
            tokenListToEmit.forEach { token ->
                onToken?.invoke(token)
            }
            return Result.success("""{"response": "$responseText"}""")
        }

        override fun cancel() {}
        override fun release() {}
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
    fun `sendMessage transitions from thinking to streaming to finalized response`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()
        val tempDir = Files.createTempDirectory("cvm_test1").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)

        advanceUntilIdle()

        // Initial state has empty messages (home greeting is rendered in UI)
        assertEquals(0, viewModel.messages.value.size)

        // Send a message
        viewModel.sendMessage("Tell me about yourself")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        // Should have user msg + finalized assistant msg = 2 messages total
        assertEquals(2, messages.size)

        val userMsg = messages[0]
        assertEquals(MessageSender.USER, userMsg.sender)
        assertEquals("Tell me about yourself", userMsg.text)

        val assistantMsg = messages[1]
        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals(fakeLlm.responseText, assistantMsg.text)
        assertFalse("Message should not be thinking after completion", assistantMsg.isThinking)
        assertFalse("Message should not be streaming after completion", assistantMsg.isStreaming)

        tempDir.deleteRecursively()
    }

    @Test
    fun `sendMessage preserves toolName on finalized message after tool execution`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val tempDir = Files.createTempDirectory("cvm_test2").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val toolFakeLlm = object : LlmEngine {
            private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("TestModel"))
            override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()
            override val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true, supportsToolCalling = true)

            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean = true
            override suspend fun startConversation(agentConfig: AgentConfig): Boolean = true

            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                return Result.success("""{"tool": "get_current_time", "arguments": {}}""")
            }

            override fun cancel() {}
            override fun release() {}
        }

        val toolRegistry = ToolRegistry().apply {
            register(object : dev.loki.android.core.tools.LocalTool {
                override val name: String = "get_current_time"
                override val description: String = "Get time"
                override val parameters: Map<String, dev.loki.android.core.tools.ToolParam> = emptyMap()
                override suspend fun execute(context: android.content.Context, arguments: Map<String, Any?>): dev.loki.android.core.tools.ToolResult {
                    return dev.loki.android.core.tools.ToolResult.success(mapOf("formatted" to "3:00 PM"))
                }
            })
        }

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = toolFakeLlm,
            toolRegistry = toolRegistry,
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        viewModel.sendMessage("What time is it?")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)

        val assistantMsg = messages[1]
        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertEquals("get_current_time", assistantMsg.toolName)
        assertTrue(assistantMsg.toolResult != null)
        assertEquals(true, assistantMsg.toolResult?.success)
        assertFalse(assistantMsg.isThinking)

        tempDir.deleteRecursively()
    }

    @Test
    fun `cancelGeneration finalizes assistant message with partial text and cancels engine`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val tempDir = Files.createTempDirectory("cvm_test3").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        var engineCancelCalled = false
        val cancelFakeLlm = object : LlmEngine {
            private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("TestModel"))
            override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()
            override val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true)

            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean = true
            override suspend fun startConversation(agentConfig: AgentConfig): Boolean = true

            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                onToken?.invoke("Partial streamed response")
                kotlinx.coroutines.awaitCancellation()
            }

            override fun cancel() {
                engineCancelCalled = true
            }
            override fun release() {}
        }

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = cancelFakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        viewModel.sendMessage("Generate a long response")
        advanceUntilIdle()

        viewModel.cancelGeneration()
        advanceUntilIdle()

        assertTrue("Engine cancel should be invoked", engineCancelCalled)

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)

        val assistantMsg = messages[1]
        assertEquals(MessageSender.ASSISTANT, assistantMsg.sender)
        assertFalse("Message should not be thinking after cancel", assistantMsg.isThinking)
        assertFalse("Message should not be streaming after cancel", assistantMsg.isStreaming)

        tempDir.deleteRecursively()
    }

    @Test
    fun `ChatViewModel opens home at startup and loads conversation turns upon selection`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        // Pre-populate a conversation in store
        val conv = store.createConversation(title = "Restored Chat")
        store.appendTurn(conv.id, ConversationTurn.User("Previous question"))
        store.appendTurn(conv.id, ConversationTurn.Assistant("Previous answer"))

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        // At startup: opens on empty home, does not auto-open stored conversation
        assertEquals(0, viewModel.messages.value.size)
        assertEquals(null, viewModel.currentConversationId)
        assertEquals(1, viewModel.conversations.value.size)

        // Select the stored conversation
        viewModel.selectConversation(conv.id)
        advanceUntilIdle()

        val restoredMessages = viewModel.messages.value
        assertEquals(2, restoredMessages.size)
        assertEquals("Previous question", restoredMessages[0].text)
        assertEquals(MessageSender.USER, restoredMessages[0].sender)
        assertEquals("Previous answer", restoredMessages[1].text)
        assertEquals(MessageSender.ASSISTANT, restoredMessages[1].sender)

        // Test newConversation
        viewModel.newConversation()
        advanceUntilIdle()
        val newMessages = viewModel.messages.value
        assertEquals(0, newMessages.size)
        assertEquals(null, viewModel.currentConversationId)

        tempDir.deleteRecursively()
    }

    @Test
    fun `conversations flow refreshes on init, new, send, select, and delete`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_conv_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val conv1 = store.createConversation(title = "Chat 1")
        store.appendTurn(conv1.id, ConversationTurn.User("Hello 1"), autoTitle = false)

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        // Initially contains conv1
        assertEquals(1, viewModel.conversations.value.size)
        assertEquals(conv1.id, viewModel.conversations.value[0].id)

        // Start new conversation (draft - no store creation yet)
        viewModel.newConversation()
        advanceUntilIdle()
        assertEquals(1, viewModel.conversations.value.size)
        assertEquals(0, viewModel.messages.value.size)
        assertEquals(null, viewModel.currentConversationId)

        // Send first message in new conversation -> creates 2nd conversation
        viewModel.sendMessage("Hello 2")
        advanceUntilIdle()
        assertEquals(2, viewModel.conversations.value.size)

        // Select conv1
        viewModel.selectConversation(conv1.id)
        advanceUntilIdle()
        assertEquals("Hello 1", viewModel.messages.value[0].text)
        assertEquals(1, viewModel.messages.value.size)

        // Delete conv1
        viewModel.deleteConversation(conv1.id)
        advanceUntilIdle()
        assertEquals(1, viewModel.conversations.value.size)
        assertFalse(viewModel.conversations.value.any { it.id == conv1.id })

        tempDir.deleteRecursively()
    }

    @Test
    fun `startVoiceInput when strategy is Unavailable sets voiceError and does not start recording`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_unavailable_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val fakeResolver = object : dev.loki.android.core.assistant.VoiceInputStrategyResolver() {
            // Returns Unavailable
        }

        val viewModel = ChatViewModel(
            conversationManager = manager,
            sttEngine = null,
            modelLibraryManager = null,
            voiceStrategyResolver = fakeResolver
        )
        advanceUntilIdle()

        viewModel.startVoiceInput()
        advanceUntilIdle()

        assertFalse("Recording should not be active", viewModel.isRecording.value)
        assertEquals("No active language model selected. Please complete setup.", viewModel.voiceError.value)

        viewModel.dismissVoiceError()
        assertEquals(null, viewModel.voiceError.value)

        tempDir.deleteRecursively()
    }

    @Test
    fun `startVoiceInput when STT errors sets voiceError and resets isRecording`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_stt_err_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val errorSttEngine = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                emit(dev.loki.android.core.voice.stt.SttEvent.Error(RuntimeException("Whisper model failed")))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
            override suspend fun transcribeAudio(pcmAudio: FloatArray, language: String): String = ""
        }

        val fakeSttResolver = object : dev.loki.android.core.assistant.VoiceInputStrategyResolver() {
            override fun resolve(
                modelManager: dev.loki.android.core.models.ModelLibraryManager?,
                sttEngine: dev.loki.android.core.voice.stt.SttEngine?
            ): dev.loki.android.core.assistant.VoiceInputStrategyResult {
                return dev.loki.android.core.assistant.VoiceInputStrategyResult.SttTranscribe
            }
        }

        val viewModel = ChatViewModel(
            conversationManager = manager,
            sttEngine = errorSttEngine,
            modelLibraryManager = null,
            voiceStrategyResolver = fakeSttResolver
        )
        advanceUntilIdle()

        viewModel.startVoiceInput()
        advanceUntilIdle()

        assertFalse("Recording should reset after STT error", viewModel.isRecording.value)
        assertEquals("Whisper model failed", viewModel.voiceError.value)

        tempDir.deleteRecursively()
    }

    @Test
    fun `startVoiceInput when STT not ready sets isVoiceModelDownloadable and actionable error`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_stt_not_ready_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val sttNotReadyResolver = object : dev.loki.android.core.assistant.VoiceInputStrategyResolver() {
            override fun resolve(
                modelManager: ModelLibraryManager?,
                sttEngine: dev.loki.android.core.voice.stt.SttEngine?
            ): dev.loki.android.core.assistant.VoiceInputStrategyResult {
                return dev.loki.android.core.assistant.VoiceInputStrategyResult.Unavailable(
                    reason = VoiceUnavailableReason.STT_NOT_READY,
                    message = "Voice recognition model not loaded."
                )
            }
        }

        val viewModel = ChatViewModel(
            conversationManager = manager,
            sttEngine = null,
            modelLibraryManager = null,
            voiceStrategyResolver = sttNotReadyResolver
        )
        advanceUntilIdle()

        viewModel.startVoiceInput()
        advanceUntilIdle()

        assertFalse("Recording should not be active", viewModel.isRecording.value)
        assertTrue("Voice model should be downloadable", viewModel.isVoiceModelDownloadable.value)
        assertTrue(viewModel.voiceError.value?.contains("Tap download") == true)

        viewModel.dismissVoiceError()
        assertEquals(null, viewModel.voiceError.value)
        assertFalse(viewModel.isVoiceModelDownloadable.value)

        tempDir.deleteRecursively()
    }

    @Test
    fun `downloadVoiceModel downloads artifact, registers ASR model and clears error`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_download_test").toFile()
        val storage = ModelStorage(tempDir)
        val registry = ModelRegistry(storage)
        val manager = ModelLibraryManager(storage, registry)

        var asrLoaded = false
        val fakeAsrEngine = object : dev.loki.android.core.voice.stt.SttEngine, ModelRuntimeController {
            override val isListening: Boolean = false
            override fun startListening(language: String) = kotlinx.coroutines.flow.emptyFlow<dev.loki.android.core.voice.stt.SttEvent>()
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
            override suspend fun load(model: ModelRecord): Boolean {
                asrLoaded = true
                return true
            }
            override suspend fun unload(model: ModelRecord) {}
        }
        manager.registerRuntime(ModelRuntime.LITERT_ASR, fakeAsrEngine)

        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val conversationManager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val catalogEntry = ModelCatalogEntry(
            id = "whisper-tiny-litert",
            displayName = "Whisper Tiny (ASR)",
            family = "Whisper",
            runtime = ModelRuntime.LITERT_ASR,
            format = ModelFormat.TFLITE,
            artifacts = listOf(
                ModelArtifact(
                    fileName = "whisper_tiny_30s_f32.tflite",
                    relativePath = "whisper_tiny_30s_f32.tflite",
                    sizeBytes = 10L,
                    sha256 = null,
                    url = "https://example.com/whisper_tiny.tflite"
                )
            ),
            capabilities = listOf("voice-recognition", "offline-stt")
        )
        val catalog = ModelCatalog(models = listOf(catalogEntry))

        val viewModel = ChatViewModel(
            conversationManager = conversationManager,
            sttEngine = fakeAsrEngine,
            modelLibraryManager = manager,
            bundledCatalog = catalog,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.downloadVoiceModel(
            streamOpener = { "fake bytes".byteInputStream() }
        )
        advanceUntilIdle()

        assertFalse("Downloading state should be false after completion", viewModel.isDownloadingVoiceModel.value)
        assertFalse("Downloadable state should be cleared", viewModel.isVoiceModelDownloadable.value)
        assertEquals(null, viewModel.voiceError.value)

        // Verify model is registered in manifest with LOADED availability
        val asrModel = manager.manifest.value.models.firstOrNull { it.id == "whisper-tiny-litert" }
        assertTrue("ASR model must be registered in manifest", asrModel != null)
        assertEquals(ModelAvailability.LOADED, asrModel?.availability)
        assertTrue("ASR controller load must have been called", asrLoaded)

        tempDir.deleteRecursively()
    }

    @Test
    fun `downloadVoiceModel when catalog has no LITERT_ASR sets voiceError and aborts`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_download_no_catalog").toFile()
        val storage = ModelStorage(tempDir)
        val registry = ModelRegistry(storage)
        val manager = ModelLibraryManager(storage, registry)
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val conversationManager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val viewModel = ChatViewModel(
            conversationManager = conversationManager,
            sttEngine = null,
            modelLibraryManager = manager,
            bundledCatalog = ModelCatalog(models = emptyList()),
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.downloadVoiceModel()
        advanceUntilIdle()

        assertEquals("Voice recognition model unavailable in the model catalog.", viewModel.voiceError.value)
        assertFalse(viewModel.isDownloadingVoiceModel.value)

        tempDir.deleteRecursively()
    }

    @Test
    fun `downloadVoiceModel when sttEngine is not ModelRuntimeController sets voice error`() = runTest(testDispatcher) {
        val tempDir = Files.createTempDirectory("chat_vm_download_bad_engine").toFile()
        val storage = ModelStorage(tempDir)
        val registry = ModelRegistry(storage)
        val manager = ModelLibraryManager(storage, registry)
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()

        val conversationManager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )

        val nonControllerSttEngine = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String) = kotlinx.coroutines.flow.emptyFlow<dev.loki.android.core.voice.stt.SttEvent>()
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val catalogEntry = ModelCatalogEntry(
            id = "whisper-tiny-litert",
            displayName = "Whisper Tiny (ASR)",
            family = "Whisper",
            runtime = ModelRuntime.LITERT_ASR,
            format = ModelFormat.TFLITE,
            artifacts = listOf(
                ModelArtifact(
                    fileName = "whisper_tiny_30s_f32.tflite",
                    relativePath = "whisper_tiny_30s_f32.tflite",
                    sizeBytes = 10L,
                    sha256 = null,
                    url = "https://example.com/whisper_tiny.tflite"
                )
            ),
            capabilities = listOf("voice-recognition", "offline-stt")
        )
        val catalog = ModelCatalog(models = listOf(catalogEntry))

        val viewModel = ChatViewModel(
            conversationManager = conversationManager,
            sttEngine = nonControllerSttEngine,
            modelLibraryManager = manager,
            bundledCatalog = catalog,
            ioDispatcher = testDispatcher
        )
        advanceUntilIdle()

        viewModel.downloadVoiceModel(
            streamOpener = { "fake bytes".byteInputStream() }
        )
        advanceUntilIdle()

        assertEquals("Voice engine unavailable. Please restart the app.", viewModel.voiceError.value)
        assertFalse(viewModel.isDownloadingVoiceModel.value)

        tempDir.deleteRecursively()
    }

    @Test
    fun `fresh install shows home and creates no conversation in store`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()
        val tempDir = Files.createTempDirectory("cvm_fresh_home").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        assertTrue(viewModel.messages.value.isEmpty())
        assertEquals(null, viewModel.currentConversationId)
        assertTrue(store.listConversations().isEmpty())

        tempDir.deleteRecursively()
    }

    @Test
    fun `restart with stored conversation opens home and populates recents without auto-opening`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()
        val tempDir = Files.createTempDirectory("cvm_restart_home").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val existing = store.createConversation("Prior Conversation")
        store.appendTurn(existing.id, ConversationTurn.User("Hello from past"))
        store.appendTurn(existing.id, ConversationTurn.Assistant("Past response"))

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        // Home state is empty
        assertTrue(viewModel.messages.value.isEmpty())
        assertEquals(null, viewModel.currentConversationId)
        // Recents still populated
        assertEquals(1, viewModel.conversations.value.size)
        assertEquals(existing.id, viewModel.conversations.value.first().id)

        tempDir.deleteRecursively()
    }

    @Test
    fun `first message creates exactly one conversation and subsequent turns append to it`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val fakeLlm = StreamingFakeLlmEngine()
        val tempDir = Files.createTempDirectory("cvm_lazy_create").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)
        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = ToolRegistry(),
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        assertEquals(null, viewModel.currentConversationId)
        assertEquals(0, store.listConversations().size)

        // Send first message
        viewModel.sendMessage("First message")
        advanceUntilIdle()

        val createdId = viewModel.currentConversationId
        assertTrue(createdId != null)
        assertEquals(1, store.listConversations().size)
        val storedConvo = store.loadConversation(createdId!!)
        assertTrue(storedConvo != null)
        assertEquals(2, storedConvo?.turns?.size) // User + Assistant

        // Send second message in same conversation
        viewModel.sendMessage("Second message")
        advanceUntilIdle()

        assertEquals(createdId, viewModel.currentConversationId)
        assertEquals(1, store.listConversations().size)
        val updatedConvo = store.loadConversation(createdId)
        assertEquals(4, updatedConvo?.turns?.size)

        // New conversation resets to draft
        viewModel.newConversation()
        advanceUntilIdle()

        assertEquals(null, viewModel.currentConversationId)
        assertTrue(viewModel.messages.value.isEmpty())
        assertEquals(1, store.listConversations().size) // No extra conversation created

        tempDir.deleteRecursively()
    }

    @Test
    fun `pendingConfirmation is populated on ConfirmationRequired and cleared on respondToConfirmation`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val tempDir = Files.createTempDirectory("cvm_confirm_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)

        var executedTool = false
        val gatedTool = object : dev.loki.android.core.tools.LocalTool {
            override val name: String = "call_contact"
            override val description: String = "Call contact"
            override val parameters: Map<String, dev.loki.android.core.tools.ToolParam> = emptyMap()
            override val requiresConfirmation: Boolean = true
            override fun describeAction(arguments: Map<String, Any?>): String = "Call Alice at +1234567890?"
            override suspend fun execute(context: android.content.Context, arguments: Map<String, Any?>): ToolResult {
                executedTool = true
                return ToolResult.success(mapOf("status" to "initiated"))
            }
        }

        val toolRegistry = ToolRegistry().apply { register(gatedTool) }
        val fakeLlm = object : LlmEngine {
            private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("TestModel"))
            override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()
            override val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true, supportsToolCalling = true)
            private var step = 0

            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean = true
            override suspend fun startConversation(agentConfig: AgentConfig): Boolean = true

            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                return if (step++ == 0) {
                    Result.success("""{"tool": "call_contact", "arguments": {}}""")
                } else {
                    Result.success("""{"response": "Calling Alice."}""")
                }
            }

            override fun cancel() {}
            override fun release() {}
        }

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = toolRegistry,
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        // Send message invoking gated tool
        viewModel.sendMessage("Call Alice")
        // Advance slightly so LLM generates tool call and reaches confirmation gate
        testScheduler.runCurrent()

        val pending = viewModel.pendingConfirmation.value
        org.junit.Assert.assertNotNull(pending)
        assertEquals("call_contact", pending?.toolName)
        assertEquals("Call Alice at +1234567890?", pending?.repeatBack)

        // Confirm
        viewModel.respondToConfirmation(true)
        advanceUntilIdle()

        assertEquals(null, viewModel.pendingConfirmation.value)
        assertTrue(executedTool)

        tempDir.deleteRecursively()
    }

    @Test
    fun `cancelGeneration clears pendingConfirmation`() = runTest(testDispatcher) {
        val dummyContext = ContextWrapper(null)
        val tempDir = Files.createTempDirectory("cvm_cancel_confirm_test").toFile()
        val store = ConversationStore(tempDir, ioDispatcher = testDispatcher)

        val gatedTool = object : dev.loki.android.core.tools.LocalTool {
            override val name: String = "call_contact"
            override val description: String = "Call contact"
            override val parameters: Map<String, dev.loki.android.core.tools.ToolParam> = emptyMap()
            override val requiresConfirmation: Boolean = true
            override fun describeAction(arguments: Map<String, Any?>): String = "Call Alice?"
            override suspend fun execute(context: android.content.Context, arguments: Map<String, Any?>): ToolResult {
                return ToolResult.success()
            }
        }

        val toolRegistry = ToolRegistry().apply { register(gatedTool) }
        val fakeLlm = object : LlmEngine {
            private val _modelState = MutableStateFlow<LlmModelState>(LlmModelState.Ready("TestModel"))
            override val modelState: StateFlow<LlmModelState> = _modelState.asStateFlow()
            override val capabilities: ModelCapabilities = ModelCapabilities(supportsText = true, supportsToolCalling = true)

            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?, runtimeConfig: RuntimeConfig, force: Boolean): Boolean = true
            override suspend fun startConversation(agentConfig: AgentConfig): Boolean = true

            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                return Result.success("""{"tool": "call_contact", "arguments": {}}""")
            }

            override fun cancel() {}
            override fun release() {}
        }

        val manager = ConversationManager(
            context = dummyContext,
            llmEngine = fakeLlm,
            toolRegistry = toolRegistry,
            ttsEngine = null,
            conversationStore = store,
            ioDispatcher = testDispatcher
        )
        val viewModel = ChatViewModel(conversationManager = manager)
        advanceUntilIdle()

        viewModel.sendMessage("Call Alice")
        testScheduler.runCurrent()

        org.junit.Assert.assertNotNull(viewModel.pendingConfirmation.value)

        viewModel.cancelGeneration()
        advanceUntilIdle()

        assertEquals(null, viewModel.pendingConfirmation.value)

        tempDir.deleteRecursively()
    }
}
