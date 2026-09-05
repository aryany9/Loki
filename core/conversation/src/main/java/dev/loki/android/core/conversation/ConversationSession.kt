package dev.loki.android.core.conversation

import android.content.Context
import dev.loki.android.core.llm.LlmEngine
import dev.loki.android.core.tools.PermissionManager
import dev.loki.android.core.tools.Tool
import dev.loki.android.core.tools.ToolErrorCode
import dev.loki.android.core.tools.ToolExecutionResult
import dev.loki.android.core.tools.ToolRegistry
import dev.loki.android.core.tools.ToolResult
import dev.loki.android.core.voice.tts.TtsEngine
import dev.loki.android.core.models.AgentConfig
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Holds the in-progress confirmation gate for a destructive tool call.
 */
data class PendingConfirmation(
    val toolName: String,
    val arguments: Map<String, Any?>,
    val repeatBack: String = "",
    val deferred: CompletableDeferred<Boolean> = CompletableDeferred()
)

/**
 * ConversationSession executes a ReAct-style agent loop for a specific conversation context.
 * Can be persistent (for multi-turn Chat UI) or ephemeral (for hands-free Voice turns).
 */
open class ConversationSession(
    private val context: Context,
    val llmEngine: LlmEngine,
    val toolRegistry: ToolRegistry,
    val ttsEngine: TtsEngine? = null,
    val conversationContext: ConversationContext = ConversationContext(),
    val permissionManager: PermissionManager = PermissionManager(),
    val agentConfig: AgentConfig = AgentConfig(),
    private val maxIterations: Int = 5,
    val conversationStore: ConversationStore? = null,
    val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    val memoryStore: MemoryStore = MemoryStore(context, ioDispatcher),
    val conversationId: String? = null,
    val contactCandidateRegistry: MutableMap<String, ContactCandidate> = mutableMapOf(),
    var pendingAsk: PendingAsk? = null,
    val onPendingAskUpdated: ((PendingAsk?) -> Unit)? = null,
    var pendingVoiceConfirmation: PendingVoiceConfirmation? = null,
    val onPendingVoiceConfirmationUpdated: ((PendingVoiceConfirmation?) -> Unit)? = null
) {

    var activeCapability: String? = null
        internal set

    var taskState: TaskState? = null
        internal set

    init {
        if (pendingVoiceConfirmation != null) {
            val cand = pendingVoiceConfirmation!!.candidate
            if (cand.id.isNotBlank()) {
                contactCandidateRegistry[cand.id.lowercase()] = cand
            }
            if (cand.name.isNotBlank() && cand.name != "the contact") {
                contactCandidateRegistry[cand.name.lowercase()] = cand
            }
            if (taskState == null) {
                taskState = ContactResolution(
                    candidates = listOf(cand),
                    selectedId = cand.id,
                    isAsked = pendingVoiceConfirmation!!.isAsked
                )
                activeCapability = "calling"
            } else if (taskState is ContactResolution) {
                val res = taskState as ContactResolution
                if (res.isAsked != pendingVoiceConfirmation!!.isAsked) {
                    taskState = res.copy(isAsked = pendingVoiceConfirmation!!.isAsked)
                }
            }
        }
        if (taskState == null && pendingAsk != null && pendingAsk!!.candidates.isNotEmpty()) {
            taskState = ContactResolution(
                candidates = pendingAsk!!.candidates,
                selectedId = pendingAsk!!.selectedId,
                isAsked = pendingVoiceConfirmation?.isAsked ?: false
            )
            activeCapability = "calling"
        }
        if (pendingVoiceConfirmation == null && taskState is ContactResolution && (taskState as ContactResolution).selectedId != null) {
            val res = taskState as ContactResolution
            val selected = res.candidates.firstOrNull { it.id == res.selectedId }
            if (selected != null) {
                val phoneArg = selected.phoneNumber
                val digits = phoneArg.filter { it.isDigit() }
                val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                val suffixPart = if (suffix.isNotBlank()) ", the number ending in $suffix" else ""
                pendingVoiceConfirmation = PendingVoiceConfirmation(
                    candidate = selected,
                    repeatBack = "Shall I call ${selected.name}$suffixPart?",
                    isAsked = res.isAsked
                )
            }
        }
    }

    var needsSchemaInjection: Boolean = true
        internal set

    private val lastContactCandidates: MutableMap<String, ContactCandidate>
        get() = contactCandidateRegistry

    @Volatile
    private var pendingConfirmation: PendingConfirmation? = null

    private fun isValidPhoneNumber(phone: String?): Boolean {
        if (phone.isNullOrBlank()) return false
        val trimmed = phone.trim()
        if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("N/A", ignoreCase = true)) return false
        if (trimmed.contains("...") || trimmed.contains("…")) return false
        val digitCount = trimmed.count { it.isDigit() }
        return digitCount >= 5
    }

    /**
     * Resolves the currently pending confirmation gate with the given [accepted] verdict.
     * Safe to call from any thread. No-ops if there is no pending gate.
     */
    fun respondToConfirmation(accepted: Boolean) {
        pendingConfirmation?.deferred?.complete(accepted)
    }

    private suspend fun recordTurn(turn: ConversationTurn) {
        conversationContext.append(turn)
        if (conversationStore != null && conversationId != null) {
            try {
                conversationStore.appendTurn(conversationId, turn)
            } catch (e: Throwable) {
                android.util.Log.w("ConversationSession", "Non-fatal: Failed to persist turn", e)
            }
        }
    }

    open fun processUtterance(
        userInput: String,
        audioBytes: ByteArray? = null,
        enableTts: Boolean = true,
        source: String = "TEXT"
    ): Flow<ConversationEvent> = channelFlow {
        val turnId = TurnLogger.newTurnId()
        TurnLogger.logTurnStart(turnId, source)

        val hasAudio = audioBytes != null && audioBytes.isNotEmpty()
        if (userInput.isBlank() && !hasAudio) {
            TurnLogger.logError(turnId, "Empty user input received")
            send(ConversationEvent.Error("Empty user input"))
            return@channelFlow
        }

        if (isSimpleGreeting(userInput) && !hasAudio) {
            val response = "Hello! How can I help you?"
            recordTurn(ConversationTurn.User(userInput))
            recordTurn(ConversationTurn.Assistant(response))
            send(ConversationEvent.Completed(response))
            return@channelFlow
        }

        if (source == "VOICE" && userInput.isNotBlank()) {
            TurnLogger.logTranscript(turnId, userInput)
        }

        val displayInput = if (userInput.isNotBlank()) userInput else if (hasAudio) "[Voice Audio]" else ""
        recordTurn(ConversationTurn.User(displayInput))
        send(ConversationEvent.Thinking(displayInput))

        var iterations = 0
        var lastToolResult: ToolResult? = null
        var finalResponseText = ""
        var correctiveRetryUsed = false
        var isActivationTurn = false
        var endedInAskUser = false

        // Initialize persistent native conversation with KV-prefilled CORE prompt ONCE per logical conversation session
        if (conversationContext.getTurns().size <= 1) {
            needsSchemaInjection = true
            val activeBackend = when (val state = llmEngine.modelState.value) {
                is dev.loki.android.core.llm.LlmModelState.Ready -> state.activeBackend
                else -> null
            }
            val corePrompt = buildCoreSystemPrompt(isCompact = (activeBackend == dev.loki.android.core.models.ExecutionBackend.NPU))
            val effectiveConfig = agentConfig.copy(systemInstruction = corePrompt)
            val started = llmEngine.startConversation(effectiveConfig)
            TurnLogger.logTurnStart(turnId, "STATEFUL_INIT (success=$started)")
        }

        llmEngine.onContextCompacted = { message ->
            needsSchemaInjection = true
            trySend(ConversationEvent.ContextCompacted(message))
        }

        try {
            var currentTurnPrompt = userInput

            while (iterations < maxIterations) {
                iterations++

                val currentAvailableTools = toolRegistry.getAvailableTools(
                    context = context,
                    permissionManager = permissionManager,
                    activeCapability = activeCapability,
                    advancingTool = taskState?.advancingTool,
                    taskState = taskState as? dev.loki.android.core.tools.TaskStateGate
                )
                val currentDisabledTools = toolRegistry.getDisabledTools(
                    context = context,
                    permissionManager = permissionManager,
                    activeCapability = activeCapability,
                    advancingTool = taskState?.advancingTool
                )
                TurnLogger.logTools(turnId, currentAvailableTools.size, currentDisabledTools.size)

                val activeBackend = when (val state = llmEngine.modelState.value) {
                    is dev.loki.android.core.llm.LlmModelState.Ready -> state.activeBackend
                    else -> null
                }
                val isCompactSchemas = (activeBackend == dev.loki.android.core.models.ExecutionBackend.NPU)
                val shouldInjectSchemas = needsSchemaInjection || (conversationContext.getTurns().size <= 1 && iterations == 1)
                val perTurnPrompt = buildPerTurnPrompt(
                    availableTools = currentAvailableTools,
                    disabledTools = currentDisabledTools,
                    activeCapability = activeCapability,
                    taskState = taskState,
                    isActivationTurn = isActivationTurn,
                    includeToolSchemas = shouldInjectSchemas,
                    compactToolSchemas = isCompactSchemas
                )
                if (shouldInjectSchemas) {
                    needsSchemaInjection = false
                }
                isActivationTurn = false

                val content = if (iterations == 1) userInput else currentTurnPrompt

                val promptToSend = buildString {
                    if (perTurnPrompt.isNotBlank()) {
                        append(perTurnPrompt)
                        if (content.isNotBlank()) {
                            append("\n\n")
                        }
                    }
                    append(content)
                    if (correctiveRetryUsed) {
                        append("\nReturn exactly one JSON object and nothing else. Do not use Markdown, explanations, or additional turns.")
                    }
                }

                // DIAGNOSTIC (Requirement 8): Log application history and prompt stats before generation
                val appTurnsCount = conversationContext.getTurns().size
                val appTokenEst = conversationContext.estimateTokenCount()
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic] Generation iteration $iterations:")
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic]   app history turns count   = $appTurnsCount")
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic]   app token count (est)     = $appTokenEst")
                android.util.Log.i("ConversationSession", "[Loki/Diagnostic]   new prompt char length    = ${promptToSend.length}")

                TurnLogger.logPrompt(turnId, promptToSend)

                val defaultBudget = if (activeBackend == dev.loki.android.core.models.ExecutionBackend.NPU) 256 else 512
                val maxTokens = agentConfig.generationConfig.maxOutputTokens ?: defaultBudget
                val cumulativePartial = StringBuilder()
                val scopedGrammar = GrammarBuilder.buildFrom(currentAvailableTools)
                val llmResult = llmEngine.generate(
                    prompt = promptToSend,
                    audioBytes = if (iterations == 1) audioBytes else null,
                    grammar = scopedGrammar,
                    maxTokens = maxTokens,
                    onToken = { token ->
                        cumulativePartial.append(token)
                        trySend(ConversationEvent.GeneratingToken(cumulativePartial.toString()))
                    },
                    source = source
                )

                if (llmResult.isFailure) {
                    val errorMsg = llmResult.exceptionOrNull()?.message ?: "LLM inference failed"
                    TurnLogger.logError(turnId, errorMsg, llmResult.exceptionOrNull())
                    send(ConversationEvent.Error(errorMsg))
                    return@channelFlow
                }

                val rawOutput = llmResult.getOrNull() ?: ""
                TurnLogger.logLlmOutput(turnId, rawOutput)

                val parsed = ToolCallParser.parse(rawOutput)
                TurnLogger.logParse(turnId, parsed)

                when (parsed) {
                    is ParsedLlmResponse.ToolCall -> {
                        send(ConversationEvent.ToolExecuting(parsed.tool, parsed.arguments))
                        recordTurn(
                            ConversationTurn.ToolCall(
                                tool = parsed.tool,
                                arguments = parsed.arguments.mapValues { it.value?.toString() ?: "" }
                            )
                        )

                        // ── ask_user turn-intent protocol (no side effects / round-trip) ─
                        if (parsed.tool == "ask_user") {
                            val rawText = parsed.arguments["text"]?.toString()?.trim() ?: ""
                            val hasPendingConfirm = pendingVoiceConfirmation != null
                            val isRawValid = rawText.isNotBlank() && !containsProtocolArtifacts(rawText)

                            // Loop breaker: If confirmation question was already asked and model emits ask_user again,
                            // break out of the infinite repetition loop, cancel the task, and don't re-arm mic.
                            if (hasPendingConfirm && pendingVoiceConfirmation!!.isAsked) {
                                val cancelMsg = "Okay, cancelled."
                                finalResponseText = cancelMsg
                                recordTurn(ConversationTurn.Assistant(finalResponseText))
                                activeCapability = null
                                taskState = null
                                contactCandidateRegistry.clear()
                                pendingAsk = null
                                onPendingAskUpdated?.invoke(null)
                                pendingVoiceConfirmation = null
                                onPendingVoiceConfirmationUpdated?.invoke(null)
                                endedInAskUser = false
                                break
                            }

                            val (sanitized, wasAppRenderedConfirm) = when {
                                isRawValid -> {
                                    rawText to false
                                }
                                hasPendingConfirm -> {
                                    // Empty-ask_user enhancement: when parsed ask_user has empty text and
                                    // pendingVoiceConfirmation exists, the app emits AskUser with its rendered repeat-back string.
                                    pendingVoiceConfirmation!!.repeatBack to true
                                }
                                else -> {
                                    if (rawText.isNotBlank()) {
                                        TurnLogger.logError(turnId, "Sanitized malformed LLM output (contained protocol artifacts): $rawText")
                                        try {
                                            android.util.Log.d("LokiTurn", "[LokiTurn] Sanitized malformed LLM output (contained protocol artifacts): $rawText")
                                        } catch (_: Throwable) {}
                                    }
                                    RECOVERY_RESPONSE_TEXT to false
                                }
                            }
                            finalResponseText = sanitized
                            recordTurn(ConversationTurn.Assistant(finalResponseText))

                            if (hasPendingConfirm && (isRawValid || wasAppRenderedConfirm)) {
                                val updatedConfirm = pendingVoiceConfirmation!!.copy(isAsked = true)
                                pendingVoiceConfirmation = updatedConfirm
                                onPendingVoiceConfirmationUpdated?.invoke(updatedConfirm)
                                taskState = (taskState as? ContactResolution)?.copy(isAsked = true)
                            }

                            val currentResolution = taskState as? ContactResolution
                            val updatedPending = PendingAsk(
                                question = sanitized,
                                candidates = currentResolution?.candidates ?: pendingAsk?.candidates ?: emptyList(),
                                selectedId = currentResolution?.selectedId ?: pendingAsk?.selectedId
                            )
                            pendingAsk = updatedPending
                            onPendingAskUpdated?.invoke(updatedPending)

                            endedInAskUser = true
                            break
                        }
                        // ─────────────────────────────────────────────────────────────────

                        // ── TaskState advancing tool: select_contact ─────────────────────
                        if (parsed.tool == "select_contact") {
                            val resolution = taskState as? ContactResolution
                            if (resolution == null || resolution.advancingTool != "select_contact") {
                                val coachMessage = "Tool 'select_contact' is unavailable. No contact selection is currently pending."
                                val coachedResult = ToolResult.error(coachMessage, ToolErrorCode.EXECUTION_ERROR)
                                lastToolResult = coachedResult
                                send(ConversationEvent.ToolExecuted(parsed.tool, coachedResult))
                                recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, coachedResult))
                                currentTurnPrompt = "Tool result for select_contact: $coachMessage"
                                continue
                            }

                            val candidateId = parsed.arguments["candidate_id"]?.toString()?.trim()
                            val matchedCandidate = resolution.candidates.firstOrNull { it.id.equals(candidateId, ignoreCase = true) }
                            if (matchedCandidate == null) {
                                val validOptions = resolution.candidates.joinToString(", ") { "${it.id}: ${it.name}" }
                                val coachMessage = "Invalid candidate ID '$candidateId'. Valid candidates are: $validOptions. Please select a valid candidate ID."
                                val errorResult = ToolResult.error(coachMessage, ToolErrorCode.VALIDATION_ERROR)
                                lastToolResult = errorResult
                                send(ConversationEvent.ToolExecuted(parsed.tool, errorResult))
                                recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, errorResult))
                                currentTurnPrompt = "Tool result for select_contact: $coachMessage"
                                continue
                            }

                            // Advance state
                            taskState = resolution.copy(selectedId = matchedCandidate.id)
                            lastContactCandidates[matchedCandidate.id.lowercase()] = matchedCandidate
                            lastContactCandidates[matchedCandidate.name.lowercase()] = matchedCandidate

                            val phoneArg = matchedCandidate.phoneNumber
                            val digits = phoneArg.filter { it.isDigit() }
                            val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                            val suffixPart = if (suffix.isNotBlank()) ", the number ending in $suffix" else ""
                            val confirmationState = PendingVoiceConfirmation(
                                candidate = matchedCandidate,
                                repeatBack = "Shall I call ${matchedCandidate.name}$suffixPart?",
                                isAsked = false
                            )
                            pendingVoiceConfirmation = confirmationState
                            onPendingVoiceConfirmationUpdated?.invoke(confirmationState)
                            val successResult = ToolResult.success(
                                mapOf(
                                    "candidate_id" to matchedCandidate.id,
                                    "name" to matchedCandidate.name,
                                    "status" to "selected"
                                )
                            )
                            lastToolResult = successResult
                            send(ConversationEvent.ToolExecuted(parsed.tool, successResult))
                            recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, successResult))
                            currentTurnPrompt = "Tool result for select_contact: Selected ${matchedCandidate.name} (${matchedCandidate.id}). Ask the user for confirmation before calling."
                            continue
                        }
                        // ─────────────────────────────────────────────────────────────────

                        val targetTool = toolRegistry.get(parsed.tool)
                        if (targetTool == null) {
                            val notFoundResult = ToolResult.error("Tool '${parsed.tool}' not found", ToolErrorCode.NOT_FOUND)
                            lastToolResult = notFoundResult
                            send(ConversationEvent.ToolExecuted(parsed.tool, notFoundResult))
                            recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, notFoundResult))
                            currentTurnPrompt = "Tool result for ${parsed.tool}: Tool '${parsed.tool}' not found."
                            continue
                        }

                        // ── Capability & scope validation ────────────────────────────────
                        val isInScope = (activeCapability == null) ||
                            (targetTool.capability == "general") ||
                            (targetTool.capability == activeCapability) ||
                            (parsed.tool == taskState?.advancingTool)

                        if (!isInScope) {
                            val coachMessage = if (taskState != null && !taskState!!.resolved) {
                                "Tool '${parsed.tool}' is unavailable. Please resolve the current task first."
                            } else {
                                "Tool '${parsed.tool}' is currently unavailable while $activeCapability is active."
                            }
                            val coachedResult = ToolResult.error(coachMessage, ToolErrorCode.EXECUTION_ERROR)
                            lastToolResult = coachedResult
                            send(ConversationEvent.ToolExecuted(parsed.tool, coachedResult))
                            recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, coachedResult))
                            currentTurnPrompt = "Tool result for ${parsed.tool}: $coachMessage"
                            continue
                        }

                        // Activation rule: tool of domain capability activates it
                        if (activeCapability == null && targetTool.capability != "general") {
                            activeCapability = targetTool.capability
                            isActivationTurn = true
                        }
                        // ─────────────────────────────────────────────────────────────────

                        val resolvedArguments = parsed.arguments.toMutableMap()
                        var resolvedContactCandidate: ContactCandidate? = null
                        if (parsed.tool == "call_contact") {
                            val candId = parsed.arguments["candidate_id"]?.toString()?.trim()
                            val nameArg = parsed.arguments["name"]?.toString()?.trim()
                            val phoneArg = parsed.arguments["phone_number"]?.toString()?.trim()

                            if (!phoneArg.isNullOrBlank()) {
                                try {
                                    android.util.Log.d("LokiTurn", "[LokiTurn] Discarded model-supplied phone_number '$phoneArg' in favor of registry resolution")
                                } catch (_: Throwable) {}
                            }

                            // Candidate resolution order: 1) taskState candidates, 2) contactCandidateRegistry, 3) name match
                            var cand: ContactCandidate? = null
                            val resolution = taskState as? ContactResolution
                            if (resolution != null) {
                                cand = if (!candId.isNullOrBlank()) {
                                    resolution.candidates.firstOrNull { it.id.equals(candId, ignoreCase = true) }
                                        ?: resolution.candidates.firstOrNull { it.name.equals(candId, ignoreCase = true) }?.also {
                                            try {
                                                android.util.Log.d("LokiTurn", "[LokiTurn] Coerced candidate_id '$candId' as contact name")
                                            } catch (_: Throwable) {}
                                        }
                                } else if (!nameArg.isNullOrBlank()) {
                                    resolution.candidates.firstOrNull { it.name.equals(nameArg, ignoreCase = true) }
                                        ?: resolution.candidates.firstOrNull { it.id.equals(nameArg, ignoreCase = true) }
                                } else if (resolution.selectedId != null) {
                                    resolution.candidates.firstOrNull { it.id == resolution.selectedId }
                                } else if (resolution.candidates.size == 1) {
                                    resolution.candidates[0]
                                } else null
                            }

                            if (cand == null && !candId.isNullOrBlank()) {
                                cand = contactCandidateRegistry[candId.lowercase()]
                                if (cand != null && !candId.startsWith("c", ignoreCase = true)) {
                                    try {
                                        android.util.Log.d("LokiTurn", "[LokiTurn] Coerced candidate_id '$candId' as contact name")
                                    } catch (_: Throwable) {}
                                }
                            }
                            if (cand == null && !nameArg.isNullOrBlank()) {
                                cand = contactCandidateRegistry[nameArg.lowercase()]
                                    ?: contactCandidateRegistry.values.firstOrNull { it.id.equals(nameArg, ignoreCase = true) }
                            }

                            if (cand != null) {
                                resolvedContactCandidate = cand
                                resolvedArguments["phone_number"] = cand.phoneNumber
                                if (!nameArg.isNullOrBlank() || cand.name != "the contact") {
                                    resolvedArguments["name"] = cand.name
                                }
                                resolvedArguments["candidate_id"] = cand.id
                                taskState = (resolution ?: ContactResolution(candidates = listOf(cand))).copy(selectedId = cand.id)
                                contactCandidateRegistry[cand.id.lowercase()] = cand
                                if (!nameArg.isNullOrBlank() || cand.name != "the contact") {
                                    contactCandidateRegistry[cand.name.lowercase()] = cand
                                }
                            } else if (!candId.isNullOrBlank()) {
                                val staleMessage = "Contact selection '$candId' is stale or invalid. Please search contacts using lookup_contact first."
                                val staleResult = ToolResult.error(staleMessage, ToolErrorCode.EXECUTION_ERROR)
                                lastToolResult = staleResult
                                send(ConversationEvent.ToolExecuted(parsed.tool, staleResult))
                                recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, staleResult))
                                currentTurnPrompt = "Tool result for call_contact: $staleMessage"
                                continue
                            } else {
                                val searchQuery = nameArg?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }

                                if (!searchQuery.isNullOrBlank() && toolRegistry.get("lookup_contact") != null) {
                                    val lookupExec = toolRegistry.executeDetailed(
                                        context = context,
                                        name = "lookup_contact",
                                        arguments = mapOf("query" to searchQuery),
                                        permissionManager = permissionManager
                                    )
                                    if (lookupExec is ToolExecutionResult.Success) {
                                        val contactsJson = lookupExec.toolResult.data?.get("contacts")
                                            ?: lookupExec.toolResult.data?.get("matches")
                                        val candidates = if (!contactsJson.isNullOrBlank()) {
                                            try {
                                                Json.decodeFromString<List<ContactCandidate>>(contactsJson)
                                            } catch (_: Throwable) {
                                                emptyList<ContactCandidate>()
                                            }
                                        } else emptyList<ContactCandidate>()

                                        for (c in candidates) {
                                            contactCandidateRegistry[c.id.lowercase()] = c
                                            contactCandidateRegistry[c.name.lowercase()] = c
                                        }

                                        if (candidates.size == 1) {
                                            val resolved = candidates[0]
                                            cand = resolved
                                            resolvedContactCandidate = resolved
                                            resolvedArguments["phone_number"] = resolved.phoneNumber
                                            resolvedArguments["name"] = resolved.name
                                            resolvedArguments["candidate_id"] = resolved.id
                                            taskState = ContactResolution(candidates = candidates, selectedId = resolved.id)
                                        } else if (candidates.size > 1) {
                                            taskState = ContactResolution(candidates = candidates)
                                            val coachMessage = buildDuplicateDisambiguationCoachMessage(candidates, searchQuery)
                                            val coachedResult = ToolResult.error(coachMessage, ToolErrorCode.EXECUTION_ERROR)
                                            lastToolResult = coachedResult
                                            send(ConversationEvent.ToolExecuted(parsed.tool, coachedResult))
                                            recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, coachedResult))
                                            currentTurnPrompt = "Tool result for call_contact: $coachMessage"
                                            continue
                                        } else {
                                            val errorMsg = "No contact found matching '$searchQuery'."
                                            val errorResult = ToolResult.error(errorMsg, ToolErrorCode.EXECUTION_ERROR)
                                            lastToolResult = errorResult
                                            send(ConversationEvent.ToolExecuted(parsed.tool, errorResult))
                                            recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, errorResult))
                                            currentTurnPrompt = "Tool result for call_contact: $errorMsg"
                                            continue
                                        }
                                    }
                                } else if (!searchQuery.isNullOrBlank()) {
                                    val staleMessage = "Contact selection '$searchQuery' is stale or invalid. Please search contacts using lookup_contact first."
                                    val staleResult = ToolResult.error(staleMessage, ToolErrorCode.EXECUTION_ERROR)
                                    lastToolResult = staleResult
                                    send(ConversationEvent.ToolExecuted(parsed.tool, staleResult))
                                    recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, staleResult))
                                    currentTurnPrompt = "Tool result for call_contact: $staleMessage"
                                    continue
                                }
                            }
                        }
                        // ─────────────────────────────────────────────────────────────────

                        // ── Confirmation gate (D1/D2) ─────────────────────────────────────
                        val voiceSources = setOf("VOICE", "DIRECT_AUDIO", "VOICE_FOLLOW_UP")
                        val isVoiceSource = source in voiceSources

                        if (toolRegistry.requiresConfirmation(parsed.tool)) {
                            if (isVoiceSource) {
                                val targetCandidate = resolvedContactCandidate
                                    ?: (taskState as? ContactResolution)?.candidates?.firstOrNull { it.id == resolvedArguments["candidate_id"] }
                                    ?: (taskState as? ContactResolution)?.candidates?.firstOrNull { it.name.equals(resolvedArguments["name"]?.toString(), ignoreCase = true) }
                                    ?: resolvedArguments["candidate_id"]?.toString()?.lowercase()?.let { contactCandidateRegistry[it] }
                                    ?: resolvedArguments["name"]?.toString()?.lowercase()?.let { contactCandidateRegistry[it] }
                                    ?: if (pendingVoiceConfirmation != null && (
                                            pendingVoiceConfirmation!!.candidate.id.equals(resolvedArguments["candidate_id"]?.toString(), ignoreCase = true) ||
                                            pendingVoiceConfirmation!!.candidate.name.equals(resolvedArguments["name"]?.toString(), ignoreCase = true)
                                       )) pendingVoiceConfirmation!!.candidate else null

                                val isConfirmed = targetCandidate != null &&
                                    pendingVoiceConfirmation != null &&
                                    pendingVoiceConfirmation!!.isAsked &&
                                    (pendingVoiceConfirmation!!.candidate.id.equals(targetCandidate.id, ignoreCase = true) ||
                                     pendingVoiceConfirmation!!.candidate.phoneNumber == targetCandidate.phoneNumber ||
                                     pendingVoiceConfirmation!!.candidate.name.equals(targetCandidate.name, ignoreCase = true))

                                if (!isConfirmed) {
                                    val contactName = targetCandidate?.name?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: resolvedArguments["name"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: "the contact"
                                    val phoneArg = targetCandidate?.phoneNumber
                                        ?: resolvedArguments["phone_number"]?.toString()?.trim()
                                        ?: ""
                                    val digits = phoneArg.filter { it.isDigit() }
                                    val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                                    val suffixPart = if (suffix.isNotBlank()) ", the number ending in $suffix" else ""
                                    val repeatBack = "Shall I call $contactName$suffixPart?"
                                    val coachMessage = "Action requires verbal confirmation. Do not execute yet. First ask the user for confirmation via ask_user (e.g. '$repeatBack'). Only execute this tool after the user verbally confirms."

                                    val resolvedCandForConfirm = targetCandidate ?: ContactCandidate(
                                        id = resolvedArguments["candidate_id"]?.toString()?.ifBlank { "c1" } ?: "c1",
                                        name = contactName,
                                        phoneNumber = phoneArg
                                    )
                                    if (resolvedCandForConfirm.id.isNotBlank()) {
                                        contactCandidateRegistry[resolvedCandForConfirm.id.lowercase()] = resolvedCandForConfirm
                                    }
                                    if (resolvedCandForConfirm.name.isNotBlank() && resolvedCandForConfirm.name != "the contact") {
                                        contactCandidateRegistry[resolvedCandForConfirm.name.lowercase()] = resolvedCandForConfirm
                                    }
                                    val confirmationState = PendingVoiceConfirmation(
                                        candidate = resolvedCandForConfirm,
                                        repeatBack = repeatBack,
                                        isAsked = false
                                    )
                                    pendingVoiceConfirmation = confirmationState
                                    onPendingVoiceConfirmationUpdated?.invoke(confirmationState)

                                    val coachedResult = ToolResult.error(coachMessage, ToolErrorCode.EXECUTION_ERROR)
                                    lastToolResult = coachedResult
                                    send(ConversationEvent.ToolExecuted(parsed.tool, coachedResult))
                                    recordTurn(ConversationTurn.ToolExecutionResult(parsed.tool, coachedResult))
                                    currentTurnPrompt = "Tool result for ${parsed.tool}: $coachMessage"
                                    continue
                                }
                            } else {
                                val repeatBack = toolRegistry.describeAction(parsed.tool, resolvedArguments)
                                val gate = PendingConfirmation(parsed.tool, resolvedArguments, repeatBack)
                                pendingConfirmation = gate
                                send(ConversationEvent.ConfirmationRequired(parsed.tool, repeatBack))

                                val accepted: Boolean = try {
                                    withTimeout(CONFIRMATION_TIMEOUT_MS) {
                                        gate.deferred.await()
                                    }
                                } catch (e: TimeoutCancellationException) {
                                    false // treat timeout as denial
                                } finally {
                                    pendingConfirmation = null
                                }

                                if (!accepted) {
                                    val isDenied = gate.deferred.isCompleted && !gate.deferred.isCancelled
                                    val denialMessage = if (isDenied) {
                                        "User declined the action."
                                    } else {
                                        "No response received; action cancelled."
                                    }
                                    val denialResult = ToolResult.error(denialMessage, ToolErrorCode.EXECUTION_ERROR)
                                    lastToolResult = denialResult
                                    send(ConversationEvent.ToolExecuted(parsed.tool, denialResult))
                                    recordTurn(
                                        ConversationTurn.ToolExecutionResult(
                                            tool = parsed.tool,
                                            result = denialResult
                                        )
                                    )
                                    // Feed denial to model so it can respond conversationally
                                    currentTurnPrompt = "Tool result for ${parsed.tool}: $denialMessage"
                                    continue
                                }
                            }
                        }
                        // ─────────────────────────────────────────────────────────────────

                        val execResult = toolRegistry.executeDetailed(
                            context = context,
                            name = parsed.tool,
                            arguments = resolvedArguments,
                            permissionManager = permissionManager
                        )

                        when (execResult) {
                            is ToolExecutionResult.Success -> {
                                val result = execResult.toolResult
                                lastToolResult = result
                                val modelResult = maskToolResultForModel(result)
                                TurnLogger.logToolExecution(turnId, parsed.tool, true, modelResult.data.toString())
                                send(ConversationEvent.ToolExecuted(parsed.tool, modelResult))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = modelResult
                                    )
                                )

                                if (parsed.tool == "lookup_contact") {
                                    activeCapability = "calling"
                                    isActivationTurn = true
                                    val contactsJson = result.data?.get("contacts") ?: result.data?.get("matches")
                                    val candidates = if (!contactsJson.isNullOrBlank()) {
                                        try {
                                            Json.decodeFromString<List<ContactCandidate>>(contactsJson)
                                        } catch (e: Throwable) {
                                            emptyList<ContactCandidate>()
                                        }
                                    } else emptyList<ContactCandidate>()

                                    for (c in candidates) {
                                        contactCandidateRegistry[c.id.lowercase()] = c
                                        contactCandidateRegistry[c.name.lowercase()] = c
                                    }

                                    // Optimization C: Unique exact display-name pre-selection.
                                    // When multiple contacts are returned, if exactly one has a name
                                    // that exactly matches the query (case-insensitive, trimmed),
                                    // auto-select it and skip CONTACT_DISAMBIGUATION.
                                    val query = parsed.arguments["query"]?.toString()?.trim() ?: ""
                                    val exactMatches = if (query.isNotBlank() && candidates.size > 1) {
                                        candidates.filter { it.name.trim().equals(query, ignoreCase = true) }
                                    } else emptyList()

                                    if (exactMatches.size == 1) {
                                        // Unique exact match: auto-select and advance to CALL_CONFIRMATION
                                        val autoSelected = exactMatches[0]
                                        taskState = ContactResolution(
                                            candidates = candidates,
                                            selectedId = autoSelected.id,
                                            confirmed = false
                                        )
                                        lastContactCandidates[autoSelected.id.lowercase()] = autoSelected
                                        lastContactCandidates[autoSelected.name.lowercase()] = autoSelected

                                        val phoneArg = autoSelected.phoneNumber
                                        val digits = phoneArg.filter { it.isDigit() }
                                        val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                                        val suffixPart = if (suffix.isNotBlank()) ", the number ending in $suffix" else ""
                                        val confirmationState = PendingVoiceConfirmation(
                                            candidate = autoSelected,
                                            repeatBack = "Shall I call ${autoSelected.name}$suffixPart?",
                                            isAsked = false
                                        )
                                        pendingVoiceConfirmation = confirmationState
                                        onPendingVoiceConfirmationUpdated?.invoke(confirmationState)

                                        android.util.Log.d("ConversationSession", "[ExactMatch] Auto-selected '${autoSelected.name}' (${autoSelected.id}), skipping disambiguation.")
                                        currentTurnPrompt = "Tool result for lookup_contact: Exact match found. Selected ${autoSelected.name} (${autoSelected.id}). Ask the user for confirmation before calling."
                                    } else {
                                        taskState = ContactResolution(
                                            candidates = candidates,
                                            selectedId = if (candidates.size == 1) candidates[0].id else null,
                                            confirmed = false
                                        )

                                        if (candidates.size > 1) {
                                            val coachMessage = buildDuplicateDisambiguationCoachMessage(candidates)
                                            currentTurnPrompt = "Tool result for lookup_contact: $coachMessage"
                                        } else {
                                            val summary = candidates.joinToString(", ") { it.name }
                                            currentTurnPrompt = "Tool result for lookup_contact: Found ${candidates.size} matching contacts: $summary"
                                        }
                                    }
                                    continue
                                }

                                if (parsed.tool == "call_contact") {
                                    taskState = (taskState as? ContactResolution)?.copy(confirmed = true)
                                    activeCapability = null
                                    taskState = null
                                    contactCandidateRegistry.clear()
                                    pendingAsk = null
                                    onPendingAskUpdated?.invoke(null)
                                    pendingVoiceConfirmation = null
                                    onPendingVoiceConfirmationUpdated?.invoke(null)
                                }

                                val fastResponse = formatFastPathResponse(parsed.tool, result)
                                if (fastResponse != null) {
                                    finalResponseText = fastResponse
                                    recordTurn(ConversationTurn.Assistant(finalResponseText))
                                    break
                                }

                                // For next ReAct iteration, send ONLY the masked tool execution result message
                                currentTurnPrompt = "Tool result for ${parsed.tool}: ${modelResult.data}"
                            }
                            is ToolExecutionResult.PermissionRequired -> {
                                TurnLogger.logPermissionCheck(turnId, execResult.permission, execResult.state.name)
                                val toolError = ToolResult.error(
                                    "Missing permission: ${execResult.permission}",
                                    ToolErrorCode.PERMISSION_DENIED
                                )
                                lastToolResult = toolError
                                send(ConversationEvent.ToolExecuted(parsed.tool, toolError))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = toolError
                                    )
                                )

                                finalResponseText = "I need the ${execResult.permission.substringAfterLast('.')} permission to do that. Please enable it in the permissions screen."
                                recordTurn(ConversationTurn.Assistant(finalResponseText))
                                break
                            }
                            is ToolExecutionResult.Error -> {
                                val result = execResult.toolResult
                                lastToolResult = result
                                TurnLogger.logToolExecution(turnId, parsed.tool, false, result.error ?: "Unknown error")
                                send(ConversationEvent.ToolExecuted(parsed.tool, result))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = result
                                    )
                                )

                                finalResponseText = formatErrorResponse(parsed.tool, result)
                                recordTurn(ConversationTurn.Assistant(finalResponseText))
                                break
                            }
                        }
                    }

                    is ParsedLlmResponse.DirectResponse -> {
                        val sanitized = if (containsProtocolArtifacts(parsed.text)) {
                            TurnLogger.logError(turnId, "Sanitized malformed LLM output (contained protocol artifacts): ${parsed.text}")
                            try {
                                android.util.Log.d("LokiTurn", "[LokiTurn] Sanitized malformed LLM output (contained protocol artifacts): ${parsed.text}")
                            } catch (_: Throwable) {}
                            RECOVERY_RESPONSE_TEXT
                        } else {
                            parsed.text
                        }
                        finalResponseText = sanitized
                        recordTurn(ConversationTurn.Assistant(finalResponseText))

                        if (pendingVoiceConfirmation != null && parsed.text.trim().endsWith("?")) {
                            val updatedConfirm = pendingVoiceConfirmation!!.copy(isAsked = true)
                            pendingVoiceConfirmation = updatedConfirm
                            onPendingVoiceConfirmationUpdated?.invoke(updatedConfirm)
                            taskState = (taskState as? ContactResolution)?.copy(isAsked = true)
                        }

                        val isUnresolvedContact = (taskState as? ContactResolution)?.let { !it.resolved && it.candidates.isNotEmpty() } ?: false
                        if (!isUnresolvedContact && (taskState == null || taskState?.resolved == true)) {
                            activeCapability = null
                            taskState = null
                        }
                        if (pendingVoiceConfirmation != null && !parsed.text.trim().endsWith("?")) {
                            pendingVoiceConfirmation = null
                            onPendingVoiceConfirmationUpdated?.invoke(null)
                            taskState = null
                            activeCapability = null
                            pendingAsk = null
                            onPendingAskUpdated?.invoke(null)
                        }
                        break
                    }

                    is ParsedLlmResponse.Malformed -> {
                        TurnLogger.logError(turnId, "Malformed LLM response: ${parsed.raw} (${parsed.error})")
                        if (!correctiveRetryUsed) {
                            correctiveRetryUsed = true
                            continue
                        }
                        finalResponseText = if (parsed.raw.isNotBlank() && !containsProtocolArtifacts(parsed.raw) && !parsed.raw.contains("{") && !parsed.raw.contains("\"tool\"")) {
                            parsed.raw.trim()
                        } else {
                            try {
                                android.util.Log.d("LokiTurn", "[LokiTurn] Sanitized malformed LLM output (contained protocol artifacts): ${parsed.raw}")
                            } catch (_: Throwable) {}
                            RECOVERY_RESPONSE_TEXT
                        }
                        recordTurn(ConversationTurn.Assistant(finalResponseText))
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            activeCapability = null
            taskState = null
            TurnLogger.logCancel(turnId, "Session cancelled")
            throw e
        } catch (e: Throwable) {
            TurnLogger.logError(turnId, "Session execution failed", e)
            send(ConversationEvent.Error(e.message ?: "Execution error"))
            return@channelFlow
        }

        if (containsProtocolArtifacts(finalResponseText)) {
            try {
                android.util.Log.d("LokiTurn", "[LokiTurn] Sanitized malformed LLM output (contained protocol artifacts): $finalResponseText")
            } catch (_: Throwable) {}
            finalResponseText = RECOVERY_RESPONSE_TEXT
        }

        if (finalResponseText.isEmpty()) {
            finalResponseText = "Task completed."
        }

        TurnLogger.logFinalResponse(turnId, finalResponseText)

        if (enableTts && ttsEngine != null) {
            send(ConversationEvent.Speaking(finalResponseText))
            ttsEngine.speak(finalResponseText)
        }

        if (endedInAskUser) {
            send(ConversationEvent.AskUser(finalResponseText))
        }

        send(ConversationEvent.Completed(finalResponseText, lastToolResult))
    }.flowOn(ioDispatcher)

    internal suspend fun buildCoreSystemPrompt(isCompact: Boolean = false): String {
        if (isCompact) {
            val sb = StringBuilder()
            sb.append("You are Loki, an on-device assistant.\n")
            sb.append("Always respond in the same language the user writes or speaks in (e.g. Hindi, English, Hinglish).\n")
            sb.append("When you need the user's answer — a choice, a confirmation, any missing information — you MUST end your turn by invoking the ask_user tool with your question as its text argument. If you end your turn with a plain question in text, the conversation ENDS and the user CANNOT reply. ask_user is the ONLY way to hand the turn to the user.\n")
            sb.append("Example — WRONG: replying with plain text \"Which Mom would you like to call?\" — RIGHT: {\"tool\": \"ask_user\", \"arguments\": {\"text\": \"Which Mom would you like to call?\"}}\n")
            sb.append("When the user asks to call or message someone, immediately call lookup_contact with their name — do not ask for contact information. Only ask which contact when a lookup returns multiple matches.\n")
            sb.append("Always output JSON: {\"tool\": \"tool_name\", \"arguments\": {...}} or {\"response\": \"conversational answer\"}.")
            return sb.toString()
        }

        val sb = StringBuilder()
        sb.append("You are Loki, a private offline Android assistant running on the user's device. You operate entirely on-device with privacy and safety as highest priority.\n\n")
        sb.append("When you need the user's answer — a choice, a confirmation, any missing information — you MUST end your turn by invoking the ask_user tool with your question as its text argument. If you end your turn with a plain question in text, the conversation ENDS and the user CANNOT reply. ask_user is the ONLY way to hand the turn to the user.\n")
        sb.append("Example — WRONG: replying with plain text \"Which Mom would you like to call?\" — RIGHT: {\"tool\": \"ask_user\", \"arguments\": {\"text\": \"Which Mom would you like to call?\"}}\n\n")
        sb.append("When the user asks to call or message someone, immediately call lookup_contact with their name — do not ask for contact information. Only ask which contact when a lookup returns multiple matches.\n\n")

        val customInstruction = agentConfig.systemInstruction.trim()
        if (customInstruction.isNotBlank() && customInstruction != AgentConfig.DEFAULT_SYSTEM_PROMPT.trim()) {
            sb.append("Additional Instructions:\n")
            sb.append(customInstruction)
            sb.append("\n\n")
        }

        val lang = agentConfig.conversationLanguage.trim()
        if (lang.isBlank() || lang.equals("auto", ignoreCase = true)) {
            sb.append("Always respond in the same language the user writes or speaks in.\n\n")
        } else {
            val locale = Locale.forLanguageTag(lang)
            val displayName = locale.getDisplayLanguage(Locale.US).ifBlank { lang }
            sb.append("Always respond in $displayName.\n\n")
        }

        val memories = memoryStore.getAll()
        if (memories.isNotEmpty()) {
            val memoryLines = mutableListOf<String>()
            var charCount = 0
            for (entry in memories) {
                if (memoryLines.size >= 10) break
                val line = "- ${entry.text.trim()}"
                if (charCount + line.length + 1 > 800) break
                memoryLines.add(line)
                charCount += line.length + 1
            }
            if (memoryLines.isNotEmpty()) {
                sb.append("What you remember about the user:\n")
                sb.append(memoryLines.joinToString("\n"))
                sb.append("\n\n")
            }
        }

        sb.append("Always output JSON: {\"tool\": \"tool_name\", \"arguments\": {...}} or {\"response\": \"conversational answer\"}.")
        return sb.toString()
    }

    internal fun buildPerTurnPrompt(
        availableTools: List<Tool>,
        disabledTools: List<Pair<Tool, String>> = emptyList(),
        activeCapability: String? = null,
        taskState: TaskState? = null,
        isActivationTurn: Boolean = false,
        includeToolSchemas: Boolean = true,
        compactToolSchemas: Boolean = false
    ): String {
        val sb = StringBuilder()

        if (includeToolSchemas && availableTools.isNotEmpty()) {
            sb.append("Available tools (respond with JSON {\"tool\": \"name\", \"arguments\": {...}}):\n")
            for (tool in availableTools) {
                if (compactToolSchemas) {
                    val params = if (tool.parameters.isNotEmpty()) {
                        tool.parameters.keys.joinToString(prefix = "(", postfix = ")")
                    } else "()"
                    sb.append("- ${tool.name}$params\n")
                } else {
                    val params = if (tool.parameters.isNotEmpty()) {
                        tool.parameters.entries.joinToString(prefix = "(", postfix = ")") { "${it.key}: ${it.value.type.name.lowercase()}" }
                    } else "()"
                    sb.append("- ${tool.name}$params: ${tool.description}\n")
                }
            }
            sb.append("\n")
        }

        if (activeCapability != null) {
            val instructions = getCapabilityInstructions(activeCapability, isActivationTurn)
            if (instructions.isNotBlank()) {
                sb.append(instructions).append("\n\n")
            }
        }

        if (taskState != null) {
            val stateBlock = renderTaskState(taskState)
            if (stateBlock.isNotBlank()) {
                sb.append(stateBlock).append("\n\n")
            }
        }

        if (disabledTools.isNotEmpty()) {
            sb.append("Disabled tools (permission not yet granted):\n")
            for ((tool, perm) in disabledTools) {
                val permName = perm.substringAfterLast('.')
                sb.append("- ${tool.name}: This tool needs the $permName permission — ask the user to grant it in Settings.\n")
            }
            sb.append("\n")
        }

        return sb.toString().trim()
    }

    internal fun getCapabilityInstructions(capability: String, isActivationTurn: Boolean): String {
        return when (capability) {
            "calling" -> {
                if (isActivationTurn) {
                    "Calling capability active. Look up or resolve contacts using available calling tools."
                } else {
                    "Calling capability active. Respond to the user naturally based on the current task state below."
                }
            }
            "media" -> {
                if (isActivationTurn) {
                    "Media guidance: The user wants to control media playback. Identify the action (play, pause, next, previous, toggle) and invoke the media_control tool with the action parameter."
                } else {
                    "Media reminder: Invoke media_control with the desired action parameter."
                }
            }
            else -> ""
        }
    }

    internal fun renderTaskState(state: TaskState): String {
        return when (state) {
            is ContactResolution -> {
                val sb = StringBuilder()
                if (state.selectedId == null) {
                    // CONTACT_DISAMBIGUATION state: LLM must choose a contact using select_contact.
                    sb.append("Current Task: Contact Disambiguation\n")
                    sb.append("Matching contacts:\n")
                    val nameCounts = state.candidates.groupingBy { it.name.trim().lowercase() }.eachCount()
                    for (c in state.candidates) {
                        val isDuplicate = (nameCounts[c.name.trim().lowercase()] ?: 0) > 1
                        val label = formatCandidateModelLabel(c, isDuplicate)
                        sb.append("- $label\n")
                    }
                    sb.append("The user is choosing a contact. Emit select_contact with the matching candidate_id.")
                } else if (!state.confirmed) {
                    val candidate = state.candidates.firstOrNull { it.id == state.selectedId }
                    val name = candidate?.name ?: state.selectedId
                    if (!state.isAsked) {
                        // CALL_CONFIRMATION state (before question is generated): LLM asks for verbal confirmation.
                        sb.append("Current Task: Pending Confirmation\n")
                        sb.append("Selected contact: $name\n")
                        sb.append("Ask the user for verbal confirmation before placing the call.")
                    } else {
                        // AWAITING_CONFIRMATION state: question was asked, awaiting user's affirmative/negative answer.
                        sb.append("Current Task: Awaiting Confirmation\n")
                        sb.append("Selected contact: $name\n")
                        val question = pendingVoiceConfirmation?.repeatBack ?: pendingAsk?.question ?: "Shall I call $name?"
                        sb.append("Confirmation question already asked: \"$question\"\n")
                        sb.append("The user's response is their answer. If affirmed, invoke call_contact. If declined, respond conversationally.")
                    }
                } else {
                    // CONFIRMED state: call_contact is available for execution.
                    val candidate = state.candidates.firstOrNull { it.id == state.selectedId }
                    val name = candidate?.name ?: state.selectedId
                    sb.append("Current Task: Confirmed Call\n")
                    sb.append("User has confirmed calling $name.")
                }
                sb.toString()
            }
        }
    }

    internal suspend fun buildSystemPrompt(
        availableTools: List<Tool>,
        disabledTools: List<Pair<Tool, String>> = emptyList()
    ): String {
        val core = buildCoreSystemPrompt()
        val perTurn = buildPerTurnPrompt(availableTools, disabledTools)
        return if (perTurn.isBlank()) core else "$core\n\n$perTurn"
    }

    private fun formatErrorResponse(toolName: String, result: ToolResult): String {
        val err = result.error ?: "Operation failed."
        return when (result.errorCode) {
            ToolErrorCode.PERMISSION_DENIED.name ->
                "I don't have permission to do that."
            ToolErrorCode.VALIDATION_ERROR.name ->
                "Please provide more details."
            ToolErrorCode.NOT_FOUND.name ->
                "I couldn't find the requested item."
            else -> err
        }
    }

    internal fun formatFastPathResponse(toolName: String, result: ToolResult): String? {
        if (!result.success) return null
        val data = result.data ?: return null

        return when (toolName) {
            "get_current_time" -> data["formatted"] ?: data["time"]?.let { "The time is $it" }
            "get_battery_status" -> data["percentage"]?.let { "Battery is at $it" }
            "open_app" -> data["app_name"]?.let { "Opening $it" }
            "set_timer" -> data["seconds"]?.let { "Timer set for $it seconds" }
            "set_alarm" -> "Alarm set for ${data["hour"]}:${data["minute"]}"
            "media_control" -> "Media command sent"
            "call_contact" -> "Calling ${data["calling"] ?: data["name"] ?: data["phone_number"]}"
            "dial_number" -> "Opening dialer for ${data["dialed"]}"
            else -> null
        }
    }

    fun clear() {
        conversationContext.clear()
        needsSchemaInjection = true
        taskState = null
        activeCapability = null
        contactCandidateRegistry.clear()
        pendingAsk = null
        onPendingAskUpdated?.invoke(null)
        pendingVoiceConfirmation = null
        onPendingVoiceConfirmationUpdated?.invoke(null)
    }

    /**
     * Cancels any active LLM generation, TTS playback, and — critically — resolves any
     * pending confirmation gate as denied, preventing zombie-gate leaks.
     */
    fun cancel() {
        // Task 2.3: deny any pending gate before tearing down
        pendingConfirmation?.deferred?.complete(false)
        pendingConfirmation = null
        llmEngine.cancel()
        ttsEngine?.stop()
    }

    private fun isSimpleGreeting(input: String): Boolean {
        return input.trim().lowercase() in setOf("hi", "hello", "hey", "good morning", "good afternoon", "good evening")
    }

    companion object {
        /** How long to wait (ms) for a user verdict before auto-cancelling the gate. */
        const val CONFIRMATION_TIMEOUT_MS = 20_000L

        const val RECOVERY_RESPONSE_TEXT = "Sorry, I didn't catch that — could you say it again?"

        private val TOOL_JSON_REGEX = """\{\s*"tool"\s*:""".toRegex()
        private val STANDALONE_TOOL_NAME_REGEX = """\b(ask_user|call_contact|lookup_contact|dial_number|select_contact|get_current_time|get_battery_status|open_app|set_timer|set_alarm|media_control|toggle_flashlight|open_wifi_settings|open_bluetooth_settings|get_wifi_state|get_bluetooth_state|get_ram_usage|remember_fact|search_chat_history)\b""".toRegex(RegexOption.IGNORE_CASE)

        internal fun containsProtocolArtifacts(text: String): Boolean {
            if (text.contains("<|") || text.contains("<|tool_call")) return true
            if (text.contains("```")) return true
            if (TOOL_JSON_REGEX.containsMatchIn(text)) return true
            if (STANDALONE_TOOL_NAME_REGEX.containsMatchIn(text)) return true
            return false
        }

        internal fun maskNumbersInString(text: String): String {
            if (text.isBlank()) return text
            var masked = text.replace("""("number"|"phone_number"|"phone")\s*:\s*"([^"]+)"""".toRegex()) { matchResult ->
                val field = matchResult.groupValues[1]
                val rawNum = matchResult.groupValues[2]
                val digits = rawNum.filter { it.isDigit() }
                val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                """$field: "ending in $suffix""""
            }
            masked = masked.replace("""(number|phone_number|phone)=([^,\}\]]+)""".toRegex()) { matchResult ->
                val field = matchResult.groupValues[1]
                val rawNum = matchResult.groupValues[2].trim()
                val digits = rawNum.filter { it.isDigit() }
                val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                """$field=ending in $suffix"""
            }
            val digits = text.filter { it.isDigit() }
            if (digits.length >= 5 && !masked.contains("ending in")) {
                val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                return "ending in $suffix"
            }
            return masked
        }

        internal fun maskToolResultForModel(result: ToolResult): ToolResult {
            val data = result.data ?: return result
            if (!result.success) return result
            val maskedData = data.mapValues { (_, value) ->
                maskNumbersInString(value)
            }
            return result.copy(data = maskedData)
        }

        internal fun formatCandidateModelLabel(candidate: ContactCandidate, isDuplicateName: Boolean): String {
            val idPrefix = if (candidate.id.isNotBlank()) "[${candidate.id}] " else ""
            return if (isDuplicateName) {
                val digits = candidate.phoneNumber.filter { it.isDigit() }
                val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                if (suffix.isNotEmpty()) {
                    "$idPrefix${candidate.name} — ending in $suffix"
                } else {
                    "$idPrefix${candidate.name}"
                }
            } else {
                "$idPrefix${candidate.name}"
            }
        }

        internal fun formatCandidateSpeechLabel(candidate: ContactCandidate, isDuplicateName: Boolean): String {
            return if (isDuplicateName) {
                val digits = candidate.phoneNumber.filter { it.isDigit() }
                val suffix = if (digits.length >= 2) digits.takeLast(2) else digits
                if (suffix.isNotEmpty()) {
                    "${candidate.name} — number ending in $suffix"
                } else {
                    candidate.name
                }
            } else {
                candidate.name
            }
        }

        internal fun formatCandidateLabel(candidate: ContactCandidate, isDuplicateName: Boolean): String {
            return formatCandidateSpeechLabel(candidate, isDuplicateName)
        }

        internal fun buildDuplicateDisambiguationCoachMessage(candidates: List<ContactCandidate>, query: String? = null): String {
            val nameCounts = candidates.groupingBy { it.name.trim().lowercase() }.eachCount()
            val formattedList = candidates.joinToString("; ") { c ->
                val isDuplicate = (nameCounts[c.name.trim().lowercase()] ?: 0) > 1
                formatCandidateModelLabel(c, isDuplicate)
            }
            return "Multiple contacts match. Options: $formattedList. Present the options to the user via ask_user without candidate IDs (e.g. using names and distinguishers like 'the number ending in 95'). When confirmed, invoke call_contact with the resolved candidate_id."
        }
    }
}
