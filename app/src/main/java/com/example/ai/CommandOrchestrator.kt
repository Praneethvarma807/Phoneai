package com.example.ai

import com.example.automation.AutomationEngine
import com.example.data.local.CommandHistoryDao
import com.example.data.local.CommandHistoryEntity
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.ActionCategory
import com.example.domain.model.ActionPlan
import com.example.domain.model.AppLanguage
import com.example.domain.model.ConversationContextSnapshot
import com.example.domain.model.ConversationTurn
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.OrbState
import com.example.domain.model.PendingConfirmation
import com.example.domain.model.PipelineStage
import com.example.domain.model.ToolCallRequest
import com.example.domain.model.ToolResult
import com.example.memory.PersonalMemoryRepository
import com.example.network.ConnectivityMonitor
import com.example.tools.ToolRegistry
import com.example.voice.VoiceConversationControl
import com.example.voice.VoiceOutputManager
import com.example.voice.WakeWordMatcher
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ExecutionUiState(
    val isExecuting: Boolean = false,
    val orbState: OrbState = OrbState.IDLE,
    val pipelineStage: PipelineStage = PipelineStage.IDLE,
    val currentCommand: String = "",
    val detectedLanguageBadge: String = "EN",
    val activeConversationLanguage: AppLanguage = AppLanguage.ENGLISH,
    val activeToolName: String = "",
    val activeCategory: ActionCategory = ActionCategory.SYSTEM,
    val currentPlan: ActionPlan? = null,
    val stepResults: List<ToolResult> = emptyList(),
    val finalReply: String = "",
    val finalStatus: ExecutionStatus? = null,
    val pendingConfirmation: PendingConfirmation? = null
)

class CommandOrchestrator(
    private val localLlmEngine: LocalLlmEngine,
    private val deterministicEngine: DeterministicCommandEngine,
    private val toolRegistry: ToolRegistry,
    private val historyDao: CommandHistoryDao,
    private val preferences: CommanderPreferences,
    private val voiceOutput: VoiceOutputManager,
    private val personalMemoryRepository: PersonalMemoryRepository? = null,
    private val connectivityMonitor: ConnectivityMonitor? = null,
    private val automationEngine: AutomationEngine? = null
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val executionGuard = AtomicBoolean(false)
    private val conversationHistory = ArrayDeque<ConversationTurn>(8)

    @Volatile
    private var contextSnapshot = ConversationContextSnapshot()

    @Volatile
    private var lastSucceededCommandNorm: String = ""

    @Volatile
    private var lastSucceededAtMs: Long = 0L

    private val _state = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = _state.asStateFlow()

    suspend fun processCommand(
        rawCommand: String,
        inputLanguage: String = "AUTO",
        allowDuplicate: Boolean = false,
        onComplete: (() -> Unit)? = null
    ) {
        val trimmed = rawCommand.trim()
        if (trimmed.isEmpty()) {
            onComplete?.invoke()
            return
        }
        val stripped = WakeWordMatcher.stripWakeWordPrefix(trimmed)
        val command = if (stripped.isNotBlank()) stripped else trimmed

        // Prevent duplicate concurrent requests while an execution is active
        if (!executionGuard.compareAndSet(false, true)) {
            onComplete?.invoke()
            return
        }

        try {
            val startedAt = System.currentTimeMillis()
            val normalizedCmd = normalizeCommandKey(command)
            if (!allowDuplicate &&
                normalizedCmd.isNotBlank() &&
                normalizedCmd == lastSucceededCommandNorm &&
                (startedAt - lastSucceededAtMs) < DUPLICATE_COMMAND_COOLDOWN_MS
            ) {
                onComplete?.invoke()
                return
            }

            val settings = preferences.settings.first()
            val previousActiveLang = _state.value.activeConversationLanguage
            val detected = LanguageDetector.detect(
                text = command,
                selectedPreference = settings.speechLanguage,
                conversationActiveLanguage = previousActiveLang
            )
            val responseLang = detected.responseLanguage

            if (VoiceConversationControl.isTerminationPhrase(command)) {
                voiceOutput.stop()
                _state.value = ExecutionUiState(
                    isExecuting = false,
                    orbState = OrbState.IDLE,
                    pipelineStage = PipelineStage.COMPLETED,
                    currentCommand = command,
                    detectedLanguageBadge = detected.badgeLabel,
                    activeConversationLanguage = responseLang,
                    finalReply = "Voice session stopped.",
                    finalStatus = ExecutionStatus.CANCELLED
                )
                onComplete?.invoke()
                return
            }

            recordTurn("user", command, detected.primaryLanguage)

            _state.value = ExecutionUiState(
                isExecuting = true,
                orbState = OrbState.PROCESSING,
                pipelineStage = PipelineStage.UNDERSTANDING,
                currentCommand = command,
                detectedLanguageBadge = detected.badgeLabel,
                activeConversationLanguage = responseLang
            )

            val isOffline = settings.offlineStrictMode ||
                (connectivityMonitor?.refresh()?.isOnline == false)

            // Retrieve relevant personal memories (non-sensitive only) for AI context
            val relevantMemories = if (settings.memoryInjectionEnabled) {
                runCatching {
                    personalMemoryRepository?.recallRelevant(command, limit = 4)
                        ?.map { "${it.summaryLabel}: ${it.valueText}" }
                        .orEmpty()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            // Stage 1: Understanding & Planning (Local LLM first if loaded & enabled, else Routine Trigger / Deterministic)
            val llmState = localLlmEngine.runtimeState.value
            var plan: ActionPlan? = null

            if (llmState.isReady && !settings.commandOnlyMode && !llmState.commandOnlyMode) {
                _state.update { it.copy(orbState = OrbState.THINKING, pipelineStage = PipelineStage.PLANNING) }
                val llmPlan = localLlmEngine.planCommand(
                    userInput = command,
                    registeredTools = toolRegistry.registeredNames(),
                    languageInfo = detected,
                    recentTurns = conversationHistory.toList(),
                    memoryContext = relevantMemories,
                    isOffline = isOffline
                )
                if (llmPlan.isSuccess) {
                    plan = llmPlan.getOrNull()
                }
            }

            if (plan == null) {
                val matchedRoutine = automationEngine?.matchVoiceTrigger(command)
                if (matchedRoutine != null) {
                    plan = ActionPlan(
                        intent = "run_routine",
                        interpretation = "Execute Smart Automation routine \"${matchedRoutine.name}\".",
                        steps = listOf("Run routine steps sequentially", "Record routine log"),
                        toolCalls = listOf(ToolCallRequest("run_routine", mapOf("routine_name" to matchedRoutine.name))),
                        assistantReply = "Running ${matchedRoutine.name}.",
                        engineUsed = "AUTOMATION_ENGINE",
                        detectedLanguage = detected.badgeLabel,
                        responseLanguage = responseLang.shortBadge
                    )
                }
            }

            if (plan == null) {
                _state.update { it.copy(pipelineStage = PipelineStage.PLANNING) }
                plan = deterministicEngine.parse(
                    rawInput = command,
                    languageInfo = detected,
                    recentTurns = conversationHistory.toList(),
                    contextSnapshot = contextSnapshot
                )
            }

            if (plan == null) {
                val message = if (detected.primaryLanguage == AppLanguage.UNSUPPORTED) {
                    LanguageDetector.unsupportedMessage(AppLanguage.UNSUPPORTED, command)
                } else {
                    LanguageDetector.unsupportedMessage(responseLang, command)
                }
                finishWithStatus(
                    command = command,
                    inputLanguage = detected.badgeLabel,
                    responseLanguage = responseLang,
                    plan = ActionPlan(
                        intent = "unsupported",
                        interpretation = "Unrecognized command in current mode (${detected.badgeLabel})",
                        engineUsed = "DETERMINISTIC",
                        assistantReply = message,
                        detectedLanguage = detected.badgeLabel,
                        responseLanguage = responseLang.shortBadge
                    ),
                    results = emptyList(),
                    status = ExecutionStatus.UNSUPPORTED,
                    summary = message,
                    startedAt = startedAt,
                    speakResponse = settings.voiceResponseEnabled,
                    onComplete = onComplete
                )
                return
            }

            // Offline resilience guard for online-requiring browser/web searches
            val requiresInternetTool = plan.toolCalls.any { it.tool == "web_search" || it.tool == "open_url" }
            if (requiresInternetTool && isOffline) {
                val offlineMsg = if (settings.offlineStrictMode) {
                    "Offline Strict Mode is active, so web browsing/search is blocked. All local device controls, notes, reminders, OCR, and routines remain active."
                } else {
                    "Device is currently offline, so web pages cannot load. On-device commands, notes, reminders, memory, OCR, and routines work normally."
                }
                finishWithStatus(
                    command = command,
                    inputLanguage = detected.badgeLabel,
                    responseLanguage = responseLang,
                    plan = plan,
                    results = emptyList(),
                    status = ExecutionStatus.FAILED,
                    summary = offlineMsg,
                    startedAt = startedAt,
                    speakResponse = settings.voiceResponseEnabled,
                    onComplete = onComplete
                )
                return
            }

            // Confidence validation before device action execution
            if (plan.toolCalls.isNotEmpty() && plan.confidence < MIN_EXECUTION_CONFIDENCE) {
                val clarifyMsg = LanguageDetector.unsupportedMessage(responseLang, command)
                finishWithStatus(
                    command = command,
                    inputLanguage = detected.badgeLabel,
                    responseLanguage = responseLang,
                    plan = plan.copy(toolCalls = emptyList(), assistantReply = clarifyMsg, silentExecution = false),
                    results = emptyList(),
                    status = ExecutionStatus.UNSUPPORTED,
                    summary = clarifyMsg,
                    startedAt = startedAt,
                    speakResponse = settings.voiceResponseEnabled,
                    onComplete = onComplete
                )
                return
            }

            _state.update {
                it.copy(
                    currentPlan = plan,
                    pipelineStage = PipelineStage.SELECTING_TOOLS
                )
            }
            delay(if (plan.silentExecution) 20L else 90L)

            // Check if plan requires explicit user confirmation before executing sensitive steps
            val anyToolRequiresConfirmation = plan.toolCalls.any { call ->
                toolRegistry.get(call.tool)?.schema?.requiresConfirmation == true
            }
            if (plan.requiresConfirmation || anyToolRequiresConfirmation) {
                val preview = plan.confirmationPrompt
                    ?: "Confirm running \"$command\"?"
                _state.update {
                    it.copy(
                        isExecuting = false,
                        orbState = OrbState.IDLE,
                        pipelineStage = PipelineStage.PLANNING,
                        finalReply = preview,
                        finalStatus = ExecutionStatus.CONFIRMATION_REQUIRED,
                        pendingConfirmation = PendingConfirmation(
                            commandText = command,
                            plan = plan,
                            previewTitle = "Confirmation Required",
                            previewBody = preview,
                            stepIndex = 0,
                            responseLanguage = responseLang
                        )
                    )
                }
                if (settings.voiceResponseEnabled) {
                    voiceOutput.setResponseLanguage(responseLang)
                    voiceOutput.speak(preview) {
                        onComplete?.invoke()
                    }
                } else {
                    onComplete?.invoke()
                }
                return
            }

            executePlan(command, detected.badgeLabel, responseLang, plan, startedAt, onComplete)
        } finally {
            executionGuard.set(false)
            _state.update { it.copy(isExecuting = false) }
        }
    }

    suspend fun confirmPendingAction(onComplete: (() -> Unit)? = null) {
        val pending = _state.value.pendingConfirmation
        if (pending == null) {
            onComplete?.invoke()
            return
        }
        if (!executionGuard.compareAndSet(false, true)) {
            onComplete?.invoke()
            return
        }
        try {
            _state.update { it.copy(pendingConfirmation = null, isExecuting = true) }
            executePlan(
                command = pending.commandText,
                inputLanguage = "${pending.plan.detectedLanguage} (CONFIRMED)",
                responseLanguage = pending.responseLanguage,
                plan = pending.plan,
                startedAt = System.currentTimeMillis(),
                onComplete = onComplete
            )
        } finally {
            executionGuard.set(false)
            _state.update { it.copy(isExecuting = false) }
        }
    }

    suspend fun cancelPendingAction(onComplete: (() -> Unit)? = null) {
        val pending = _state.value.pendingConfirmation
        if (pending == null) {
            onComplete?.invoke()
            return
        }
        _state.update { it.copy(pendingConfirmation = null) }
        val settings = preferences.settings.first()
        finishWithStatus(
            command = pending.commandText,
            inputLanguage = "${pending.plan.detectedLanguage} (CANCELLED)",
            responseLanguage = pending.responseLanguage,
            plan = pending.plan,
            results = emptyList(),
            status = ExecutionStatus.CANCELLED,
            summary = "Action cancelled by user before execution.",
            startedAt = System.currentTimeMillis(),
            speakResponse = settings.voiceResponseEnabled,
            onComplete = onComplete
        )
    }

    private suspend fun executePlan(
        command: String,
        inputLanguage: String,
        responseLanguage: AppLanguage,
        plan: ActionPlan,
        startedAt: Long,
        onComplete: (() -> Unit)?
    ) {
        val settings = preferences.settings.first()
        val results = mutableListOf<ToolResult>()
        var overallStatus = ExecutionStatus.SUCCESS

        for (call in plan.toolCalls) {
            val toolCategory = toolRegistry.get(call.tool)?.category ?: ActionCategory.SYSTEM
            _state.update {
                it.copy(
                    orbState = OrbState.EXECUTING,
                    pipelineStage = PipelineStage.EXECUTING,
                    activeToolName = call.tool,
                    activeCategory = toolCategory
                )
            }

            val result = toolRegistry.executeTool(call.tool, call.arguments)
            results.add(result)
            _state.update { it.copy(stepResults = results.toList()) }

            if (result.status == ExecutionStatus.SUCCESS) {
                updateContextFromToolCall(plan.intent, call, result)
            } else {
                overallStatus = result.status
                break
            }
        }

        _state.update { it.copy(pipelineStage = PipelineStage.VERIFYING) }
        delay(if (plan.silentExecution) 15L else 90L)

        val distinctMessages = results.map { it.message.trim() }.filter { it.isNotBlank() }.distinct()
        val summary = buildString {
            if (distinctMessages.isEmpty()) {
                append(plan.assistantReply.ifBlank { "Done." })
            } else {
                val joinedResults = distinctMessages.joinToString(" ")
                val prefix = plan.assistantReply.trim()
                if (responseLanguage != AppLanguage.ENGLISH &&
                    prefix.isNotBlank() &&
                    overallStatus == ExecutionStatus.SUCCESS &&
                    !joinedResults.contains(prefix, ignoreCase = true)
                ) {
                    append(prefix).append(" ")
                }
                append(joinedResults)
            }
        }

        finishWithStatus(
            command = command,
            inputLanguage = inputLanguage,
            responseLanguage = responseLanguage,
            plan = plan,
            results = results,
            status = overallStatus,
            summary = summary,
            startedAt = startedAt,
            speakResponse = settings.voiceResponseEnabled,
            onComplete = onComplete
        )
    }

    private fun updateContextFromToolCall(intent: String, call: ToolCallRequest, result: ToolResult) {
        contextSnapshot = contextSnapshot.copy(
            lastIntent = intent,
            lastToolName = call.tool,
            lastUrl = result.data["url"] ?: call.arguments["url"] ?: contextSnapshot.lastUrl,
            lastQuery = call.arguments["query"] ?: contextSnapshot.lastQuery,
            lastPackage = result.data["package"] ?: contextSnapshot.lastPackage,
            lastAppName = call.arguments["app_name"] ?: contextSnapshot.lastAppName,
            lastRecipient = call.arguments["recipient"] ?: call.arguments["to"] ?: contextSnapshot.lastRecipient,
            lastExtractedText = result.data["extracted_text"] ?: contextSnapshot.lastExtractedText
        )
    }

    private suspend fun finishWithStatus(
        command: String,
        inputLanguage: String,
        responseLanguage: AppLanguage,
        plan: ActionPlan,
        results: List<ToolResult>,
        status: ExecutionStatus,
        summary: String,
        startedAt: Long,
        speakResponse: Boolean,
        onComplete: (() -> Unit)?
    ) {
        val duration = System.currentTimeMillis() - startedAt
        val failureNote = results
            .filter { it.status != ExecutionStatus.SUCCESS }
            .joinToString("; ") { "${it.toolName}: ${it.message}" }
            .takeIf { it.isNotBlank() }

        if (status == ExecutionStatus.SUCCESS) {
            lastSucceededCommandNorm = normalizeCommandKey(command)
            lastSucceededAtMs = System.currentTimeMillis()
        }

        contextSnapshot = contextSnapshot.copy(lastSummary = summary)
        recordTurn("assistant", summary, responseLanguage)

        historyDao.insert(
            CommandHistoryEntity(
                commandText = command,
                transcription = command,
                inputLanguage = inputLanguage,
                engineUsed = plan.engineUsed,
                aiInterpretation = plan.interpretation,
                selectedToolsJson = json.encodeToString(plan.toolCalls.map { it.tool }),
                executionPlanJson = json.encodeToString(plan),
                resultStatus = status.name,
                resultSummary = summary,
                failureDetails = failureNote,
                durationMs = duration
            )
        )

        val silentSuccess = plan.silentExecution && status == ExecutionStatus.SUCCESS
        if (silentSuccess) {
            _state.update {
                it.copy(
                    isExecuting = false,
                    orbState = OrbState.IDLE,
                    pipelineStage = PipelineStage.IDLE,
                    finalReply = "",
                    finalStatus = ExecutionStatus.SUCCESS
                )
            }
            onComplete?.invoke()
            return
        }

        val finalOrb = if (status == ExecutionStatus.SUCCESS) OrbState.SUCCESS else OrbState.ERROR
        val finalStage = if (status == ExecutionStatus.SUCCESS) PipelineStage.COMPLETED else PipelineStage.FAILED

        _state.update {
            it.copy(
                isExecuting = false,
                orbState = if (speakResponse && summary.isNotBlank()) OrbState.SPEAKING else finalOrb,
                pipelineStage = finalStage,
                finalReply = summary,
                finalStatus = status
            )
        }

        if (speakResponse && summary.isNotBlank()) {
            voiceOutput.setResponseLanguage(responseLanguage)
            voiceOutput.speak(summary) {
                _state.update { it.copy(orbState = finalOrb) }
                onComplete?.invoke()
            }
        } else {
            onComplete?.invoke()
        }
    }

    private fun recordTurn(role: String, text: String, language: AppLanguage) {
        synchronized(conversationHistory) {
            if (conversationHistory.size >= 6) {
                conversationHistory.removeFirst()
            }
            conversationHistory.addLast(ConversationTurn(role, text, language))
        }
    }

    fun updateOrbState(orbState: OrbState) {
        _state.update { it.copy(orbState = orbState) }
    }

    private fun normalizeCommandKey(raw: String): String = raw.trim().lowercase()
        .replace(Regex("[^a-z0-9\\u0900-\\u0D7F\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        const val DUPLICATE_COMMAND_COOLDOWN_MS = 2_500L
        const val MIN_EXECUTION_CONFIDENCE = 0.55f
    }
}
