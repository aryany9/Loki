package dev.loki.android.core.assistant

import android.util.Log
import dev.loki.android.core.conversation.ConfirmationOutcome
import dev.loki.android.core.llm.LlmEngine

/**
 * Stateless, grammar-constrained LLM inference wrapper that classifies a user's voice
 * response to a pending confirmation question.
 *
 * Accepts either:
 * - [audioBytes]: WAV-encoded PCM bytes (DirectAudio path — passed directly to the audio LLM)
 * - [transcript]: Pre-transcribed text (STT-Transcribe path — embedded in the prompt)
 *
 * Exactly one of the two should be non-null per call.
 *
 * The model output is grammar-constrained to `"CONFIRMED" | "DECLINED" | "UNKNOWN"` via GBNF,
 * eliminating the failure mode where the 2B audio encoder produces a free-form question text.
 *
 * This class is stateless — no KV cache, no conversation history, no session lifecycle.
 * Each call to [resolve] is an independent [LlmEngine.generate] invocation.
 */
object ConfirmationResolver {

    private const val TAG = "ConfirmationResolver"

    /**
     * Regex pattern constraining the model output to exactly one of the three semantic tokens.
     * Passed to [LlmEngine.generate] as the `grammar` parameter, which is forwarded to
     * [com.google.ai.edge.litertlm.ResponseFormat.regex] at the native sampler level.
     *
     * Note: the LlmEngine grammar parameter uses regex syntax (not GBNF), since the LiteRT SDK
     * exposes [ResponseFormat.regex] rather than a GBNF sampler.
     */
    private const val GRAMMAR = "CONFIRMED|DECLINED|UNKNOWN"

    /**
     * Classifies the user's spoken or transcribed response to [question] as
     * [ConfirmationOutcome.CONFIRMED], [ConfirmationOutcome.DECLINED], or
     * [ConfirmationOutcome.UNKNOWN].
     *
     * @param audioBytes WAV-encoded PCM bytes for DirectAudio devices; null on STT-Transcribe path.
     * @param transcript Pre-transcribed text for STT-Transcribe devices; null on DirectAudio path.
     * @param question   The confirmation question that was asked (from `pendingVoiceAsk.question`).
     * @param llmEngine  The existing [LlmEngine] instance — no new model download or lifecycle.
     * @return [ConfirmationOutcome] based on the grammar-constrained model output.
     */
    suspend fun resolve(
        audioBytes: ByteArray?,
        transcript: String?,
        question: String,
        llmEngine: LlmEngine
    ): ConfirmationOutcome {
        val prompt = buildPrompt(question, transcript)

        Log.d(TAG, "resolve() — audioBytes=${audioBytes?.size ?: 0}b, " +
                "transcript=${transcript?.let { "\"${it.take(50)}\"" } ?: "null"}")

        val result = llmEngine.generate(
            prompt = prompt,
            audioBytes = audioBytes,
            grammar = GRAMMAR,
            maxTokens = 32
        )

        if (result.isFailure) {
            Log.w(TAG, "LLM generate failed: ${result.exceptionOrNull()?.message}; defaulting to UNKNOWN")
            return ConfirmationOutcome.UNKNOWN
        }

        val raw = result.getOrDefault("").trim()
        val outcome = ConfirmationOutcome.from(raw)
        Log.d(TAG, "resolve() → raw=\"$raw\", outcome=$outcome")
        return outcome
    }

    /**
     * Constructs the stripped classification prompt.
     *
     * The model operates with a JSON-tool system prompt in its KV cache, so
     * we lean into that format — asking it to respond with a JSON object
     * containing a `label` field. [ConfirmationOutcome.from] handles both the
     * bare-token and JSON-envelope forms via case-insensitive substring search.
     *
     * For the STT-Transcribe path, [transcript] is embedded as the user's response.
     * For the DirectAudio path, [transcript] is null and the audio bytes carry the signal.
     */
    internal fun buildPrompt(question: String, transcript: String?): String {
        val responseSection = if (transcript != null) {
            "User's response: \"$transcript\""
        } else {
            // DirectAudio: audio bytes provided separately; prompt indicates audio input.
            "User's response: [audio]"
        }

        return """
You asked the user: "$question"

$responseSection

Classify the user's response. Reply with ONLY one of these exact labels: CONFIRMED, DECLINED, or UNKNOWN.

CONFIRMED — The user clearly agreed or affirmed (examples: "yes", "sure", "go ahead", "do it",
  "haan", "haan karo", "theek hai", "karo", "okay", "yep", "absolutely").

DECLINED — The user clearly refused or cancelled (examples: "no", "nahi", "cancel", "don't",
  "mat karo", "ruk ja", "stop", "nevermind", "nope", "nahi chahiye").

UNKNOWN — The response is ambiguous, unclear, unrelated, or you cannot determine the intent.

Respond with exactly one label: CONFIRMED, DECLINED, or UNKNOWN.
        """.trimIndent()
    }
}
