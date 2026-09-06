package dev.loki.android.core.assistant

import dev.loki.android.core.conversation.ConfirmationOutcome
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.llm.LlmModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ConfirmationResolverTest {

    private fun mockEngine(response: String): LlmEngine = object : LlmEngine {
        private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _state
        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true
        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> = Result.success(response)
        override fun cancel() {}
        override fun release() {}
    }

    private fun failingEngine(): LlmEngine = object : LlmEngine {
        private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
        override val modelState: StateFlow<LlmModelState> = _state
        override fun isReady(): Boolean = true
        override suspend fun initializeAsync(modelPath: String?): Boolean = true
        override suspend fun generate(
            prompt: String,
            audioBytes: ByteArray?,
            grammar: String?,
            maxTokens: Int,
            onToken: ((String) -> Unit)?
        ): Result<String> = Result.failure(RuntimeException("Engine failure"))
        override fun cancel() {}
        override fun release() {}
    }

    // ─── Outcome mapping ───────────────────────────────────────────────────────

    @Test
    fun `engine output CONFIRMED maps to ConfirmationOutcome CONFIRMED`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "yes",
            question = "Shall I call Mom?",
            llmEngine = mockEngine("CONFIRMED")
        )
        assertEquals(ConfirmationOutcome.CONFIRMED, outcome)
    }

    @Test
    fun `engine output DECLINED maps to ConfirmationOutcome DECLINED`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "no",
            question = "Shall I call Mom?",
            llmEngine = mockEngine("DECLINED")
        )
        assertEquals(ConfirmationOutcome.DECLINED, outcome)
    }

    @Test
    fun `engine output UNKNOWN maps to ConfirmationOutcome UNKNOWN`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "maybe",
            question = "Shall I call Mom?",
            llmEngine = mockEngine("UNKNOWN")
        )
        assertEquals(ConfirmationOutcome.UNKNOWN, outcome)
    }

    @Test
    fun `malformed engine output maps to UNKNOWN as safe default`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "sure",
            question = "Shall I call Mom?",
            llmEngine = mockEngine("Shall I call Mom, the number ending in 90?")
        )
        assertEquals(ConfirmationOutcome.UNKNOWN, outcome)
    }

    @Test
    fun `empty engine output maps to UNKNOWN`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "yes",
            question = "Shall I call Mom?",
            llmEngine = mockEngine("")
        )
        assertEquals(ConfirmationOutcome.UNKNOWN, outcome)
    }

    @Test
    fun `engine output with whitespace trimmed before mapping`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "haan",
            question = "Shall I call Mom?",
            llmEngine = mockEngine("  CONFIRMED  ")
        )
        assertEquals(ConfirmationOutcome.CONFIRMED, outcome)
    }

    // ─── Engine failure ─────────────────────────────────────────────────────────

    @Test
    fun `engine failure returns UNKNOWN as safe default`() = runTest {
        val outcome = ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "yes",
            question = "Shall I call Mom?",
            llmEngine = failingEngine()
        )
        assertEquals(ConfirmationOutcome.UNKNOWN, outcome)
    }

    // ─── Input routing ──────────────────────────────────────────────────────────

    @Test
    fun `null audio and non-null transcript uses STT-Transcribe path`() = runTest {
        var capturedAudio: ByteArray? = ByteArray(1) // sentinel non-null
        val engine = object : LlmEngine {
            private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
            override val modelState: StateFlow<LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                capturedAudio = audioBytes
                return Result.success("CONFIRMED")
            }
            override fun cancel() {}
            override fun release() {}
        }

        ConfirmationResolver.resolve(
            audioBytes = null,
            transcript = "yes please",
            question = "Shall I call Mom?",
            llmEngine = engine
        )

        // On STT-Transcribe path, audioBytes passed to engine should be null
        assertEquals(null, capturedAudio)
    }

    @Test
    fun `non-null audio and null transcript uses DirectAudio path with audio bytes`() = runTest {
        val fakeAudio = ByteArray(1024) { 0x42 }
        var capturedAudio: ByteArray? = null
        val engine = object : LlmEngine {
            private val _state = MutableStateFlow<LlmModelState>(LlmModelState.Ready())
            override val modelState: StateFlow<LlmModelState> = _state
            override fun isReady(): Boolean = true
            override suspend fun initializeAsync(modelPath: String?): Boolean = true
            override suspend fun generate(
                prompt: String,
                audioBytes: ByteArray?,
                grammar: String?,
                maxTokens: Int,
                onToken: ((String) -> Unit)?
            ): Result<String> {
                capturedAudio = audioBytes
                return Result.success("DECLINED")
            }
            override fun cancel() {}
            override fun release() {}
        }

        ConfirmationResolver.resolve(
            audioBytes = fakeAudio,
            transcript = null,
            question = "Shall I call Mom?",
            llmEngine = engine
        )

        // On DirectAudio path, audio bytes should be forwarded as-is to the engine
        assertTrue(capturedAudio != null && capturedAudio!!.isNotEmpty())
        assertEquals(fakeAudio.size, capturedAudio!!.size)
    }

    // ─── Prompt construction ────────────────────────────────────────────────────

    @Test
    fun `buildPrompt with transcript embeds response in prompt`() {
        val prompt = ConfirmationResolver.buildPrompt("Shall I call Mom?", "yes go ahead")
        assertTrue(prompt.contains("Shall I call Mom?"))
        assertTrue(prompt.contains("yes go ahead"))
        assertFalse(prompt.contains("[audio]"))
    }

    @Test
    fun `buildPrompt with null transcript uses audio placeholder`() {
        val prompt = ConfirmationResolver.buildPrompt("Shall I call Mom?", null)
        assertTrue(prompt.contains("Shall I call Mom?"))
        assertTrue(prompt.contains("[audio]"))
    }

    @Test
    fun `buildPrompt contains multilingual vocabulary anchors for all three outcomes`() {
        val prompt = ConfirmationResolver.buildPrompt("Shall I call Mom?", "haan")
        // CONFIRMED anchors
        assertTrue(prompt.contains("haan"))
        assertTrue(prompt.contains("sure"))
        // DECLINED anchors
        assertTrue(prompt.contains("nahi"))
        assertTrue(prompt.contains("cancel"))
        // UNKNOWN anchor description
        assertTrue(prompt.contains("UNKNOWN"))
    }

    @Test
    fun `buildPrompt does not contain conversation history or tool schemas`() {
        val prompt = ConfirmationResolver.buildPrompt("Shall I call Mom?", "yes")
        // Ensure no conversation turn markers or tool JSON in the prompt
        assertFalse(prompt.contains("\"tool\""))
        assertFalse(prompt.contains("\"arguments\""))
        assertFalse(prompt.contains("system"))
        assertFalse(prompt.contains("<turn>"))
    }
}
