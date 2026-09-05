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
    val conversationId: String? = null
) {

    var activeCapability: String? = null
        internal set

    var taskState: TaskState? = null
        internal set

    var needsSchemaInjection: Boolean = true
        internal set

    private val lastContactCandidates = mutableMapOf<String, ContactCandidate>()

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
                    advancingTool = taskState?.advancingTool
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
                    }
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

                        // ── App-side validation & resolution for call_contact ─────────────
                        val resolvedArguments = parsed.arguments.toMutableMap()
                        if (parsed.tool == "call_contact") {
                            val candId = parsed.arguments["candidate_id"]?.toString()?.trim()
                            val nameArg = parsed.arguments["name"]?.toString()?.trim()
                            val phoneArg = parsed.arguments["phone_number"]?.toString()?.trim()
                            val hasValidPhone = isValidPhoneNumber(phoneArg)

                            // Candidate resolution order: 1) taskState candidates, 2) lastContactCandidates registry, 3) name/search re-query
                            var cand: ContactCandidate? = null
                            val resolution = taskState as? ContactResolution
                            if (resolution != null) {
                                cand = if (!candId.isNullOrBlank()) {
                                    resolution.candidates.firstOrNull { it.id.equals(candId, ignoreCase = true) }
                                } else if (resolution.selectedId != null) {
                                    resolution.candidates.firstOrNull { it.id == resolution.selectedId }
                                } else if (resolution.candidates.size == 1) {
                                    resolution.candidates[0]
                                } else null
                            }

                            if (cand == null && !candId.isNullOrBlank()) {
                                cand = lastContactCandidates[candId.lowercase()]
                            }

                            if (cand != null) {
                                resolvedArguments["phone_number"] = cand.phoneNumber
                                resolvedArguments["name"] = cand.name
                                resolvedArguments["candidate_id"] = cand.id
                                taskState = (resolution ?: ContactResolution(candidates = listOf(cand))).copy(selectedId = cand.id)
                                lastContactCandidates[cand.id.lowercase()] = cand
                                lastContactCandidates[cand.name.lowercase()] = cand
                            } else if (!hasValidPhone) {
                                val searchQuery = nameArg?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
                                    ?: candId?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }

                                if (!searchQuery.isNullOrBlank()) {
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
                                            lastContactCandidates[c.id.lowercase()] = c
                                            lastContactCandidates[c.name.lowercase()] = c
                                        }

                                        if (candidates.size == 1) {
                                            val resolved = candidates[0]
                                            resolvedArguments["phone_number"] = resolved.phoneNumber
                                            resolvedArguments["name"] = resolved.name
                                            resolvedArguments["candidate_id"] = resolved.id
                                            taskState = ContactResolution(candidates = candidates, selectedId = resolved.id)
                                        } else if (candidates.size > 1) {
                                            taskState = ContactResolution(candidates = candidates)
                                            val candidateList = candidates.joinToString(", ") { "${it.id}: ${it.name}" }
                                            val coachMessage = "Found multiple contacts for '$searchQuery': $candidateList. Ask the user which contact to call."
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
                                }
                            }
                        }
                        // ─────────────────────────────────────────────────────────────────

                        // ── Confirmation gate (D1/D2) ─────────────────────────────────────
                        val voiceSources = setOf("VOICE", "DIRECT_AUDIO", "VOICE_FOLLOW_UP")
                        val isVoiceSource = source in voiceSources

                        if (toolRegistry.requiresConfirmation(parsed.tool)) {
                            if (isVoiceSource) {
                                val hasAskedConfirmation = source == "VOICE_FOLLOW_UP" ||
                                    conversationContext.getTurns().any {
                                        (it is ConversationTurn.Assistant && it.text.trim().endsWith("?")) ||
                                        (it is ConversationTurn.ToolExecutionResult && it.result.error?.contains("Action requires verbal confirmation") == true)
                                    }

                                if (!hasAskedConfirmation) {
                                    val contactName = resolvedArguments["name"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: resolvedArguments["calling"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                                        ?: "the contact"
                                    val coachMessage = "Action requires verbal confirmation. Do not execute yet. First ask the user for confirmation by stating the contact name in a question. Only execute this tool after the user verbally confirms."
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
                                TurnLogger.logToolExecution(turnId, parsed.tool, true, result.data.toString())
                                send(ConversationEvent.ToolExecuted(parsed.tool, result))

                                recordTurn(
                                    ConversationTurn.ToolExecutionResult(
                                        tool = parsed.tool,
                                        result = result
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

                                    taskState = ContactResolution(
                                        candidates = candidates,
                                        selectedId = if (candidates.size == 1) candidates[0].id else null,
                                        confirmed = false
                                    )

                                    val summary = candidates.joinToString(", ") { "${it.id}: ${it.name}" }
                                    currentTurnPrompt = "Tool result for lookup_contact: Found ${candidates.size} matching contacts: $summary"
                                    continue
                                }

                                if (parsed.tool == "call_contact") {
                                    taskState = (taskState as? ContactResolution)?.copy(confirmed = true)
                                    activeCapability = null
                                    taskState = null
                                }

                                val fastResponse = formatFastPathResponse(parsed.tool, result)
                                if (fastResponse != null) {
                                    finalResponseText = fastResponse
                                    recordTurn(ConversationTurn.Assistant(finalResponseText))
                                    break
                                }

                                // For next ReAct iteration, send ONLY the tool execution result message
                                currentTurnPrompt = "Tool result for ${parsed.tool}: ${result.data}"
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
                        finalResponseText = parsed.text
                        recordTurn(ConversationTurn.Assistant(finalResponseText))
                        val isUnresolvedContact = (taskState as? ContactResolution)?.let { !it.resolved && it.candidates.isNotEmpty() } ?: false
                        if (!isUnresolvedContact && (taskState == null || taskState?.resolved == true)) {
                            activeCapability = null
                            taskState = null
                        }
                        break
                    }

                    is ParsedLlmResponse.Malformed -> {
                        TurnLogger.logError(turnId, "Malformed LLM response: ${parsed.raw} (${parsed.error})")
                        if (!correctiveRetryUsed) {
                            correctiveRetryUsed = true
                            continue
                        }
                        finalResponseText = if (parsed.raw.isNotBlank() && !parsed.raw.contains("{") && !parsed.raw.contains("\"tool\"")) {
                            parsed.raw.trim()
                        } else {
                            "I couldn't determine that request. Please try again."
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

        if (finalResponseText.isEmpty()) {
            finalResponseText = "Task completed."
        }

        TurnLogger.logFinalResponse(turnId, finalResponseText)

        if (enableTts && ttsEngine != null) {
            send(ConversationEvent.Speaking(finalResponseText))
            ttsEngine.speak(finalResponseText)
        }

        send(ConversationEvent.Completed(finalResponseText, lastToolResult))
    }.flowOn(ioDispatcher)

    internal suspend fun buildCoreSystemPrompt(isCompact: Boolean = false): String {
        if (isCompact) {
            val sb = StringBuilder()
            sb.append("You are Loki, an on-device assistant. When the user asks to call or message someone, immediately call lookup_contact with their name — do not ask for contact information. Only ask which contact when a lookup returns multiple matches.\n")
            sb.append("Always output JSON: {\"tool\": \"tool_name\", \"arguments\": {...}} or {\"response\": \"conversational answer\"}.")
            return sb.toString()
        }

        val sb = StringBuilder()
        sb.append("You are Loki, a private offline Android assistant running on the user's device. You operate entirely on-device with privacy and safety as highest priority.\n\n")
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
                    "Calling guidance: When looking up contacts to call: if multiple contacts match, list them by NAME only. Never speak, display, or invent phone numbers — phone numbers are unavailable to you. Ask the user which contact to call using their name or candidate ID (e.g. using select_contact). If there is a unique match or once selected, ask for verbal confirmation before placing the call."
                } else {
                    "Calling reminder: Refer to candidates by name or candidate ID; phone numbers are unavailable to you. Do not execute call_contact until the user confirms."
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
                sb.append("Current Task: Contact Resolution\n")
                if (state.selectedId == null) {
                    sb.append("Matching candidates:\n")
                    for (c in state.candidates) {
                        sb.append("- [${c.id}] ${c.name}\n")
                    }
                    sb.append("Phone numbers are unavailable to you. Ask the user which contact they want to call.")
                } else {
                    val candidate = state.candidates.firstOrNull { it.id == state.selectedId }
                    val name = candidate?.name ?: state.selectedId
                    sb.append("Selected candidate: [${state.selectedId}] $name\n")
                    if (!state.confirmed) {
                        sb.append("Ask the user for verbal confirmation to call $name before placing the call.")
                    } else {
                        sb.append("User has confirmed calling $name.")
                    }
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
        lastContactCandidates.clear()
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
    }
}
