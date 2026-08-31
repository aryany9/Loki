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
import dev.loki.android.core.conversation.ConversationTurn
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    val modelState: StateFlow<LlmModelState> = conversationManager.llmEngine.modelState
    val currentConversationId: String? get() = conversationManager.currentConversationId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

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

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
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
        source: String
    ) {
        val userMessage = ChatMessage(sender = MessageSender.USER, text = displayInput)
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
            var latestToolResult: ToolResult? = null
            var latestToolName: String? = null

            try {
                if (conversationManager.currentConversationId == null) {
                    conversationManager.createConversation()
                    refreshConversations()
                }

                val chatSession = conversationManager.newChatSession()
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
                            latestToolName = event.toolName
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        isThinking = true,
                                        isStreaming = false,
                                        text = "Executing ${event.toolName}...",
                                        toolName = event.toolName
                                    )
                                } else msg
                            }
                        }
                        is ConversationEvent.ToolExecuted -> {
                            latestToolResult = event.result
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        toolResult = event.result,
                                        toolName = latestToolName ?: msg.toolName
                                    )
                                } else msg
                            }
                        }
                        is ConversationEvent.GeneratingToken -> {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdateTime >= 50L) {
                                lastUpdateTime = now
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == inFlightMessageId) {
                                        msg.copy(
                                            text = event.partial,
                                            isThinking = false,
                                            isStreaming = true,
                                            toolResult = latestToolResult ?: msg.toolResult,
                                            toolName = latestToolName ?: msg.toolName
                                        )
                                    } else msg
                                }
                            }
                        }
                        is ConversationEvent.Completed -> {
                            val finalToolResult = event.toolResult ?: latestToolResult
                            val finalToolName = latestToolName
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == inFlightMessageId) {
                                    msg.copy(
                                        text = event.finalResponse,
                                        isThinking = false,
                                        isStreaming = false,
                                        toolResult = finalToolResult,
                                        toolName = finalToolName ?: msg.toolName
                                    )
                                } else msg
                            }
                            refreshConversations()
                        }
                        is ConversationEvent.Error -> {
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
                throw e
            } catch (e: Throwable) {
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
                val audioFloats = recorder.recordUtterance()
                _isRecording.value = false
                voiceStartCuePlayed = false
                activeAudioRecorder = null

                if (audioFloats.isNotEmpty()) {
                    val wavBytes = WavEncoder.pcmFloatsToWav(audioFloats)
                    executeChatTurn(
                        displayInput = "[Voice Audio]",
                        userInput = "",
                        audioBytes = wavBytes,
                        source = "CHAT_DIRECT_AUDIO"
                    )
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

                sttEngine.startListening().collect { event ->
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
                                timestamp = turn.timestamp
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
}
