package dev.loki.android.core.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class AssistantSessionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tempDir: java.io.File
    private lateinit var defaultModelManager: dev.loki.android.core.models.ModelLibraryManager

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
        defaultModelManager = manager

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
    fun `handleFollowUpLoop with DIRECT_AUDIO converts speech PCM to WAV and invokes voiceSession with VOICE_FOLLOW_UP and no STT`() = runTest {
        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

        val fakeRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder() {
            override fun arm(): Boolean = true
            override suspend fun recordGatedUtterance(isCommitGated: () -> Boolean, onRmsUpdate: ((Float) -> Unit)?): FloatArray {
                return FloatArray(1600) { 0.5f }
            }
            override fun release() {}
        }
        session.audioRecorderFactory = { fakeRecorder }

        var sttInvoked = false
        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                sttInvoked = true
            }
            override suspend fun transcribeAudio(audioData: FloatArray, language: String): String {
                sttInvoked = true
                return "unexpected"
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val fakeTts = object : dev.loki.android.core.voice.tts.TtsEngine {
            override val isSpeaking: Boolean = false
            override val isReady: Boolean = true
            override fun speak(text: String, utteranceId: String, onStart: (() -> Unit)?, onDone: (() -> Unit)?, onError: ((String) -> Unit)?) {
                onDone?.invoke()
            }
            override fun stop() {}
            override fun release() {}
        }

        val fakeConversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = object : android.content.ContextWrapper(null) {},
            llmEngine = object : dev.loki.android.core.llm.LlmEngine {
                private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
                override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
                override fun isReady(): Boolean = true
                override suspend fun initializeAsync(modelPath: String?): Boolean = true
                override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("")
                override fun cancel() {}
                override fun release() {}
            },
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ttsEngine = fakeTts
        )

        var capturedAudioBytes: ByteArray? = null
        var capturedSource: String? = null
        var capturedUserInput: String? = null

        val testVoiceSession = object : dev.loki.android.core.conversation.ConversationSession(
            context = object : android.content.ContextWrapper(null) {},
            llmEngine = fakeConversationManager.llmEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        ) {
            override fun processUtterance(userInput: String, audioBytes: ByteArray?, enableTts: Boolean, source: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.conversation.ConversationEvent> = kotlinx.coroutines.flow.flow {
                capturedAudioBytes = audioBytes
                capturedSource = source
                capturedUserInput = userInput
                emit(dev.loki.android.core.conversation.ConversationEvent.Completed("Done!"))
            }
        }

        val result = session.handleFollowUpLoop(
            conversationManager = fakeConversationManager,
            voiceSession = testVoiceSession,
            sttEngine = fakeStt,
            initialResponseText = "Which Mom would you like to call?",
            language = "auto",
            useDirectAudio = true
        )

        assertFalse(sttInvoked)
        assertEquals("VOICE_FOLLOW_UP", capturedSource)
        assertEquals("", capturedUserInput)
        assertTrue(capturedAudioBytes != null && capturedAudioBytes!!.isNotEmpty())
        assertEquals("Done!", result)
        session.destroy()
    }

    @Test
    fun `handleFollowUpLoop with STT_TRANSCRIBE transcribes speech and invokes voiceSession with transcript`() = runTest {
        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

        val fakeRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder() {
            override fun arm(): Boolean = true
            override suspend fun recordGatedUtterance(isCommitGated: () -> Boolean, onRmsUpdate: ((Float) -> Unit)?): FloatArray {
                return FloatArray(1600) { 0.5f }
            }
            override fun release() {}
        }
        session.audioRecorderFactory = { fakeRecorder }

        var sttInvoked = false
        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {}
            override suspend fun transcribeAudio(audioData: FloatArray, language: String): String {
                sttInvoked = true
                return "Mom"
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val fakeTts = object : dev.loki.android.core.voice.tts.TtsEngine {
            override val isSpeaking: Boolean = false
            override val isReady: Boolean = true
            override fun speak(text: String, utteranceId: String, onStart: (() -> Unit)?, onDone: (() -> Unit)?, onError: ((String) -> Unit)?) {
                onDone?.invoke()
            }
            override fun stop() {}
            override fun release() {}
        }

        val fakeConversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = object : android.content.ContextWrapper(null) {},
            llmEngine = object : dev.loki.android.core.llm.LlmEngine {
                private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
                override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
                override fun isReady(): Boolean = true
                override suspend fun initializeAsync(modelPath: String?): Boolean = true
                override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("")
                override fun cancel() {}
                override fun release() {}
            },
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ttsEngine = fakeTts
        )

        var capturedAudioBytes: ByteArray? = null
        var capturedSource: String? = null
        var capturedUserInput: String? = null

        val testVoiceSession = object : dev.loki.android.core.conversation.ConversationSession(
            context = object : android.content.ContextWrapper(null) {},
            llmEngine = fakeConversationManager.llmEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        ) {
            override fun processUtterance(userInput: String, audioBytes: ByteArray?, enableTts: Boolean, source: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.conversation.ConversationEvent> = kotlinx.coroutines.flow.flow {
                capturedAudioBytes = audioBytes
                capturedSource = source
                capturedUserInput = userInput
                emit(dev.loki.android.core.conversation.ConversationEvent.Completed("Calling Mom."))
            }
        }

        val result = session.handleFollowUpLoop(
            conversationManager = fakeConversationManager,
            voiceSession = testVoiceSession,
            sttEngine = fakeStt,
            initialResponseText = "Which Mom would you like to call?",
            language = "auto",
            useDirectAudio = false
        )

        assertTrue(sttInvoked)
        assertEquals("VOICE_FOLLOW_UP", capturedSource)
        assertEquals("Mom", capturedUserInput)
        assertEquals(null, capturedAudioBytes)
        assertEquals("Calling Mom.", result)
        session.destroy()
    }

    @Test
    fun `handleFollowUpLoop with silent buffer retries and exits without invoking voiceSession`() = runTest {
        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

        val fakeRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder() {
            override fun arm(): Boolean = true
            override suspend fun recordGatedUtterance(isCommitGated: () -> Boolean, onRmsUpdate: ((Float) -> Unit)?): FloatArray {
                return FloatArray(1600) { 0.0f } // silent
            }
            override fun release() {}
        }
        session.audioRecorderFactory = { fakeRecorder }

        val fakeTts = object : dev.loki.android.core.voice.tts.TtsEngine {
            override val isSpeaking: Boolean = false
            override val isReady: Boolean = true
            override fun speak(text: String, utteranceId: String, onStart: (() -> Unit)?, onDone: (() -> Unit)?, onError: ((String) -> Unit)?) {
                onDone?.invoke()
            }
            override fun stop() {}
            override fun release() {}
        }

        val fakeConversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = object : android.content.ContextWrapper(null) {},
            llmEngine = object : dev.loki.android.core.llm.LlmEngine {
                private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
                override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
                override fun isReady(): Boolean = true
                override suspend fun initializeAsync(modelPath: String?): Boolean = true
                override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("")
                override fun cancel() {}
                override fun release() {}
            },
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ttsEngine = fakeTts
        )

        var processUtteranceInvoked = false
        val testVoiceSession = object : dev.loki.android.core.conversation.ConversationSession(
            context = object : android.content.ContextWrapper(null) {},
            llmEngine = fakeConversationManager.llmEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        ) {
            override fun processUtterance(userInput: String, audioBytes: ByteArray?, enableTts: Boolean, source: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.conversation.ConversationEvent> = kotlinx.coroutines.flow.flow {
                processUtteranceInvoked = true
                emit(dev.loki.android.core.conversation.ConversationEvent.Completed("Done"))
            }
        }

        val result = session.handleFollowUpLoop(
            conversationManager = fakeConversationManager,
            voiceSession = testVoiceSession,
            sttEngine = null,
            initialResponseText = "Which one?",
            language = "auto",
            useDirectAudio = true
        )

        assertFalse(processUtteranceInvoked)
        assertEquals("Which one?", result)
        session.destroy()
    }

    @Test
    fun `speakAndAwait suspends until TTS completes`() = runTest {
        val session = AssistantSession()
        var completed = false

        val fakeTts = object : dev.loki.android.core.voice.tts.TtsEngine {
            override val isSpeaking: Boolean = false
            override val isReady: Boolean = true
            override fun speak(text: String, utteranceId: String, onStart: (() -> Unit)?, onDone: (() -> Unit)?, onError: ((String) -> Unit)?) {
                completed = true
                onDone?.invoke()
            }
            override fun stop() {}
            override fun release() {}
        }

        session.speakAndAwait(fakeTts, "Hello world")
        assertTrue(completed)
        session.destroy()
    }

    @Test
    fun `isSilentBuffer correctly classifies silent and non-silent float arrays`() {
        assertTrue(AssistantSession.isSilentBuffer(FloatArray(0)))
        assertTrue(AssistantSession.isSilentBuffer(FloatArray(100) { 0f }))
        assertTrue(AssistantSession.isSilentBuffer(FloatArray(100) { 0.005f }))
        assertTrue(AssistantSession.isSilentBuffer(FloatArray(100) { -0.015f }))

        assertFalse(AssistantSession.isSilentBuffer(FloatArray(100) { if (it == 50) 0.05f else 0.001f }))
        assertFalse(AssistantSession.isSilentBuffer(FloatArray(100) { -0.1f }))
    }

    @Test
    fun `STT single turn returns to Idle silently without LLM call when no speech detected`() = runTest {
        var listenCount = 0
        var llmCalled = false

        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
                llmCalled = true
                return Result.success("""{"response": "Spurious response"}""")
            }
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry()
        )

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                listenCount++
                emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult(""))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val textModel = dev.loki.android.core.models.ModelRecord(
            id = "test-text-model",
            displayName = "Test Text Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = false, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        val asrModel = dev.loki.android.core.models.ModelRecord(
            id = "test-asr-model",
            displayName = "Test ASR Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_ASR,
            format = dev.loki.android.core.models.ModelFormat.TFLITE,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("asr.bin", "asr.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L
        )
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        storage.artifactFile("test-text-model", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        storage.artifactFile("test-asr-model", "asr.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val registry = dev.loki.android.core.models.ModelRegistry(storage)
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(
                dev.loki.android.core.models.ModelRuntime.LITERT_LM to textModel.id,
                dev.loki.android.core.models.ModelRuntime.LITERT_ASR to asrModel.id
            ),
            models = listOf(textModel, asrModel)
        ))
        val modelManager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_ASR) { true }
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine = fakeStt
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = modelManager
        }

        val session = AssistantSession()
        session.startTurn()

        // Single turn: one listen attempt, then straight to Idle
        testScheduler.advanceTimeBy(1000L)

        assertEquals(1, listenCount)
        assertFalse(llmCalled)
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `successful answered turn holds Completed state with response text`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
                return Result.success("""{"response": "The time is 3:00 PM"}""")
            }
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("what time is it"))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val textModel = dev.loki.android.core.models.ModelRecord(
            id = "test-text-model-2",
            displayName = "Test Text Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = false, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        val asrModel = dev.loki.android.core.models.ModelRecord(
            id = "test-asr-model-2",
            displayName = "Test ASR Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_ASR,
            format = dev.loki.android.core.models.ModelFormat.TFLITE,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("asr.bin", "asr.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L
        )
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        storage.artifactFile("test-text-model-2", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        storage.artifactFile("test-asr-model-2", "asr.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val registry = dev.loki.android.core.models.ModelRegistry(storage)
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(
                dev.loki.android.core.models.ModelRuntime.LITERT_LM to textModel.id,
                dev.loki.android.core.models.ModelRuntime.LITERT_ASR to asrModel.id
            ),
            models = listOf(textModel, asrModel)
        ))
        val modelManager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_ASR) { true }
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine = fakeStt
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = modelManager
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.startTurn()

        testScheduler.advanceUntilIdle()

        val state = session.state.value
        assertTrue("State should be Completed after answered turn, was: $state", state is AssistantState.Completed)
        assertEquals("The time is 3:00 PM", (state as AssistantState.Completed).responseText)

        // Clear on next startTurn
        session.startTurn()
        assertTrue(session.state.value is AssistantState.Listening)

        // Clear on dismiss
        session.dismiss()
        assertEquals(AssistantState.Idle, session.state.value)

        session.destroy()
    }

    @Test
    fun `STT turn with error falls back to Idle`() = runTest {
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
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                emit(dev.loki.android.core.voice.stt.SttEvent.Error(RuntimeException("STT mic failure")))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val textModel = dev.loki.android.core.models.ModelRecord(
            id = "test-text-model-3",
            displayName = "Test Text Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = false, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        val asrModel = dev.loki.android.core.models.ModelRecord(
            id = "test-asr-model-3",
            displayName = "Test ASR Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_ASR,
            format = dev.loki.android.core.models.ModelFormat.TFLITE,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("asr.bin", "asr.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L
        )
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        storage.artifactFile("test-text-model-3", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        storage.artifactFile("test-asr-model-3", "asr.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val registry = dev.loki.android.core.models.ModelRegistry(storage)
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(
                dev.loki.android.core.models.ModelRuntime.LITERT_LM to textModel.id,
                dev.loki.android.core.models.ModelRuntime.LITERT_ASR to asrModel.id
            ),
            models = listOf(textModel, asrModel)
        ))
        val modelManager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_ASR) { true }
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine = fakeStt
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = modelManager
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.startTurn()

        testScheduler.advanceUntilIdle()

        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }

    @Test
    fun `follow-up round triggers after a question-ending response and feeds transcript back as new turn`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val promptsReceived = mutableListOf<String>()
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
                promptsReceived.add(prompt)
                return if (promptsReceived.size == 1) {
                    Result.success("""{"response": "Rahul has two numbers. Which one should I call?"}""")
                } else {
                    Result.success("""{"response": "Calling Rahul mobile."}""")
                }
            }
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        var sttCalls = 0
        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                sttCalls++
                if (sttCalls == 1) {
                    emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("call Rahul"))
                } else {
                    emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("the mobile one"))
                }
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val textModel = dev.loki.android.core.models.ModelRecord(
            id = "test-text-model-fu1",
            displayName = "Test Text Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = false, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        val asrModel = dev.loki.android.core.models.ModelRecord(
            id = "test-asr-model-fu1",
            displayName = "Test ASR Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_ASR,
            format = dev.loki.android.core.models.ModelFormat.TFLITE,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("asr.bin", "asr.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L
        )
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        storage.artifactFile("test-text-model-fu1", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        storage.artifactFile("test-asr-model-fu1", "asr.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val registry = dev.loki.android.core.models.ModelRegistry(storage)
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(
                dev.loki.android.core.models.ModelRuntime.LITERT_LM to textModel.id,
                dev.loki.android.core.models.ModelRuntime.LITERT_ASR to asrModel.id
            ),
            models = listOf(textModel, asrModel)
        ))
        val modelManager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_ASR) { true }
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine = fakeStt
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = modelManager
        }

        val fakeRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            override fun arm(): Boolean = true
            override suspend fun recordGatedUtterance(isCommitGated: () -> Boolean, onRmsUpdate: ((Float) -> Unit)?): FloatArray = FloatArray(0)
            override fun release() {}
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.audioRecorderFactory = { fakeRecorder }
        session.startTurn()

        testScheduler.advanceUntilIdle()

        val state = session.state.value
        assertTrue("Expected Completed state but was: $state", state is AssistantState.Completed)
        assertEquals("Calling Rahul mobile.", (state as AssistantState.Completed).responseText)
        assertEquals(2, promptsReceived.size)
        session.destroy()
    }

    @Test
    fun `timeout or silence in follow-up round retries once then completes with question response`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val spokenTexts = mutableListOf<String>()
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
                return Result.success("""{"response": "Which contact should I call?"}""")
            }
            override fun cancel() {}
            override fun release() {}
        }
        val fakeTts = object : dev.loki.android.core.voice.tts.TtsEngine {
            override val isSpeaking: Boolean = false
            override val isReady: Boolean = true
            override fun speak(text: String, utteranceId: String, onStart: (() -> Unit)?, onDone: (() -> Unit)?, onError: ((String) -> Unit)?) {
                spokenTexts.add(text)
                onDone?.invoke()
            }
            override fun stop() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ttsEngine = fakeTts,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        var sttCalls = 0
        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                sttCalls++
                if (sttCalls == 1) {
                    emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult("call mom"))
                } else {
                    // Empty speech on follow-up and retry
                    emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult(""))
                }
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        val textModel = dev.loki.android.core.models.ModelRecord(
            id = "test-text-model-fu2",
            displayName = "Test Text Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_LM,
            format = dev.loki.android.core.models.ModelFormat.LITERT_MODEL,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("m.bin", "m.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L,
            capabilities = dev.loki.android.core.models.ModelRecordCapabilities(
                audioInput = dev.loki.android.core.models.ModelMetadataField(value = false, confidence = dev.loki.android.core.models.MetadataConfidence.VERIFIED)
            )
        )
        val asrModel = dev.loki.android.core.models.ModelRecord(
            id = "test-asr-model-fu2",
            displayName = "Test ASR Model",
            runtime = dev.loki.android.core.models.ModelRuntime.LITERT_ASR,
            format = dev.loki.android.core.models.ModelFormat.TFLITE,
            availability = dev.loki.android.core.models.ModelAvailability.LOADED,
            artifacts = listOf(dev.loki.android.core.models.ModelArtifact("asr.bin", "asr.bin", 100L, url = "")),
            source = dev.loki.android.core.models.ModelSource.BUNDLED_CATALOG,
            importedAtEpochMs = 1L
        )
        val storage = dev.loki.android.core.models.ModelStorage(tempDir)
        storage.artifactFile("test-text-model-fu2", "m.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        storage.artifactFile("test-asr-model-fu2", "asr.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val registry = dev.loki.android.core.models.ModelRegistry(storage)
        registry.save(dev.loki.android.core.models.ModelManifest(
            activeModels = mapOf(
                dev.loki.android.core.models.ModelRuntime.LITERT_LM to textModel.id,
                dev.loki.android.core.models.ModelRuntime.LITERT_ASR to asrModel.id
            ),
            models = listOf(textModel, asrModel)
        ))
        val modelManager = dev.loki.android.core.models.ModelLibraryManager(storage, registry)
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_ASR) { true }
        modelManager.registerReadinessProvider(dev.loki.android.core.models.ModelRuntime.LITERT_LM) { true }

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine = fakeStt
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = modelManager
        }

        val fakeFollowUpRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            override fun arm(): Boolean = true
            override suspend fun recordGatedUtterance(isCommitGated: () -> Boolean, onRmsUpdate: ((Float) -> Unit)?): FloatArray = FloatArray(0)
            override fun release() {}
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.audioRecorderFactory = { fakeFollowUpRecorder }
        session.startTurn()

        testScheduler.advanceUntilIdle()

        val state = session.state.value
        assertTrue("Expected Completed state but was: $state", state is AssistantState.Completed)
        assertEquals("Which contact should I call?", (state as AssistantState.Completed).responseText)
        assertTrue(spokenTexts.contains("Which contact should I call?"))
        assertTrue(spokenTexts.contains("I didn't catch that"))
        session.destroy()
    }

    @Test
    fun `recorder is released on all exits of handleFollowUpLoop`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("{}")
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        val voiceSession = conversationManager.newVoiceSession()

        var recorderReleased = false
        val customRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            override fun arm(): Boolean = true
            override suspend fun recordGatedUtterance(isCommitGated: () -> Boolean, onRmsUpdate: ((Float) -> Unit)?): FloatArray = FloatArray(0)
            override fun release() {
                recorderReleased = true
                super.release()
            }
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.audioRecorderFactory = { customRecorder }

        val fakeStt = object : dev.loki.android.core.voice.stt.SttEngine {
            override val isListening: Boolean = false
            override fun startListening(language: String): kotlinx.coroutines.flow.Flow<dev.loki.android.core.voice.stt.SttEvent> = kotlinx.coroutines.flow.flow {
                emit(dev.loki.android.core.voice.stt.SttEvent.FinalResult(""))
            }
            override fun stopListening() {}
            override fun cancel() {}
            override fun release() {}
        }

        session.handleFollowUpLoop(
            conversationManager = conversationManager,
            voiceSession = voiceSession,
            sttEngine = fakeStt,
            initialResponseText = "Which Mom?"
        )

        assertTrue("Recorder must be released on loop exit", recorderReleased)
        session.destroy()
    }

    @Test
    fun `direct audio turn handles MicUnavailableException gracefully without crashing`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> = Result.success("{}")
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine? = null
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = defaultModelManager
        }

        val failingRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            override suspend fun recordUtterance(onRmsUpdate: ((Float) -> Unit)?): FloatArray {
                throw dev.loki.android.core.voice.stt.MicUnavailableException(
                    dev.loki.android.core.voice.stt.MicUnavailableReason.PERMISSION_DENIED,
                    "Permission denied"
                )
            }
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.audioRecorderFactory = { failingRecorder }

        val states = mutableListOf<AssistantState>()
        val collectJob = kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
            session.state.collect { states.add(it) }
        }

        session.startTurn()
        testScheduler.advanceUntilIdle()

        val errorState = states.filterIsInstance<AssistantState.Error>().firstOrNull()
        assertTrue("State should transition through Error when mic is unavailable", errorState != null)
        assertEquals("Microphone unavailable — grant mic permission", errorState?.message)
        assertEquals(AssistantState.Idle, session.state.value)
        collectJob.cancel()
        session.destroy()
    }

    @Test
    fun `two consecutive startTurn calls use independent ConversationSessions with empty initial context`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val recordedTurnsCounts = mutableListOf<Int>()
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
                return Result.success("""{"response": "Hello"}""")
            }
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine? = null
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = defaultModelManager
        }

        val nonSilentAudio = FloatArray(16000) { 0.5f }
        val testRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            override suspend fun recordUtterance(onRmsUpdate: ((Float) -> Unit)?): FloatArray = nonSilentAudio
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.audioRecorderFactory = { testRecorder }

        // Turn 1
        session.startTurn()
        testScheduler.advanceUntilIdle()
        assertTrue(session.state.value is AssistantState.Completed)

        // Turn 2: Starts fresh voice session, independent of Turn 1
        val turn2Session = conversationManager.newVoiceSession()
        assertEquals(0, turn2Session.conversationContext.getTurns().size)
        assertEquals(1, turn2Session.conversationContext.maxTurns)

        session.startTurn()
        testScheduler.advanceUntilIdle()
        assertTrue(session.state.value is AssistantState.Completed)

        session.destroy()
    }

    @Test
    fun `direct audio turn skips LLM generation on empty or silent audio capture`() = runTest {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        var generateCalled = false
        val dummyEngine = object : dev.loki.android.core.llm.LlmEngine {
            private val _state = kotlinx.coroutines.flow.MutableStateFlow<dev.loki.android.core.llm.LlmModelState>(dev.loki.android.core.llm.LlmModelState.Ready())
            override val modelState: kotlinx.coroutines.flow.StateFlow<dev.loki.android.core.llm.LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(prompt: String, audioBytes: ByteArray?, grammar: String?, maxTokens: Int, onToken: ((String) -> Unit)?): Result<String> {
                generateCalled = true
                return Result.success("""{"response": "Hello"}""")
            }
            override fun cancel() {}
            override fun release() {}
        }
        val conversationManager = dev.loki.android.core.conversation.ConversationManager(
            context = dummyContext,
            llmEngine = dummyEngine,
            toolRegistry = dev.loki.android.core.tools.ToolRegistry(),
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )

        AssistantSessionProvider.instance = object : AssistantSessionProvider {
            override fun getConversationManager(): dev.loki.android.core.conversation.ConversationManager = conversationManager
            override fun getSttEngine(): dev.loki.android.core.voice.stt.SttEngine? = null
            override fun getModelLibraryManager(): dev.loki.android.core.models.ModelLibraryManager = defaultModelManager
        }

        // Empty audio recorder
        val emptyRecorder = object : dev.loki.android.core.voice.stt.AudioRecorder(
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        ) {
            override suspend fun recordUtterance(onRmsUpdate: ((Float) -> Unit)?): FloatArray = FloatArray(0)
        }

        val session = AssistantSession()
        session.ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        session.audioRecorderFactory = { emptyRecorder }

        session.startTurn()
        testScheduler.advanceUntilIdle()

        assertFalse("LLM generate must NOT be called when audio capture is empty", generateCalled)
        assertEquals(AssistantState.Idle, session.state.value)
        session.destroy()
    }
}
