package com.example.ai

import com.example.data.local.CommandHistoryDao
import com.example.data.local.CommandHistoryEntity
import com.example.data.preferences.CommanderPreferences
import com.example.domain.model.ActionCategory
import com.example.domain.model.ActionPlan
import com.example.domain.model.ExecutionStatus
import com.example.domain.model.OrbState
import com.example.domain.model.PendingConfirmation
import com.example.domain.model.PipelineStage
import com.example.domain.model.ToolResult
import com.example.tools.ToolRegistry
import com.example.voice.VoiceOutputManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ExecutionUiState(
    val orbState: OrbState = OrbState.IDLE,
    val pipelineStage: PipelineStage = PipelineStage.IDLE,
    val currentCommand: String = "",
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

    private val _state = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = _state.asStateFlow()

    suspend fun processCommand(
        rawCommand: String,
        inputLanguage: String = "AUTO",
        onComplete: (() -> Unit)? = null
    ) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return

        val startedAt = System.currentTimeMillis()
        _state.value = ExecutionUiState(
            orbState = OrbState.PROCESSING,
            pipelineStage = PipelineStage.UNDERSTANDING,
            currentCommand = command
        )

        // Stage 1: Understanding & Planning (Local LLM first if loaded & enabled, else Deterministic Engine)
        val settings = preferences.settings.first()
        val llmState = localLlmEngine.runtimeState.value
        var plan: ActionPlan? = null

        if (llmState.isReady && !settings.commandOnlyMode && !llmState.commandOnlyMode) {
            _state.update { it.copy(orbState = OrbState.THINKING, pipelineStage = PipelineStage.PLANNING) }
            val llmPlan = localLlmEngine.planCommand(command, toolRegistry.registeredNames())
            if (llmPlan.isSuccess) {
                plan = llmPlan.getOrNull()
            }
        }

        if (plan == null) {
            _state.update { it.copy(pipelineStage = PipelineStage.PLANNING) }
            plan = deterministicEngine.parse(command)
        }

        if (plan == null) {
            val message = if (llmState.isReady && !settings.commandOnlyMode) {
                "Local AI could not map that instruction to a supported phone action. Please rephrase or specify an app, setting, or search."
            } else {
                "No local LLM model is loaded. Deterministic mode couldn't match \"$command\". Please simplify the command (e.g., \"Open Chrome\", \"YouTube lo Java tutorials search chey\") or load a local model in Models."
            }
            finishWithStatus(
                command = command,
                inputLanguage = inputLanguage,
                plan = ActionPlan(
                    intent = "unsupported",
                    interpretation = "Unrecognized command in current mode",
                    engineUsed = "DETERMINISTIC",
                    assistantReply = message
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
        delay(120L)

        // Check if plan requires explicit user confirmation before executing sensitive steps
        val anyToolRequiresConfirmation = plan.toolCalls.any { call ->
            toolRegistry.get(call.tool)?.schema?.requiresConfirmation == true
        }
        if (plan.requiresConfirmation || anyToolRequiresConfirmation) {
            val preview = plan.confirmationPrompt
                ?: "Confirm executing ${plan.toolCalls.joinToString { it.tool }} for \"$command\"?"
            _state.update {
                it.copy(
                    orbState = OrbState.IDLE,
                    pipelineStage = PipelineStage.PLANNING,
                    finalReply = preview,
                    finalStatus = ExecutionStatus.CONFIRMATION_REQUIRED,
                    pendingConfirmation = PendingConfirmation(
                        commandText = command,
                        plan = plan,
                        previewTitle = "Confirmation Required",
                        previewBody = preview,
                        stepIndex = 0
                    )
                )
            }
            if (settings.voiceResponseEnabled) {
                voiceOutput.speak(preview)
            }
            return
        }

        executePlan(command, inputLanguage, plan, startedAt, onComplete)
    }

    suspend fun confirmPendingAction(onComplete: (() -> Unit)? = null) {
        val pending = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        executePlan(
            command = pending.commandText,
            inputLanguage = "CONFIRMED",
            plan = pending.plan,
            startedAt = System.currentTimeMillis(),
            onComplete = onComplete
        )
    }

    suspend fun cancelPendingAction(onComplete: (() -> Unit)? = null) {
        val pending = _state.value.pendingConfirmation ?: return
        _state.update { it.copy(pendingConfirmation = null) }
        val settings = preferences.settings.first()
        finishWithStatus(
            command = pending.commandText,
            inputLanguage = "CANCELLED",
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
                // If an optional secondary step (like get_screen_text after open_url) needs Accessibility, keep primary result clear
                if (results.size > 1 && results.first().status == ExecutionStatus.SUCCESS && result.requiresAccessibility) {
                    overallStatus = ExecutionStatus.SUCCESS
                } else {
                    break
                }
            }
        }

        _state.update { it.copy(pipelineStage = PipelineStage.VERIFYING) }
        delay(120L)

        val summary = buildString {
            if (results.isEmpty()) {
                append(plan.assistantReply.ifBlank { "Done." })
            } else {
                results.forEachIndexed { idx, res ->
                    if (idx > 0) append(" ")
                    append(res.message)
                }
            }
        }

        finishWithStatus(
            command = command,
            inputLanguage = inputLanguage,
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
                orbState = if (speakResponse && summary.isNotBlank()) OrbState.SPEAKING else finalOrb,
                pipelineStage = finalStage,
                finalReply = summary,
                finalStatus = status
            )
        }

        if (speakResponse && summary.isNotBlank()) {
            voiceOutput.speak(summary) {
                _state.update { it.copy(orbState = finalOrb) }
                onComplete?.invoke()
            }
        } else {
            onComplete?.invoke()
        }
    }

    fun updateOrbState(orbState: OrbState) {
        _state.update { it.copy(orbState = orbState) }
    }
}
