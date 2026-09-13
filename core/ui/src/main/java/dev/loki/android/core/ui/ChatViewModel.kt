package dev.loki.android.core.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.loki.android.core.assistant.AssistantSessionProvider
import dev.loki.android.core.assistant.VoiceInputStrategyResolver
import dev.loki.android.core.assistant.VoiceInputStrategyResult
import dev.loki.android.core.sound.AudioCue
import dev.loki.android.core.sound.audioStartCueEnabled
import dev.loki.android.core.conversation.ConversationEvent
import dev.loki.android.core.conversation.ConversationManager
import dev.loki.android.core.conversation.ConversationRecord
import dev.loki.android.core.conversation.ConversationSession
import dev.loki.android.core.conversation.ConversationTurn
import dev.loki.android.core.conversation.PendingConfirmation
import dev.loki.android.core.conversation.ToolCallParser
import dev.loki.android.core.llm.LlmModelState
import dev.loki.android.core.assistant.VoiceUnavailableReason
import dev.loki.android.core.models.DownloadResult
import dev.loki.android.core.models.MetadataConfidence
import dev.loki.android.core.models.ModelArtifact
import dev.loki.android.core.models.ModelAvailability
import dev.loki.android.core.models.ModelCatalog
import dev.loki.android.core.models.ModelCatalogEntry
import dev.loki.android.core.models.ModelDownloader
import dev.loki.android.core.models.ModelFormat
import dev.loki.android.core.models.ModelLibraryManager
import dev.loki.android.core.models.ModelMetadataField
import dev.loki.android.core.models.ModelRecord
import dev.loki.android.core.models.ModelRecordCapabilities
import dev.loki.android.core.models.ModelRuntime
import dev.loki.android.core.models.ModelRuntimeController
import dev.loki.android.core.models.ModelSource
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.stt.AudioRecorder
import dev.loki.android.core.voice.stt.LiteRtWhisperEngine
import dev.loki.android.core.voice.stt.SttEngine
import dev.loki.android.core.voice.stt.SttEvent
import dev.loki.android.core.voice.stt.WavEncoder
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    val conversationManager: ConversationManager,
    private val sttEngine: SttEngine? = null,
    private val modelLibraryManager: ModelLibraryManager? = null,
    private val voiceStrategyResolver: VoiceInputStrategyResolver = VoiceInputStrategyResolver(),
    private val bundledCatalog: ModelCatalog? = null,
    private val modelDownloader: ModelDownloader? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    applicationContext: android.content.Context? = null
) : ViewModel() {

    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState
    val currentConversationId: String? get() = conversationManager.currentConversationId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Task 7.3: Single-Instance Media3 Controller
    val audioPlaybackController: AudioPlaybackController? = applicationContext?.let {
        AudioPlaybackController(it, viewModelScope)
    }

    private val _conversations = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val conversations: StateFlow<List<ConversationRecord>> = _conversations.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private val _isVoiceModelDownloadable = MutableStateFlow(false)
    val isVoiceModelDownloadable: StateFlow<Boolean> = _isVoiceModelDownloadable.asStateFlow()

    private val _isDownloadingVoiceModel = MutableStateFlow(false)
    val isDownloadingVoiceModel: StateFlow<Boolean> = _isDownloadingVoiceModel.asStateFlow()

    private val _voiceDownloadProgress = MutableStateFlow<Float?>(null)
    val voiceDownloadProgress: StateFlow<Float?> = _voiceDownloadProgress.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private var activeChatSession: ConversationSession? = null
    private var generationJob: Job? = null
    private var inFlightAssistantMessageId: String? = null

    private var activeAudioRecorder: AudioRecorder? = null
    private var recordingJob: Job? = null
    private var voiceStartCuePlayed: Boolean = false

    init {
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
            loadInitialConversation()
        }
    }

    private suspend fun refreshConversations() {
        _conversations.value = conversationManager.listConversations()
    }

    suspend fun loadInitialConversation() {
        conversationManager.reset()
        _messages.value = emptyList()
        _pendingConfirmation.value = null
        refreshConversations()
    }

    fun selectConversation(id: String) {
        cancelGeneration()
        viewModelScope.launch {
            val loaded = conversationManager.loadConversation(id)
            if (loaded != null) {
                _messages.value = mapTurnsToMessages(loaded.turns)
            }
            refreshConversations()
        }
    }

    fun newConversation() {
        cancelGeneration()
        conversationManager.reset()
        _messages.value = emptyList()
        _pendingConfirmation.value = null
        viewModelScope.launch {
            refreshConversations()
        }
    }

    fun clearChat() = newConversation()

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            val wasActive = conversationManager.currentConversationId == id
            conversationManager.deleteConversation(id)
            if (wasActive) {
                conversationManager.reset()
                _messages.value = emptyList()
                _pendingConfirmation.value = null
            }
            refreshConversations()
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            conversationManager.renameConversation(id, title)
            refreshConversations()
        }
    }

    fun retryLoadModel() {
        viewModelScope.launch {
            conversationManager.llmEngine.initializeAsync()
        }
    }

    fun respondToConfirmation(accepted: Boolean) {
        activeChatSession?.respondToConfirmation(accepted)
        _pendingConfirmation.value = null
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        activeChatSession?.respondToConfirmation(false)
        activeChatSession = null
        _pendingConfirmation.value = null
        conversationManager.llmEngine.cancel()

        val targetId = inFlightAssistantMessageId
        _messages.value = _messages.value.map { msg ->
            if ((targetId != null && msg.id == targetId) || msg.isThinking || msg.isStreaming) {
                msg.copy(
                    isThinking = false,
                    isStreaming = false
                )
            } else {
                msg
            }
        }
        inFlightAssistantMessageId = null
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        executeChatTurn(
            displayInput = text,
            userInput = text,
            audioBytes = null,
            source = "TEXT"
        )
    }

    private fun executeChatTurn(
        displayInput: String,
        userInput: String,
        audioBytes: ByteArray?,
        source: String,
        customUserMessage: ChatMessage? = null
    ) {
        val userMessage = customUserMessage ?: ChatMessage(sender = MessageSender.USER, text = displayInput)
        val inFlightMessageId = UUID.randomUUID().toString()
        inFlightAssistantMessageId = inFlightMessageId
        val initialAssistantMessage = ChatMessage(
            id = inFlightMessageId,
            sender = MessageSender.ASSISTANT,
            text = "Thinking...",
            isThinking = true,
            isStreaming = false
        )
        _messages.value = _messages.value + userMessage + initialAssistantMessage

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            var lastUpdateTime = 0L
            var currentToolInvocations = mutableListOf<ToolInvocation>()
            var inFlightCallId: String? = null

            try {
                if (conversationManager.currentConversationId == null) {
                    conversationManager.createConversation()
                    refreshConversations()
                }

                val chatSession = conversationManager.newChatSession()
                activeChatSession = chatSession
                chatSession.processUtterance(
                    userInput = userInput,
                    audioBytes = audioBytes,
                    enableTts = false,
                    source = source
                ).collect { event ->
                    when (event) {
                        is ConversationEvent.Thinking -> {
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(isThinking = true, isStreaming = false, text = "Thinking...") else msg
                            }
                        }
                        is ConversationEvent.ToolExecuting -> {
                            val callId = inFlightCallId ?: java.util.UUID.randomUUID().toString()
                            val newInvocation = ToolInvocation(
                                id = callId,
                                toolName = event.toolName,
                                arguments = event.args,
                                result = null,
                                isExecuting = true
                            )
                            currentToolInvocations = (currentToolInvocations + newInvocation).toMutableList()
                            inFlightCallId = callId
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(
                                    text = "",
                                    toolInvocations = currentToolInvocations.toList()
                                ) else msg
                            }
                        }
                        is ConversationEvent.ConfirmationRequired -> {
                            _pendingConfirmation.value = PendingConfirmation(
                                toolName = event.toolName,
                                arguments = emptyMap(),
                                repeatBack = event.repeatBack
                            )
                        }
                        is ConversationEvent.ToolExecuted -> {
                            _pendingConfirmation.value = null
                            val cid = inFlightCallId
                            currentToolInvocations = currentToolInvocations.map { inv ->
                                if (inv.id == cid) inv.copy(result = event.result, isExecuting = false) else inv
                            }.toMutableList()
                            inFlightCallId = null
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(toolInvocations = currentToolInvocations.toList()) else msg
                            }
                        }
                        is ConversationEvent.GeneratingToken -> {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime >= 50L) {
                                lastUpdateTime = now
                                val cleaned = ToolCallParser.cleanStreamingPartial(event.partial)
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == inFlightMessageId) {
                                        if (cleaned != null) {
                                            msg.copy(
                                                text = cleaned,
                                                isThinking = false,
                                                isStreaming = true
                                            )
                                        } else {
                                            msg.copy(
                                                text = "",
                                                isThinking = true,
                                                isStreaming = false
                                            )
                                        }
                                    } else msg
                                }
                            }
                        }
                        is ConversationEvent.Completed -> {
                            _pendingConfirmation.value = null
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        text = event.finalResponse,
                                        isThinking = false,
                                        isStreaming = false
                                    )
                                } else msg
                            }
                            refreshConversations()
                        }
                        is ConversationEvent.ToolCallStarted -> {
                            val newInvocation = ToolInvocation(
                                id = event.callId,
                                toolName = event.toolName,
                                arguments = event.arguments,
                                isExecuting = true
                            )
                            currentToolInvocations = (currentToolInvocations + newInvocation).toMutableList()
                            inFlightCallId = event.callId
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(
                                    text = "",
                                    toolInvocations = currentToolInvocations.toList()
                                ) else msg
                            }
                        }
                        is ConversationEvent.ToolCallCompleted -> {
                            currentToolInvocations = currentToolInvocations.map { inv ->
                                if (inv.id == event.callId) inv.copy(result = event.result, isExecuting = false) else inv
                            }.toMutableList()
                            inFlightCallId = null
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(toolInvocations = currentToolInvocations.toList()) else msg
                            }
                        }
                        is ConversationEvent.ToolCallFailed -> {
                            currentToolInvocations = currentToolInvocations.map { inv ->
                                if (inv.id == event.callId) inv.copy(isExecuting = false) else inv
                            }.toMutableList()
                            inFlightCallId = null
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) msg.copy(toolInvocations = currentToolInvocations.toList()) else msg
                            }
                        }
                        is ConversationEvent.ContextCompacted -> {
                            android.util.Log.i("ChatViewModel", "Context compacted: ${event.message}")
                        }
                        is ConversationEvent.Error -> {
                            _pendingConfirmation.value = null
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        text = "Error: ${event.message}",
                                        isThinking = false,
                                        isStreaming = false
                                    )
                                } else msg
                            }
                            refreshConversations()
                        }
                        else -> {}
                    }
                }
            } catch (e: CancellationException) {
                _pendingConfirmation.value = null
                throw e
            } catch (e: Throwable) {
                _pendingConfirmation.value = null
                _messages.value = _messages.value.map { msg ->
                    if (msg.id == inFlightMessageId) {
                        msg.copy(
                            text = "Error: ${e.message ?: "Failed to process request"}",
                            isThinking = false,
                            isStreaming = false
                        )
                    } else msg
                }
            } finally {
                _pendingConfirmation.value = null
                if (inFlightAssistantMessageId == inFlightMessageId) {
                    inFlightAssistantMessageId = null
                }
            }
        }
    }

    fun startVoiceInput() {
        if (audioStartCueEnabled && !voiceStartCuePlayed) {
            AudioCue.playStartTone()
            voiceStartCuePlayed = true
        }

        _voiceError.value = null
        _isVoiceModelDownloadable.value = false
        val modelManager = modelLibraryManager ?: AssistantSessionProvider.instance?.getModelLibraryManager()
        val resolution = voiceStrategyResolver.resolve(modelManager, sttEngine)

        when (resolution) {
            is VoiceInputStrategyResult.DirectAudio -> {
                startDirectAudioVoiceInput()
            }
            is VoiceInputStrategyResult.SttTranscribe -> {
                startSttVoiceInput(modelManager)
            }
            is VoiceInputStrategyResult.Unavailable -> {
                _isRecording.value = false
                voiceStartCuePlayed = false
                if (resolution.reason == VoiceUnavailableReason.STT_NOT_READY) {
                    _isVoiceModelDownloadable.value = true
                    _voiceError.value = "Voice recognition model required for text-only model. Tap download to install."
                } else {
                    _voiceError.value = resolution.message
                }
            }
        }
    }

    fun dismissVoiceError() {
        _voiceError.value = null
        _isVoiceModelDownloadable.value = false
        _isDownloadingVoiceModel.value = false
        _voiceDownloadProgress.value = null
    }

    fun downloadVoiceModel(streamOpener: (suspend (String) -> InputStream)? = null) {
        val modelManager = modelLibraryManager ?: AssistantSessionProvider.instance?.getModelLibraryManager()
        if (modelManager == null) {
            _voiceError.value = "Model manager unavailable."
            return
        }

        val entry = bundledCatalog?.models?.firstOrNull { it.runtime == ModelRuntime.LITERT_ASR }
        if (entry == null) {
            _voiceError.value = "Voice recognition model unavailable in the model catalog."
            return
        }

        _isDownloadingVoiceModel.value = true
        _voiceDownloadProgress.value = 0f
        _voiceError.value = "Downloading ${entry.displayName}..."

        viewModelScope.launch {
            val downloader = modelDownloader ?: ModelDownloader(modelManager.managedStorage)
            val downloadedArtifacts = mutableListOf<ModelArtifact>()
            val totalArtifacts = entry.artifacts.size

            try {
                entry.artifacts.forEachIndexed { index, artifact ->
                    val result = withContext(ioDispatcher) {
                        val stream = if (streamOpener != null) {
                            streamOpener(artifact.url)
                        } else {
                            val connection = (URL(artifact.url).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 15_000
                                readTimeout = 60_000
                                requestMethod = "GET"
                                connect()
                            }
                            connection.inputStream
                        }

                        stream.use { input ->
                            downloader.downloadArtifact(
                                modelId = entry.id,
                                artifact = artifact,
                                input = input,
                                onProgress = { bytesCopied, totalBytes ->
                                    val artifactProgress = totalBytes?.let { bytesCopied.toFloat() / it } ?: -1f
                                    _voiceDownloadProgress.value = (index + artifactProgress.coerceIn(0f, 1f)) / totalArtifacts
                                }
                            )
                        }
                    }

                    when (result) {
                        is DownloadResult.Completed -> {
                            downloadedArtifacts.add(artifact.copy(sha256 = result.sha256))
                        }
                        is DownloadResult.Failed -> {
                            _voiceError.value = "Download failed: ${result.reason}"
                            _isDownloadingVoiceModel.value = false
                            _voiceDownloadProgress.value = null
                            return@launch
                        }
                    }
                }

                val hasAudioInput = entry.capabilities.any { it.equals("audio-input", ignoreCase = true) }
                val record = ModelRecord(
                    id = entry.id,
                    displayName = entry.displayName,
                    family = ModelMetadataField(entry.family),
                    runtime = entry.runtime,
                    format = entry.format,
                    artifacts = downloadedArtifacts,
                    source = ModelSource.BUNDLED_CATALOG,
                    availability = ModelAvailability.DOWNLOADED,
                    importedAtEpochMs = System.currentTimeMillis(),
                    capabilities = ModelRecordCapabilities(
                        audioInput = ModelMetadataField(
                            value = hasAudioInput,
                            confidence = MetadataConfidence.VERIFIED
                        )
                    )
                )

                modelManager.register(record)
                modelManager.load(entry.id)

                when (val controller = sttEngine) {
                    is ModelRuntimeController -> {
                        controller.load(record)
                        _voiceError.value = null
                        _isVoiceModelDownloadable.value = false
                    }
                    else -> {
                        Log.e(TAG, "sttEngine is not a ModelRuntimeController; STT model not loaded")
                        _voiceError.value = "Voice engine unavailable. Please restart the app."
                        _isVoiceModelDownloadable.value = false
                    }
                }
            } catch (e: Exception) {
                _voiceError.value = "Download error: ${e.message ?: "Unknown error"}"
            } finally {
                _isDownloadingVoiceModel.value = false
                _voiceDownloadProgress.value = null
            }
        }
    }

    private fun startDirectAudioVoiceInput() {
        _isRecording.value = true
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            val recorder = AudioRecorder()
            activeAudioRecorder = recorder
            try {
                val availableTokens = conversationManager.llmEngine.availableKvTokens
                val maxAudioDurationMs = if (availableTokens > 0) {
                    val reservedGenTokens = 256
                    val budgetSec = maxOf(0, (availableTokens - reservedGenTokens) / 25)
                    (budgetSec * 1000L).coerceAtLeast(1000L)
                } else null
                
                val audioFloats = recorder.recordUtterance(maxAudioDurationMs = maxAudioDurationMs)
                _isRecording.value = false
                voiceStartCuePlayed = false
                activeAudioRecorder = null

                if (audioFloats.isNotEmpty()) {
                    val wavBytes = WavEncoder.pcmFloatsToWav(audioFloats)
                    val userMessageId = java.util.UUID.randomUUID().toString()
                    if (conversationManager.currentConversationId == null) {
                        conversationManager.createConversation()
                        refreshConversations()
                    }
                    val currentConversationId = conversationManager.currentConversationId
                    
                    // Task 6.2: Storage Domain Setup
                    var savedAudioPath: String? = null
                    if (currentConversationId != null) {
                        try {
                            val audioDir = java.io.File(conversationManager.conversationStore.baseDir.parentFile, "audio_records")
                            if (!audioDir.exists()) audioDir.mkdirs()
                            val file = java.io.File(audioDir, "${currentConversationId}_${userMessageId}.wav")
                            file.writeBytes(wavBytes)
                            savedAudioPath = file.absolutePath
                            
                            // Task 6.3: 50MB Inline LRU Quota Pruning
                            val maxBytes = 50L * 1024 * 1024
                            var totalSize = audioDir.listFiles()?.sumOf { it.length() } ?: 0L
                            if (totalSize > maxBytes) {
                                // We don't have SQLite to filter FAILED ones, but we can avoid deleting the current file
                                val sortedFiles = audioDir.listFiles()?.filter { it != file }?.sortedBy { it.lastModified() } ?: emptyList()
                                for (oldFile in sortedFiles) {
                                    if (totalSize <= maxBytes) break
                                    val size = oldFile.length()
                                    if (oldFile.delete()) {
                                        totalSize -= size
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save audio record", e)
                        }
                    }

                    val waveform = dev.loki.android.core.voice.stt.WaveformExtractor.extractAmplitudes(audioFloats)
                    val userMessage = ChatMessage(
                        id = userMessageId,
                        sender = MessageSender.USER,
                        text = "",
                        transcriptStatus = TranscriptStatus.PENDING,
                        audioFilePath = savedAudioPath,
                        waveformData = waveform
                    )
                    
                    executeChatTurn(
                        displayInput = "[Voice Audio]",
                        userInput = "",
                        audioBytes = wavBytes,
                        source = "CHAT_DIRECT_AUDIO",
                        customUserMessage = userMessage
                    )

                    // Task 5.1/5.4: Async Whisper Transcript Recovery
                    if (currentConversationId != null && sttEngine != null) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val transcript = sttEngine.transcribeAudio(audioFloats)
                                val finalStatus = if (transcript.isNotBlank()) TranscriptStatus.COMPLETED else TranscriptStatus.FAILED
                                val finalText = transcript.ifBlank { "" }

                                // 1. Update UI stream
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == userMessageId) {
                                        msg.copy(transcriptStatus = finalStatus, text = finalText)
                                    } else msg
                                }

                                // 2. Update persistent JSON store (KV-Cache Isolation: do NOT replace the multimodal Context)
                                val store = conversationManager.conversationStore
                                val record = store.loadConversation(currentConversationId)
                                if (record != null) {
                                    // The ConversationTurn.User was added by ConversationSession with timestamp close to userMessage.timestamp
                                    val updatedTurns = record.turns.map { turn ->
                                        if (turn is dev.loki.android.core.conversation.ConversationTurn.User && turn.text == "[Voice Audio]") {
                                            // Heuristic: matching "[Voice Audio]" which is what executeChatTurn wrote
                                            turn.copy(
                                                text = if (finalStatus == TranscriptStatus.COMPLETED) finalText else turn.text,
                                                transcriptStatus = dev.loki.android.core.conversation.ConversationTurn.TranscriptStatus.valueOf(finalStatus.name),
                                                audioFilePath = savedAudioPath,
                                                waveformData = waveform
                                            )
                                        } else turn
                                    }
                                    store.saveConversation(record.copy(turns = updatedTurns))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Async transcription failed", e)
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == userMessageId) msg.copy(transcriptStatus = TranscriptStatus.FAILED) else msg
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                _isRecording.value = false
                voiceStartCuePlayed = false
                activeAudioRecorder = null
                throw e
            } catch (e: Throwable) {
                _isRecording.value = false
                voiceStartCuePlayed = false
                activeAudioRecorder = null
                _voiceError.value = e.message ?: "Audio recording failed"
            }
        }
    }

    private fun startSttVoiceInput(modelManager: ModelLibraryManager?) {
        if (sttEngine == null) {
            _isRecording.value = false
            voiceStartCuePlayed = false
            _voiceError.value = "Voice recognition engine is unavailable."
            return
        }

        _isRecording.value = true
        recordingJob?.cancel()
        recordingJob = viewModelScope.launch {
            try {
                if (sttEngine is LiteRtWhisperEngine && !sttEngine.isReady() && modelManager != null) {
                    val activeAsrId = modelManager.manifest.value.activeModels[ModelRuntime.LITERT_ASR]
                    val asrRecord = modelManager.manifest.value.models.firstOrNull { it.id == activeAsrId }
                    if (asrRecord != null && asrRecord.availability != ModelAvailability.NOT_DOWNLOADED) {
                        sttEngine.load(asrRecord)
                    }
                }

                val language = conversationManager.getAgentConfig().conversationLanguage
                sttEngine.startListening(language).collect { event ->
                    when (event) {
                        is SttEvent.FinalResult -> {
                            _isRecording.value = false
                            voiceStartCuePlayed = false
                            if (event.text.isNotBlank()) {
                                sendMessage(event.text)
                            }
                        }
                        is SttEvent.ListeningStopped -> {
                            _isRecording.value = false
                            voiceStartCuePlayed = false
                        }
                        is SttEvent.Error -> {
                            _isRecording.value = false
                            voiceStartCuePlayed = false
                            _voiceError.value = event.error.message ?: "Voice recognition failed"
                        }
                        else -> {}
                    }
                }
            } catch (e: CancellationException) {
                _isRecording.value = false
                voiceStartCuePlayed = false
                throw e
            } catch (e: Throwable) {
                _isRecording.value = false
                voiceStartCuePlayed = false
                _voiceError.value = e.message ?: "Voice recognition failed"
            }
        }
    }

    fun stopVoiceInput() {
        activeAudioRecorder?.stop()
        activeAudioRecorder = null
        recordingJob?.cancel()
        recordingJob = null
        sttEngine?.stopListening()
        _isRecording.value = false
        voiceStartCuePlayed = false
    }

    companion object {
        private const val TAG = "ChatViewModel"

        fun mapTurnsToMessages(turns: List<ConversationTurn>): List<ChatMessage> {
            if (turns.isEmpty()) {
                return emptyList()
            }

            val result = mutableListOf<ChatMessage>()
            var pendingToolResult: ToolResult? = null
            var pendingToolName: String? = null

            for (turn in turns) {
                when (turn) {
                    is ConversationTurn.User -> {
                        if (pendingToolResult != null || pendingToolName != null) {
                            result.add(
                                ChatMessage(
                                    sender = MessageSender.ASSISTANT,
                                    text = "",
                                    toolResult = pendingToolResult,
                                    toolName = pendingToolName
                                )
                            )
                            pendingToolResult = null
                            pendingToolName = null
                        }
                        result.add(
                            ChatMessage(
                                sender = MessageSender.USER,
                                text = turn.text,
                                timestamp = turn.timestamp,
                                transcriptStatus = dev.loki.android.core.ui.TranscriptStatus.valueOf(turn.transcriptStatus.name),
                                audioFilePath = turn.audioFilePath
                            )
                        )
                    }
                    is ConversationTurn.ToolCall -> {
                        pendingToolName = turn.tool
                    }
                    is ConversationTurn.ToolExecutionResult -> {
                        pendingToolName = turn.tool
                        pendingToolResult = turn.result
                    }
                    is ConversationTurn.Assistant -> {
                        result.add(
                            ChatMessage(
                                sender = MessageSender.ASSISTANT,
                                text = turn.text,
                                timestamp = turn.timestamp,
                                toolResult = pendingToolResult,
                                toolName = pendingToolName
                            )
                        )
                        pendingToolResult = null
                        pendingToolName = null
                    }
                }
            }

            if (pendingToolResult != null || pendingToolName != null) {
                result.add(
                    ChatMessage(
                        sender = MessageSender.ASSISTANT,
                        text = "",
                        toolResult = pendingToolResult,
                        toolName = pendingToolName
                    )
                )
            }

            return result
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        audioPlaybackController?.release()
    }
}
