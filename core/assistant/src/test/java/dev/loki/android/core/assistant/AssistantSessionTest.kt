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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        AssistantSessionProvider.instance = null
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
}
