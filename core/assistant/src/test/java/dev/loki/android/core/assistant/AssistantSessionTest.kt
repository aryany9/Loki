package dev.loki.android.core.assistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantSessionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: java.io.File

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        tempDir = java.nio.file.Files.createTempDirectory("loki-test-assistant-default").toFile()
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        val registry = dev.loki.android.core.models.ModelRegistry(storage)

        storage.artifactFile("default-audio-model", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val audioModel = dev.loki.android.core.models.ModelRecord(
            id = "default-audio-model",
            displayName = "Default Audio Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(
                dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")
            ),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = true, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(dev.loki.android.core.models.ModelRuntime.LITERT_LM to audioModel.id),
            models = listOf(audioModel)
        ))
        val manager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        manager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager? = null
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine? = null
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = manager
        }
    }

    @After
    fun tearDown() {
        AssistantSessionProvider.instance = null
        tempDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() {
        val session = AssistantSession()
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `startTurn transitions to Listening`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        assertTrue(session.state.value is AssistantState.Listening)
        session.destroy()
    }

    @Test
    fun `updateTranscript updates Listening state with partial transcript`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.updateTranscript("what time")
        val state = session.state.value
        assertTrue(state is AssistantState.Listening)
        assertEquals("what time", (state as AssistantState.Listening).partialTranscript)
        session.destroy()
    }

    @Test
    fun `updateProcessing transitions to Processing`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.updateProcessing("what time is it")
        val state = session.state.value
        assertTrue(state is AssistantState.Processing)
        assertEquals("what time is it", (state as AssistantState.Processing).query)
        session.destroy()
    }

    @Test
    fun `updateSpeaking transitions to Speaking`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.updateSpeaking("It's 4:00 PM")
        val state = session.state.value
        assertTrue(state is AssistantState.Speaking)
        assertEquals("It's 4:00 PM", (state as AssistantState.Speaking).responseText)
        session.destroy()
    }

    @Test
    fun `cancelTurn resets state to Idle`() = runTest {
        val session = AssistantSession()
        session.startTurn()
        session.cancelTurn()
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `dismiss calls onDismiss callback and resets to Idle`() = runTest {
        var dismissed = false
        val session = AssistantSession(onDismissCallback = { dismissed = true })
        session.startTurn()
        session.dismiss()
        assertTrue(dismissed)
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `direct-audio strategy does not require LITERT_ASR readiness`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("loki-test-session").toFile()
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        val registry = dev.loki.android.core.models.ModelRegistry(storage)

        storage.artifactFile("gemma-4-audio", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val audioModel = dev.loki.android.core.models.ModelRecord(
            id = "gemma-4-audio",
            displayName = "Gemma 4 Audio",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(
                dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")
            ),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = true, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(dev.loki.android.core.models.ModelRuntime.LITERT_LM to audioModel.id),
            models = listOf(audioModel)
        ))
        val manager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        manager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }
        // LITERT_ASR is NOT ready

        val mockProvider = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager? = null
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine? = null
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = manager
        }
        AssistantSessionProvider.instance = mockProvider

        val session = AssistantSession()
        session.startTurn()

        val state = session.state.value
        assertTrue(state is AssistantState.Listening)
        assertEquals(VoiceInputStrategy.DIRECT_AUDIO, (state as AssistantState.Listening).strategy)

        AssistantSessionProvider.instance = null
        session.destroy()
        tempDir.deleteRecursively()
    }

    @Test
    fun `stt-transcribe strategy fails fast when LITERT_ASR is not ready`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("loki-test-session-2").toFile()
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        val registry = dev.loki.android.core.models.ModelRegistry(storage)

        storage.artifactFile("qwen-text", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        val textModel = dev.loki.android.core.models.ModelRecord(
            id = "qwen-text",
            displayName = "Qwen Text",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(
                dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")
            ),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities()
        )
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(dev.loki.android.core.models.ModelRuntime.LITERT_LM to textModel.id),
            models = listOf(textModel)
        ))
        val manager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        manager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }
        // LITERT_ASR is NOT ready

        val mockProvider = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager? = null
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine? = null
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = manager
        }
        AssistantSessionProvider.instance = mockProvider

        val session = AssistantSession()
        session.startTurn()

        val state = session.state.value
        assertTrue(state is AssistantState.Error)
        assertEquals("Voice recognition model not loaded. Please complete setup.", (state as AssistantState.Error).message)

        AssistantSessionProvider.instance = null
        session.destroy()
        tempDir.deleteRecursively()
    }

    @Test
    fun `initial amplitude is 0f`() {
        val session = AssistantSession()
        assertEquals(0f, session.amplitude.value)
        session.destroy()
    }

    @Test
    fun `normalizeRms produces values in 0 to 1 and clamps extremes`() {
        assertEquals(0f, AssistantSession.normalizeRms(0f), 0.0001f)
        assertEquals(0f, AssistantSession.normalizeRms(-500f), 0.0001f)
        assertEquals(0.5f, AssistantSession.normalizeRms(4000f, ceiling = 8000f), 0.0001f)
        assertEquals(1.0f, AssistantSession.normalizeRms(8000f, ceiling = 8000f), 0.0001f)
        assertEquals(1.0f, AssistantSession.normalizeRms(20000f, ceiling = 8000f), 0.0001f)
    }

    @Test
    fun `smoothRms performs one-pole low pass filter correctly`() {
        val current = 0.2f
        val target = 0.8f
        val smoothed = AssistantSession.smoothRms(current, target, alpha = 0.4f)
        // 0.2 * 0.6 + 0.8 * 0.4 = 0.12 + 0.32 = 0.44
        assertEquals(0.44f, smoothed, 0.001f)
    }

    @Test
    fun `amplitude smoothing is monotonic under rising and falling RMS streams`() {
        var current = 0f
        val risingStream = listOf(1000f, 2000f, 4000f, 6000f, 8000f)
        val risingValues = mutableListOf<Float>()

        for (rms in risingStream) {
            val normalized = AssistantSession.normalizeRms(rms)
            current = AssistantSession.smoothRms(current, normalized)
            risingValues.add(current)
        }

        // Verify strictly increasing
        for (i in 0 until risingValues.size - 1) {
            assertTrue(
                "Expected rising stream to be monotonically increasing: ${risingValues[i]} < ${risingValues[i+1]}",
                risingValues[i] < risingValues[i+1]
            )
        }

        // Verify values are in 0..1
        assertTrue(risingValues.all { it in 0f..1f })

        // Falling stream
        val fallingStream = listOf(6000f, 4000f, 2000f, 500f, 0f)
        val fallingValues = mutableListOf<Float>()
        for (rms in fallingStream) {
            val normalized = AssistantSession.normalizeRms(rms)
            current = AssistantSession.smoothRms(current, normalized)
            fallingValues.add(current)
        }

        // Verify strictly decreasing
        for (i in 0 until fallingValues.size - 1) {
            assertTrue(
                "Expected falling stream to be monotonically decreasing: ${fallingValues[i]} > ${fallingValues[i+1]}",
                fallingValues[i] > fallingValues[i+1]
            )
        }
    }

    @Test
    fun `processRawRms updates amplitude state flow and resetAmplitude resets to 0`() {
        val session = AssistantSession()
        session.processRawRms(6000f)
        assertTrue(session.amplitude.value > 0f)
        assertTrue(session.amplitude.value <= 1f)

        session.resetAmplitude()
        assertEquals(0f, session.amplitude.value)
        session.destroy()
    }

    @Test
    fun `ConfirmationKeywords correctly parses yes, no, and unrecognized inputs`() {
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("yes"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("Yes, please"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("yeah"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("sure"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("confirm"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("ok"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("okay"))
        assertEquals(ConfirmationKeywords.Verdict.ACCEPTED, ConfirmationKeywords.parseVerdict("do it"))

        assertEquals(ConfirmationKeywords.Verdict.DENIED, ConfirmationKeywords.parseVerdict("no"))
        assertEquals(ConfirmationKeywords.Verdict.DENIED, ConfirmationKeywords.parseVerdict("cancel"))
        assertEquals(ConfirmationKeywords.Verdict.DENIED, ConfirmationKeywords.parseVerdict("stop"))
        assertEquals(ConfirmationKeywords.Verdict.DENIED, ConfirmationKeywords.parseVerdict("don't"))
        assertEquals(ConfirmationKeywords.Verdict.DENIED, ConfirmationKeywords.parseVerdict("dont do it"))

        assertEquals(ConfirmationKeywords.Verdict.UNRECOGNIZED, ConfirmationKeywords.parseVerdict("what is the weather"))
        assertEquals(ConfirmationKeywords.Verdict.UNRECOGNIZED, ConfirmationKeywords.parseVerdict("hello world"))
    }

    @Test
    fun `handleVerbalConfirmation resolves accepted when user says yes`() = runTest {
        val session = AssistantSession()

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("")
            override fun cancel() {}
            override fun release() {}
        }
        val voiceSession = dev.loki.android.core.conversation.ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        )

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("yes"))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        session.handleVerbalConfirmation(
            voiceSession = voiceSession,
            sttEngine = fakeStt,
            ttsEngine = null,
            repeatBack = "Call Mom?"
        )

        val state = session.state.value
        assertTrue(state is AssistantState.Processing)
        assertEquals("yes", (state as AssistantState.Processing).query)
        session.destroy()
    }

    @Test
    fun `handleVerbalConfirmation resolves denied when user says no`() = runTest {
        val session = AssistantSession()

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("")
            override fun cancel() {}
            override fun release() {}
        }
        val voiceSession = dev.loki.android.core.conversation.ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        )

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("cancel that"))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        session.handleVerbalConfirmation(
            voiceSession = voiceSession,
            sttEngine = fakeStt,
            ttsEngine = null,
            repeatBack = "Call Mom?"
        )

        val state = session.state.value
        assertTrue(state is AssistantState.Processing)
        assertEquals("cancel that", (state as AssistantState.Processing).query)
        session.destroy()
    }

    @Test
    fun `handleVerbalConfirmation re-prompts once on unrecognized input then resolves on second turn`() = runTest {
        val session = AssistantSession()
        var callCount = 0

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("")
            override fun cancel() {}
            override fun release() {}
        }
        val voiceSession = dev.loki.android.core.conversation.ConversationSession(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        )

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                if (callCount++ == 0) {
                    emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("what did you say"))
                } else {
                    emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("yeah"))
                }
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        session.handleVerbalConfirmation(
            voiceSession = voiceSession,
            sttEngine = fakeStt,
            ttsEngine = null,
            repeatBack = "Call Mom?"
        )

        assertEquals(2, callCount)
        val state = session.state.value
        assertTrue(state is AssistantState.Processing)
        assertEquals("yeah", (state as AssistantState.Processing).query)
        session.destroy()
    }
}
