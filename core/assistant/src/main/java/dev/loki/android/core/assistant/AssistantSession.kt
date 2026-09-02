package dev.loki.android.core.assistant

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.voice.stt.SttEvent
import dev.loki.android.core.voice.tts.TtsEngine
import dev.loki.android.core.voice.tts.speakAndAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AssistantSession coordinates the Android VoiceInteractionSession lifecycle,
 * LiteRtWhisperEngine STT transcription, ConversationManager multi-turn / continuous listening,
 * verbal confirmation state machine, and AndroidTtsEngine playback with multi-stage cancellation.
 */
class AssistantSession(
    val context: Context? = null,
    private val onDismissCallback: (() -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeTurnJob: Job? = null
    private var activeVoiceSession: dev.loki.android.core.conversation.ConversationSession? = null
    internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    internal var audioRecorderFactory: () -> dev.loki.android.core.voice.stt.AudioRecorder = {
        dev.loki.android.core.voice.stt.AudioRecorder(ioDispatcher = ioDispatcher)
    }

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile
    private var smoothedAmplitude = 0f
    @Volatile
    private var lastAmplitudeUpdateTimeMs = 0L

    internal enum class TurnOutcome {
        SUCCESS,
        EMPTY_SPEECH,
        ERROR,
        PERMISSION_OPENED
    }

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
                    var outcome: TurnOutcome? = null
                    try {
                        outcome = executeDirectAudioTurn(conversationManager, sttEngine, modelManager)
                        when (outcome) {
                            TurnOutcome.SUCCESS -> {
                                Log.i(TAG, "Turn completed successfully")
                            }
                            TurnOutcome.EMPTY_SPEECH -> {
                                Log.i(TAG, "No speech detected, returning to Idle silently")
                            }
                            TurnOutcome.PERMISSION_OPENED -> {
                                Log.i(TAG, "Permission screen opened, returning to Idle")
                            }
                            TurnOutcome.ERROR -> {
                                Log.w(TAG, "Turn ended with error, returning to Idle")
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Turn cancelled")
                        resetAmplitude()
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Turn failed with unhandled exception", e)
                        resetAmplitude()
                        _state.value = AssistantState.Error(e.message ?: "Error processing request")
                    } finally {
                        when {
                            _state.value is AssistantState.Error -> {
                                delay(1500)
                                _state.value = AssistantState.Idle
                            }
                            outcome == TurnOutcome.SUCCESS -> {
                                val currentSpeakingText = (_state.value as? AssistantState.Speaking)?.responseText
                                    ?: (_state.value as? AssistantState.AwaitingFollowUp)?.responseText
                                    ?: (_state.value as? AssistantState.Completed)?.responseText
                                if (!currentSpeakingText.isNullOrBlank()) {
                                    _state.value = AssistantState.Completed(currentSpeakingText)
                                } else {
                                    _state.value = AssistantState.Idle
                                }
                            }
                            else -> {
                                _state.value = AssistantState.Idle
                            }
                        }
                    }
                }
            }
            is VoiceInputStrategyResult.SttTranscribe -> {
                _state.value = AssistantState.Listening(strategy = VoiceInputStrategy.STT_TRANSCRIBE)
                Log.i(TAG, "Turn started -> Strategy: STT_TRANSCRIBE, State: Listening")

                if (sttEngine == null || conversationManager == null) return

                activeTurnJob = scope.launch {
                    var outcome: TurnOutcome? = null
                    try {
                        outcome = executeSttTurn(conversationManager, sttEngine)
                        when (outcome) {
                            TurnOutcome.SUCCESS -> {
                                Log.i(TAG, "Turn completed successfully")
                            }
                            TurnOutcome.EMPTY_SPEECH -> {
                                Log.i(TAG, "No speech detected, returning to Idle silently")
                            }
                            TurnOutcome.PERMISSION_OPENED -> {
                                Log.i(TAG, "Permission screen opened, returning to Idle")
                            }
                            TurnOutcome.ERROR -> {
                                Log.w(TAG, "Turn ended with error, returning to Idle")
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.i(TAG, "Turn cancelled")
                        resetAmplitude()
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "Turn failed with unhandled exception", e)
                        resetAmplitude()
                        _state.value = AssistantState.Error(e.message ?: "Error processing request")
                    } finally {
                        when {
                            _state.value is AssistantState.Error -> {
                                delay(1500)
                                _state.value = AssistantState.Idle
                            }
                            outcome == TurnOutcome.SUCCESS -> {
                                val currentSpeakingText = (_state.value as? AssistantState.Speaking)?.responseText
                                    ?: (_state.value as? AssistantState.AwaitingFollowUp)?.responseText
                                    ?: (_state.value as? AssistantState.Completed)?.responseText
                                if (!currentSpeakingText.isNullOrBlank()) {
                                    _state.value = AssistantState.Completed(currentSpeakingText)
                                } else {
                                    _state.value = AssistantState.Idle
                                }
                            }
                            else -> {
                                _state.value = AssistantState.Idle
                            }
                        }
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
    ): TurnOutcome {
        val recorder = audioRecorderFactory()
        val audioFloats = recorder.recordUtterance(
            onRmsUpdate = { rms ->
                processRawRms(rms)
            }
        )
        resetAmplitude()

        if (audioFloats.isEmpty() || isSilentBuffer(audioFloats)) {
            Log.i(TAG, "Direct audio capture is empty or silent, skipping turn")
            return TurnOutcome.EMPTY_SPEECH
        }

        _state.value = AssistantState.Processing(query = "")
        val wavBytes = dev.loki.android.core.voice.stt.WavEncoder.pcmFloatsToWav(audioFloats)

        var turnError: String? = null
        var turnOutcome = TurnOutcome.SUCCESS
        var finalResponseText = ""
        val language = conversationManager.getAgentConfig().conversationLanguage

        val voiceSession = conversationManager.newVoiceSession()
        activeVoiceSession = voiceSession

        voiceSession.processUtterance(
            userInput = "",
            audioBytes = wavBytes,
            enableTts = false,
            source = "DIRECT_AUDIO"
        ).collect { event ->
            when (event) {
                is ConversationEvent.ToolExecuted -> {
                    if (event.result.errorCode == ToolErrorCode.PERMISSION_DENIED.name) {
                        val permRaw = event.result.error?.substringAfter("Missing permission: ")?.trim() ?: "required"
                        val permName = permRaw.substringAfterLast('.')
                        val permMsg = "To do that, I need the $permName permission. Opening permissions."
                        speakAndAwait(conversationManager.ttsEngine, permMsg)
                        openPermissionsScreen(null)
                        turnOutcome = TurnOutcome.PERMISSION_OPENED
                    }
                }
                is ConversationEvent.Speaking -> {
                    _state.value = AssistantState.Speaking(responseText = event.text)
                }
                is ConversationEvent.Completed -> {
                    finalResponseText = event.finalResponse
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

                val transcript = sttEngine!!.transcribeAudio(audioFloats, language)
                if (transcript.isBlank()) {
                    return TurnOutcome.EMPTY_SPEECH
                }

                _state.value = AssistantState.Processing(query = transcript, isDemoted = true)
                val fallbackSession = conversationManager.newVoiceSession()
                activeVoiceSession = fallbackSession
                fallbackSession.processUtterance(
                    userInput = transcript,
                    enableTts = false,
                    source = "VOICE"
                ).collect { event ->
                    when (event) {
                        is ConversationEvent.ToolExecuted -> {
                            if (event.result.errorCode == ToolErrorCode.PERMISSION_DENIED.name) {
                                val permRaw = event.result.error?.substringAfter("Missing permission: ")?.trim() ?: "required"
                                val permName = permRaw.substringAfterLast('.')
                                val permMsg = "To do that, I need the $permName permission. Opening permissions."
                                speakAndAwait(conversationManager.ttsEngine, permMsg)
                                openPermissionsScreen(null)
                                turnOutcome = TurnOutcome.PERMISSION_OPENED
                            }
                        }
                        is ConversationEvent.Speaking -> {
                            _state.value = AssistantState.Speaking(responseText = event.text)
                        }
                        is ConversationEvent.Completed -> {
                            finalResponseText = event.finalResponse
                            _state.value = AssistantState.Speaking(responseText = event.finalResponse)
                        }
                        is ConversationEvent.Error -> {
                            _state.value = AssistantState.Error(message = event.message)
                            turnOutcome = TurnOutcome.ERROR
                        }
                        else -> {}
                    }
                }
            } else {
                Log.e(TAG, "Direct-audio turn failed and STT engine is not available for fallback")
                _state.value = AssistantState.Error(message = turnError)
                return TurnOutcome.ERROR
            }
        }

        if (turnOutcome == TurnOutcome.SUCCESS && finalResponseText.isNotEmpty()) {
            if (finalResponseText.trim().endsWith("?")) {
                finalResponseText = handleFollowUpLoop(
                    conversationManager = conversationManager,
                    voiceSession = activeVoiceSession ?: voiceSession,
                    sttEngine = sttEngine,
                    initialResponseText = finalResponseText,
                    language = language,
                    useDirectAudio = true
                )
            } else {
                speakAndAwait(conversationManager.ttsEngine, finalResponseText)
            }
        }

        return turnOutcome
    }

    private suspend fun executeSttTurn(
        conversationManager: dev.loki.android.core.conversation.ConversationManager,
        sttEngine: dev.loki.android.core.voice.stt.SttEngine
    ): TurnOutcome {
        var finalTranscript = ""
        var sttFailed = false
        var sttErrorMessage = ""

        val language = conversationManager.getAgentConfig().conversationLanguage
        sttEngine.startListening(language).collect { event ->
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
            return TurnOutcome.ERROR
        }

        if (finalTranscript.isBlank()) {
            return TurnOutcome.EMPTY_SPEECH
        }

        _state.value = AssistantState.Processing(query = finalTranscript)

        var turnOutcome = TurnOutcome.SUCCESS
        var finalResponseText = ""

        val voiceSession = conversationManager.newVoiceSession()
        activeVoiceSession = voiceSession
        voiceSession.processUtterance(finalTranscript, enableTts = false, source = "VOICE").collect { event ->
            when (event) {
                is ConversationEvent.ToolExecuted -> {
                    if (event.result.errorCode == ToolErrorCode.PERMISSION_DENIED.name) {
                        val permRaw = event.result.error?.substringAfter("Missing permission: ")?.trim() ?: "required"
                        val permName = permRaw.substringAfterLast('.')
                        val permMsg = "To do that, I need the $permName permission. Opening permissions."
                        speakAndAwait(conversationManager.ttsEngine, permMsg)
                        openPermissionsScreen(null)
                        turnOutcome = TurnOutcome.PERMISSION_OPENED
                    }
                }
                is ConversationEvent.Speaking -> {
                    _state.value = AssistantState.Speaking(responseText = event.text)
                }
                is ConversationEvent.Completed -> {
                    finalResponseText = event.finalResponse
                    _state.value = AssistantState.Speaking(responseText = event.finalResponse)
                }
                is ConversationEvent.Error -> {
                    Log.e(TAG, "Conversation execution error: ${event.message}")
                    _state.value = AssistantState.Error(message = event.message)
                    turnOutcome = TurnOutcome.ERROR
                }
                else -> {}
            }
        }

        if (turnOutcome == TurnOutcome.SUCCESS && finalResponseText.isNotEmpty()) {
            if (finalResponseText.trim().endsWith("?")) {
                finalResponseText = handleFollowUpLoop(
                    conversationManager = conversationManager,
                    voiceSession = activeVoiceSession ?: voiceSession,
                    sttEngine = sttEngine,
                    initialResponseText = finalResponseText,
                    language = language,
                    useDirectAudio = false
                )
            } else {
                speakAndAwait(conversationManager.ttsEngine, finalResponseText)
            }
        }

        return turnOutcome
    }

    private suspend fun transcribeVerdict(
        sttEngine: dev.loki.android.core.voice.stt.SttEngine?,
        audioFloats: FloatArray,
        language: String
    ): String {
        if (sttEngine == null) return ""
        if (audioFloats.isNotEmpty()) {
            val transcript = sttEngine.transcribeAudio(audioFloats, language).trim()
            if (transcript.isNotEmpty()) {
                return transcript
            }
        }
        var transcript = ""
        try {
            sttEngine.startListening(language).collect { event ->
                if (event is dev.loki.android.core.voice.stt.SttEvent.FinalResult) {
                    transcript = event.text.trim()
                }
            }
        } catch (_: Throwable) {}
        return transcript
    }

    internal suspend fun handleFollowUpLoop(
        conversationManager: dev.loki.android.core.conversation.ConversationManager,
        voiceSession: dev.loki.android.core.conversation.ConversationSession,
        sttEngine: dev.loki.android.core.voice.stt.SttEngine?,
        initialResponseText: String,
        language: String = "auto",
        useDirectAudio: Boolean = false
    ): String {
        val ttsEngine = conversationManager.ttsEngine
        if (!useDirectAudio && sttEngine == null) {
            speakAndAwait(ttsEngine, initialResponseText)
            return initialResponseText
        }

        val recorder = audioRecorderFactory()
        recorder.arm()
        var currentResponse = initialResponseText
        var lastSpokenResponse: String? = null

        try {
            var rounds = 0
            while (rounds < 3 && currentResponse.trim().endsWith("?")) {
                rounds++

                // Attempt capture for this follow-up round with 20s timeout
                var transcript: String? = null
                var audioPcmBytes: ByteArray? = null

                val roundHandled = withTimeoutOrNull(
                    dev.loki.android.core.conversation.ConversationSession.CONFIRMATION_TIMEOUT_MS
                ) {
                    coroutineScope {
                        _state.value = AssistantState.Speaking(responseText = currentResponse)
                        val ttsDone = java.util.concurrent.atomic.AtomicBoolean(false)
                        val capture = async(ioDispatcher) {
                            recorder.recordGatedUtterance(
                                isCommitGated = { !ttsDone.get() || ttsEngine?.isSpeaking == true },
                                onRmsUpdate = { processRawRms(it) }
                            )
                        }

                        if (ttsEngine != null && ttsEngine.isReady && currentResponse.isNotBlank()) {
                            lastSpokenResponse = currentResponse
                            ttsEngine.speak(
                                text = currentResponse,
                                onDone = {
                                    ttsDone.set(true)
                                    _state.value = AssistantState.AwaitingFollowUp(responseText = currentResponse)
                                },
                                onError = {
                                    ttsDone.set(true)
                                    _state.value = AssistantState.AwaitingFollowUp(responseText = currentResponse)
                                }
                            )
                        } else {
                            ttsDone.set(true)
                            _state.value = AssistantState.AwaitingFollowUp(responseText = currentResponse)
                        }

                        val audioFloats = capture.await()
                        resetAmplitude()

                        if (useDirectAudio) {
                            if (audioFloats.isNotEmpty() && !isSilentBuffer(audioFloats)) {
                                audioPcmBytes = dev.loki.android.core.voice.stt.WavEncoder.pcmFloatsToWav(audioFloats)
                                true
                            } else {
                                false
                            }
                        } else {
                            val text = if (sttEngine != null) transcribeVerdict(sttEngine, audioFloats, language) else ""
                            transcript = text.trim().ifEmpty { null }
                            transcript != null
                        }
                    }
                }

                var speechCaptured = roundHandled == true

                // Retry once on timeout or silence
                if (!speechCaptured) {
                    val retryPrompt = "I didn't catch that"
                    val retryHandled = withTimeoutOrNull(
                        dev.loki.android.core.conversation.ConversationSession.CONFIRMATION_TIMEOUT_MS
                    ) {
                        coroutineScope {
                            _state.value = AssistantState.Speaking(responseText = retryPrompt)
                            val ttsDoneRetry = java.util.concurrent.atomic.AtomicBoolean(false)
                            val captureRetry = async(ioDispatcher) {
                                recorder.recordGatedUtterance(
                                    isCommitGated = { !ttsDoneRetry.get() || ttsEngine?.isSpeaking == true },
                                    onRmsUpdate = { processRawRms(it) }
                                )
                            }

                            if (ttsEngine != null && ttsEngine.isReady) {
                                ttsEngine.speak(
                                    text = retryPrompt,
                                    onDone = {
                                        ttsDoneRetry.set(true)
                                        _state.value = AssistantState.AwaitingFollowUp(responseText = currentResponse)
                                    },
                                    onError = {
                                        ttsDoneRetry.set(true)
                                        _state.value = AssistantState.AwaitingFollowUp(responseText = currentResponse)
                                    }
                                )
                            } else {
                                ttsDoneRetry.set(true)
                                _state.value = AssistantState.AwaitingFollowUp(responseText = currentResponse)
                            }

                            val audioFloatsRetry = captureRetry.await()
                            resetAmplitude()

                            if (useDirectAudio) {
                                if (audioFloatsRetry.isNotEmpty() && !isSilentBuffer(audioFloatsRetry)) {
                                    audioPcmBytes = dev.loki.android.core.voice.stt.WavEncoder.pcmFloatsToWav(audioFloatsRetry)
                                    true
                                } else {
                                    false
                                }
                            } else {
                                val textRetry = if (sttEngine != null) transcribeVerdict(sttEngine, audioFloatsRetry, language) else ""
                                transcript = textRetry.trim().ifEmpty { null }
                                transcript != null
                            }
                        }
                    }
                    speechCaptured = retryHandled == true
                }

                if (!speechCaptured) {
                    Log.i(TAG, "Follow-up missed after retry; exiting follow-up loop")
                    break
                }

                // Feed input back as a new user turn on the same voiceSession
                val promptText = transcript ?: ""
                _state.value = AssistantState.Processing(query = promptText)
                var nextResponseText = ""
                var followUpError = false

                voiceSession.processUtterance(
                    userInput = promptText,
                    audioBytes = audioPcmBytes,
                    enableTts = false,
                    source = "VOICE_FOLLOW_UP"
                ).collect { event ->
                    when (event) {
                        is ConversationEvent.ToolExecuted -> {
                            if (event.result.errorCode == ToolErrorCode.PERMISSION_DENIED.name) {
                                val permRaw = event.result.error?.substringAfter("Missing permission: ")?.trim() ?: "required"
                                val permName = permRaw.substringAfterLast('.')
                                val permMsg = "To do that, I need the $permName permission. Opening permissions."
                                speakAndAwait(ttsEngine, permMsg)
                                openPermissionsScreen(null)
                            }
                        }
                        is ConversationEvent.Speaking -> {
                            _state.value = AssistantState.Speaking(responseText = event.text)
                        }
                        is ConversationEvent.Completed -> {
                            nextResponseText = event.finalResponse
                            _state.value = AssistantState.Speaking(responseText = event.finalResponse)
                        }
                        is ConversationEvent.Error -> {
                            Log.w(TAG, "Follow-up turn error: ${event.message}")
                            followUpError = true
                        }
                        else -> {}
                    }
                }

                if (followUpError || nextResponseText.isBlank()) {
                    break
                }

                currentResponse = nextResponseText
            }

            if (currentResponse.isNotBlank() && currentResponse != lastSpokenResponse) {
                speakAndAwait(ttsEngine, currentResponse)
            }

            return currentResponse
        } finally {
            recorder.release()
            resetAmplitude()
        }
    }

    internal suspend fun speakAndAwait(ttsEngine: TtsEngine?, text: String) {
        if (text.isBlank() || ttsEngine == null) return
        _state.value = AssistantState.Speaking(responseText = text)
        if (!ttsEngine.isReady) return
        ttsEngine.speakAndAwait(text)
    }

    internal fun openPermissionsScreen(context: Context?) {
        val targetContext = this.context ?: context ?: return
        try {
            val intent = Intent(targetContext, Class.forName("dev.loki.android.ui.MainActivity")).apply {
                putExtra("openScreen", "PERMISSIONS")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open permissions screen intent", e)
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
            activeVoiceSession?.cancel()
            activeVoiceSession = null

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
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val MAX_CONSECUTIVE_SILENCES = 2

        fun normalizeRms(rawRms: Float, ceiling: Float = RMS_CEILING): Float {
            return (rawRms / ceiling).coerceIn(0f, 1f)
        }

        fun smoothRms(currentSmoothed: Float, target: Float, alpha: Float = SMOOTHING_ALPHA): Float {
            return (currentSmoothed * (1f - alpha) + target * alpha).coerceIn(0f, 1f)
        }

        fun isSilentBuffer(audioFloats: FloatArray, threshold: Float = 0.02f): Boolean {
            if (audioFloats.isEmpty()) return true
            for (sample in audioFloats) {
                if (kotlin.math.abs(sample) >= threshold) {
                    return false
                }
            }
            return true
        }
    }
}
