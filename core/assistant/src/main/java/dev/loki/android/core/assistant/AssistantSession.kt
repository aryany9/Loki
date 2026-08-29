package dev.loki.android.core.assistant

import android.util.Log
import dev.loki.android.core.conversation.ConversationEvent
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

    fun startTurn() {
        cancelTurn()
        _state.value = AssistantState.Listening()
        Log.i(TAG, "Turn started -> State: Listening")

        val provider = AssistantSessionProvider.instance
        val sttEngine = provider?.getSttEngine()
        val conversationManager = provider?.getConversationManager()

        if (sttEngine == null || conversationManager == null) {
            val err = "Assistant provider components not initialized"
            Log.w(TAG, err)
            _state.value = AssistantState.Error(err)
            return
        }

        activeTurnJob = scope.launch {
            try {
                var finalTranscript = ""
                var sttFailed = false
                var sttErrorMessage = ""

                sttEngine.startListening().collect { event ->
                    when (event) {
                        is SttEvent.PartialResult -> {
                            _state.value = AssistantState.Listening(partialTranscript = event.text)
                        }
                        is SttEvent.FinalResult -> {
                            finalTranscript = event.text
                        }
                        is SttEvent.Error -> {
                            sttFailed = true
                            sttErrorMessage = event.error.message ?: "STT processing error"
                            Log.e(TAG, "STT Error during voice turn", event.error)
                            _state.value = AssistantState.Error(sttErrorMessage)
                        }
                        else -> {}
                    }
                }

                if (sttFailed) {
                    Log.w(TAG, "Voice turn aborted due to STT failure: $sttErrorMessage")
                    return@launch
                }

                if (finalTranscript.isBlank()) {
                    Log.i(TAG, "No speech detected in turn, returning to Idle")
                    _state.value = AssistantState.Idle
                    return@launch
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
            } catch (e: CancellationException) {
                Log.i(TAG, "Turn cancelled across active stages")
                _state.value = AssistantState.Idle
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Turn failed with unhandled exception", e)
                _state.value = AssistantState.Error(e.message ?: "Error processing request")
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
    }
}
