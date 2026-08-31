package dev.loki.android.core.assistant

import android.util.Log
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.voice.stt.SttEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AssistantSession coordinates the Android VoiceInteractionSession lifecycle,
 * LiteRtWhisperEngine STT transcription, ConversationManager single-turn execution,
 * and AndroidTtsEngine playback with multi-stage cancellation and error-to-Idle recovery.
 */
class AssistantSession(
    private val onDismissCallback: (() -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeTurnJob: Job? = null

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile
    private var smoothedAmplitude = 0f
    @Volatile
    private var lastAmplitudeUpdateTimeMs = 0L

    @Synchronized
    internal fun processRawRms(rawRms: Float) {
        val normalized = normalizeRms(rawRms)
        smoothedAmplitude = smoothRms(smoothedAmplitude, normalized)
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdateTimeMs >= THROTTLE_INTERVAL_MS) {
            lastAmplitudeUpdateTimeMs = now
            _amplitude.value = smoothedAmplitude
        }
    }

    @Synchronized
    internal fun resetAmplitude() {
        smoothedAmplitude = 0f
        lastAmplitudeUpdateTimeMs = 0L
        _amplitude.value = 0f
    }

    fun startTurn() {
        cancelTurn()
        resetAmplitude()

        val provider = AssistantSessionProvider.instance
        val modelManager = provider?.getModelLibraryManager()
        val sttEngine = provider?.getSttEngine()
        val conversationManager = provider?.getConversationManager()

        val resolver = VoiceInputStrategyResolver()
        val resolution = resolver.resolve(modelManager, sttEngine)

        when (resolution) {
            is VoiceInputStrategyResult.DirectAudio -> {
                _state.value = AssistantState.Listening(strategy = VoiceInputStrategy.DIRECT_AUDIO)
                Log.i(TAG, "Turn started -> Strategy: DIRECT_AUDIO, State: Listening")

                if (conversationManager == null) return

                activeTurnJob = scope.launch {
                    try {
                        executeDirectAudioTurn(conversationManager, sttEngine, modelManager)
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Turn cancelled across active stages")
                        resetAmplitude()
                        _state.value = AssistantState.Idle
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Turn failed with unhandled exception", e)
                        resetAmplitude()
                        _state.value = AssistantState.Error(e.message ?: "Error processing request")
                    }
                }
            }
            is VoiceInputStrategyResult.SttTranscribe -> {
                _state.value = AssistantState.Listening(strategy = VoiceInputStrategy.STT_TRANSCRIBE)
                Log.i(TAG, "Turn started -> Strategy: STT_TRANSCRIBE, State: Listening")

                if (sttEngine == null || conversationManager == null) return

                activeTurnJob = scope.launch {
                    try {
                        executeSttTurn(conversationManager, sttEngine)
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Turn cancelled across active stages")
                        resetAmplitude()
                        _state.value = AssistantState.Idle
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Turn failed with unhandled exception", e)
                        resetAmplitude()
                        _state.value = AssistantState.Error(e.message ?: "Error processing request")
                    }
                }
            }
            is VoiceInputStrategyResult.Unavailable -> {
                Log.w(TAG, resolution.message)
                _state.value = AssistantState.Error(resolution.message)
                return
            }
        }
    }

    private suspend fun executeDirectAudioTurn(
        conversationManager: dev.loki.android.core.conversation.ConversationManager,
        sttEngine: dev.loki.android.core.voice.stt.SttEngine?,
        modelManager: dev.loki.android.core.models.ModelLibraryManager?
    ) {
        val recorder = dev.loki.android.core.voice.stt.AudioRecorder()
        val recordStart = System.currentTimeMillis()
        val audioFloats = recorder.recordUtterance(
            onRmsUpdate = { rms ->
                processRawRms(rms)
            }
        )
        val recordDuration = System.currentTimeMillis() - recordStart
        resetAmplitude()

        if (audioFloats.isEmpty()) {
            Log.i(TAG, "No audio recorded in direct-audio turn, returning to Idle")
            _state.value = AssistantState.Idle
            return
        }

        _state.value = AssistantState.Processing(query = "")
        val wavBytes = dev.loki.android.core.voice.stt.WavEncoder.pcmFloatsToWav(audioFloats)

        var turnError: String? = null
        val voiceSession = conversationManager.newVoiceSession()

        voiceSession.processUtterance(
            userInput = "",
            audioBytes = wavBytes,
            enableTts = true,
            source = "DIRECT_AUDIO"
        ).collect { event ->
            when (event) {
                is ConversationEvent.Speaking -> {
                    _state.value = AssistantState.Speaking(responseText = event.text)
                }
                is ConversationEvent.Completed -> {
                    _state.value = AssistantState.Speaking(responseText = event.finalResponse)
                }
                is ConversationEvent.Error -> {
                    turnError = event.message
                }
                else -> {}
            }
        }

        if (turnError != null) {
            val isSttAvailable = sttEngine != null && modelManager?.isRuntimeReady(ModelRuntime.LITERT_ASR) == true
            if (isSttAvailable) {
                Log.w(TAG, "Direct-audio turn failed ($turnError); attempting auto-demotion to STT fallback")
                _state.value = AssistantState.Processing(query = "Transcribing with STT fallback...", isDemoted = true)

                val transcript = sttEngine!!.transcribeAudio(audioFloats)
                if (transcript.isBlank()) {
                    Log.i(TAG, "STT fallback produced empty transcript, returning to Idle")
                    _state.value = AssistantState.Idle
                    return
                }

                _state.value = AssistantState.Processing(query = transcript, isDemoted = true)
                val fallbackSession = conversationManager.newVoiceSession()
                fallbackSession.processUtterance(
                    userInput = transcript,
                    enableTts = true,
                    source = "VOICE"
                ).collect { event ->
                    when (event) {
                        is ConversationEvent.Speaking -> {
                            _state.value = AssistantState.Speaking(responseText = event.text)
                        }
                        is ConversationEvent.Completed -> {
                            _state.value = AssistantState.Speaking(responseText = event.finalResponse)
                        }
                        is ConversationEvent.Error -> {
                            _state.value = AssistantState.Error(message = event.message)
                        }
                        else -> {}
                    }
                }
            } else {
                Log.e(TAG, "Direct-audio turn failed and STT engine is not available for fallback")
                _state.value = AssistantState.Error(message = turnError!!)
            }
        }
    }

    private suspend fun executeSttTurn(
        conversationManager: dev.loki.android.core.conversation.ConversationManager,
        sttEngine: dev.loki.android.core.voice.stt.SttEngine
    ) {
        var finalTranscript = ""
        var sttFailed = false
        var sttErrorMessage = ""

        sttEngine.startListening().collect { event ->
            when (event) {
                is SttEvent.Amplitude -> {
                    processRawRms(event.rms)
                }
                is SttEvent.ListeningStopped -> {
                    resetAmplitude()
                }
                is SttEvent.PartialResult -> {
                    _state.value = AssistantState.Listening(
                        partialTranscript = event.text,
                        strategy = VoiceInputStrategy.STT_TRANSCRIBE
                    )
                }
                is SttEvent.FinalResult -> {
                    resetAmplitude()
                    finalTranscript = event.text
                }
                is SttEvent.Error -> {
                    resetAmplitude()
                    sttFailed = true
                    sttErrorMessage = event.error.message ?: "STT processing error"
                    Log.e(TAG, "STT Error during voice turn", event.error)
                    _state.value = AssistantState.Error(sttErrorMessage)
                }
                else -> {}
            }
        }
        resetAmplitude()

        if (sttFailed) {
            Log.w(TAG, "Voice turn aborted due to STT failure: $sttErrorMessage")
            return
        }

        if (finalTranscript.isBlank()) {
            Log.i(TAG, "No speech detected in turn, returning to Idle")
            _state.value = AssistantState.Idle
            return
        }

        _state.value = AssistantState.Processing(query = finalTranscript)

        val voiceSession = conversationManager.newVoiceSession()
        voiceSession.processUtterance(finalTranscript, enableTts = true, source = "VOICE").collect { event ->
            when (event) {
                is ConversationEvent.Speaking -> {
                    _state.value = AssistantState.Speaking(responseText = event.text)
                }
                is ConversationEvent.Completed -> {
                    _state.value = AssistantState.Speaking(responseText = event.finalResponse)
                }
                is ConversationEvent.Error -> {
                    Log.e(TAG, "Conversation execution error: ${event.message}")
                    _state.value = AssistantState.Error(message = event.message)
                }
                else -> {}
            }
        }
    }

    fun updateTranscript(partial: String) {
        if (_state.value is AssistantState.Listening) {
            _state.value = AssistantState.Listening(partialTranscript = partial)
        }
    }

    fun updateProcessing(query: String) {
        _state.value = AssistantState.Processing(query = query)
    }

    fun updateSpeaking(responseText: String) {
        _state.value = AssistantState.Speaking(responseText = responseText)
    }

    fun updateError(errorMessage: String) {
        _state.value = AssistantState.Error(message = errorMessage)
    }

    fun cancelTurn() {
        resetAmplitude()
        if (activeTurnJob != null || _state.value !is AssistantState.Idle) {
            Log.i(TAG, "cancelTurn() invoked — stopping STT, LLM generation, tools, and TTS playback")
            activeTurnJob?.cancel()
            activeTurnJob = null

            val provider = AssistantSessionProvider.instance
            try {
                provider?.getSttEngine()?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling STT engine", e)
            }

            try {
                provider?.getConversationManager()?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling ConversationManager", e)
            }

            _state.value = AssistantState.Idle
        }
    }

    fun dismiss() {
        cancelTurn()
        onDismissCallback?.invoke()
    }

    fun destroy() {
        Log.i(TAG, "destroy() invoked")
        cancelTurn()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "AssistantSession"
        const val RMS_CEILING = 8000f
        const val THROTTLE_INTERVAL_MS = 33L
        const val SMOOTHING_ALPHA = 0.4f

        fun normalizeRms(rawRms: Float, ceiling: Float = RMS_CEILING): Float {
            return (rawRms / ceiling).coerceIn(0f, 1f)
        }

        fun smoothRms(currentSmoothed: Float, target: Float, alpha: Float = SMOOTHING_ALPHA): Float {
            return (currentSmoothed * (1f - alpha) + target * alpha).coerceIn(0f, 1f)
        }
    }
}
