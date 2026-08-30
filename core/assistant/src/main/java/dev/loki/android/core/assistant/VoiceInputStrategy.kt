package dev.loki.android.core.assistant

/**
 * Strategy used for a voice turn:
 * - [DIRECT_AUDIO]: Direct multimodal audio input sent to the LLM (for audio-capable models).
 * - [STT_TRANSCRIBE]: Offline STT transcription using LiteRtWhisperEngine before LLM inference (for text-only models).
 */
enum class VoiceInputStrategy {
    DIRECT_AUDIO,
    STT_TRANSCRIBE
}
