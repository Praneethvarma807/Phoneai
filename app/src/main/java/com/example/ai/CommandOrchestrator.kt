package com.example.ai

import com.example.data.local.CommandHistoryDao
import com.example.data.local.CommandHistoryEntity
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.ActionCategory
import com.example.domain.model.ActionPlan
import com.example.domain.model.AppLanguage
import com.example.domain.model.ConversationTurn
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.OrbState
import com.example.domain.model.PendingConfirmation
import com.example.domain.model.PipelineStage
import com.example.domain.model.ToolResult
import com.example.tools.ToolRegistry
import com.example.voice.VoiceOutputManager
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
    private val voiceOutput: VoiceOutputManager
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val executionGuard = AtomicBoolean(false)
    private val conversationHistory = ArrayDeque<ConversationTurn>(8)

    private val _state = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = _state.asStateFlow()

    suspend fun processCommand(
        rawCommand: String,
        inputLanguage: String = "AUTO",
        onComplete: (() -> Unit)? = null
    ) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        // Prevent duplicate concurrent requests while an execution is active
        if (!executionGuard.compareAndSet(false, true)) {
            return
        }

        try {
            val startedAt = System.currentTimeMillis()
            val settings = preferences.settings.first()
            val previousActiveLang = _state.value.activeConversationLanguage
            val detected = LanguageDetector.detect(
                text = command,
                selectedPreference = settings.speechLanguage,
                conversationActiveLanguage = previousActiveLang
            )
            val responseLang = detected.responseLanguage

            recordTurn("user", command, detected.primaryLanguage)

            _state.value = ExecutionUiState(
                isExecuting = true,
                orbState = OrbState.PROCESSING,
                pipelineStage = PipelineStage.UNDERSTANDING,
                currentCommand = command,
                detectedLanguageBadge = detected.badgeLabel,
                activeConversationLanguage = responseLang
            )

            // Stage 1: Understanding & Planning (Local LLM first if loaded & enabled, else Deterministic Engine)
            val llmState = localLlmEngine.runtimeState.value
            var plan: ActionPlan? = null
            var llmFailureReason: String? = null

            if (llmState.isReady && !settings.commandOnlyMode && !llmState.commandOnlyMode) {
                _state.update { it.copy(orbState = OrbState.THINKING, pipelineStage = PipelineStage.PLANNING) }
                val llmPlan = localLlmEngine.planCommand(
                    userInput = command,
                    registeredTools = toolRegistry.registeredNames(),
                    languageInfo = detected,
                    recentTurns = conversationHistory.toList()
                )
                if (llmPlan.isSuccess) {
                    plan = llmPlan.getOrNull()
                } else {
                    llmFailureReason = llmPlan.exceptionOrNull()?.message
                }
            }

            if (plan == null) {
                _state.update { it.copy(pipelineStage = PipelineStage.PLANNING) }
                plan = deterministicEngine.parse(command, detected)
            }

            if (plan == null) {
                val message = buildString {
                    if (detected.primaryLanguage == AppLanguage.UNSUPPORTED) {
                        append(LanguageDetector.unsupportedMessage(AppLanguage.UNSUPPORTED, command))
                    } else if (llmState.isReady && !settings.commandOnlyMode) {
                        append(LanguageDetector.unsupportedMessage(responseLang, command))
                        if (!llmFailureReason.isNullOrBlank()) {
                            append(" ($llmFailureReason)")
                        }
                    } else {
                        append(LanguageDetector.unsupportedMessage(responseLang, command))
                    }
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

            _state.update {
                it.copy(
                    currentPlan = plan,
                    pipelineStage = PipelineStage.SELECTING_TOOLS
                )
            }
            delay(90L)

            // Check if plan requires explicit user confirmation before executing sensitive steps
            val anyToolRequiresConfirmation = plan.toolCalls.any { call ->
                toolRegistry.get(call.tool)?.schema?.requiresConfirmation == true
            }
            if (plan.requiresConfirmation || anyToolRequiresConfirmation) {
                val preview = plan.confirmationPrompt
                    ?: "Confirm executing ${plan.toolCalls.joinToString { it.tool }} for \"$command\"?"
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
                    voiceOutput.speak(preview)
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
        val pending = _state.value.pendingConfirmation ?: return
        if (!executionGuard.compareAndSet(false, true)) return
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
        val pending = _state.value.pendingConfirmation ?: return
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

            if (result.status != ExecutionStatus.SUCCESS) {
                overallStatus = result.status
                if (results.size > 1 && results.first().status == ExecutionStatus.SUCCESS && result.requiresAccessibility) {
                    overallStatus = ExecutionStatus.SUCCESS
                } else {
                    break
                }
            }
        }

        _state.update { it.copy(pipelineStage = PipelineStage.VERIFYING) }
        delay(90L)

        val summary = buildString {
            if (results.isEmpty()) {
                append(plan.assistantReply.ifBlank { "Done." })
            } else {
                if (responseLanguage != AppLanguage.ENGLISH && plan.assistantReply.isNotBlank() && overallStatus == ExecutionStatus.SUCCESS) {
                    append(plan.assistantReply).append(" ")
                }
                results.forEachIndexed { idx, res ->
                    if (idx > 0) append(" ")
                    append(res.message)
                }
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
}
