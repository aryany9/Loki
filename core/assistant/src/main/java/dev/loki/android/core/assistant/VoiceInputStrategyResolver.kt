package dev.loki.android.core.assistant

import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.voice.stt.SttEngine

enum class VoiceUnavailableReason {
    NO_ACTIVE_MODEL,
    NOT_AUDIO_CAPABLE,
    STT_NOT_READY,
    AUDIO_PERMISSION_DENIED
}

sealed class VoiceInputStrategyResult {
    data object DirectAudio : VoiceInputStrategyResult()
    data object SttTranscribe : VoiceInputStrategyResult()
    data class Unavailable(val reason: VoiceUnavailableReason, val message: String) : VoiceInputStrategyResult()
}

open class VoiceInputStrategyResolver {

    open fun resolve(
        modelManager: ModelLibraryManager?,
        sttEngine: SttEngine? = null
    ): VoiceInputStrategyResult {
        val activeLlmId = modelManager?.manifest?.value?.activeModels?.get(ModelRuntime.LITERT_LM)
        val activeLlmRecord = modelManager?.manifest?.value?.models?.firstOrNull { it.id == activeLlmId }

        if (modelManager == null || activeLlmRecord == null) {
            return VoiceInputStrategyResult.Unavailable(
                reason = VoiceUnavailableReason.NO_ACTIVE_MODEL,
                message = "No active language model selected. Please complete setup."
            )
        }

        if (!modelManager.isRuntimeReady(ModelRuntime.LITERT_LM)) {
            return VoiceInputStrategyResult.Unavailable(
                reason = VoiceUnavailableReason.NO_ACTIVE_MODEL,
                message = "LLM model not loaded. Please complete setup."
            )
        }

        if (activeLlmRecord.capabilities.isAudioInputSupported) {
            return VoiceInputStrategyResult.DirectAudio
        }

        val isSttReady = modelManager.isRuntimeReady(ModelRuntime.LITERT_ASR) && sttEngine != null
        return if (isSttReady) {
            VoiceInputStrategyResult.SttTranscribe
        } else {
            VoiceInputStrategyResult.Unavailable(
                reason = VoiceUnavailableReason.STT_NOT_READY,
                message = "Voice recognition model not loaded. Please complete setup."
            )
        }
    }
}
