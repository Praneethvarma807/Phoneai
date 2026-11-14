package com.example.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ExecutionStatus {
    SUCCESS,
    FAILED,
    PERMISSION_REQUIRED,
    CONFIRMATION_REQUIRED,
    UNSUPPORTED,
    CANCELLED
}

enum class OrbState {
    IDLE,
    WAKE_WORD_WAITING,
    WAKE_WORD_DETECTED,
    LISTENING,
    PROCESSING,
    THINKING,
    EXECUTING,
    SPEAKING,
    SUCCESS,
    ERROR
}

enum class PipelineStage(val label: String) {
    IDLE("Ready for command"),
    UNDERSTANDING("Understanding command"),
    PLANNING("Preparing action plan"),
    SELECTING_TOOLS("Selecting tools"),
    EXECUTING("Executing task"),
    VERIFYING("Verifying result"),
    COMPLETED("Completed"),
    FAILED("Execution halted")
}

enum class ActionCategory {
    BROWSER,
    SEARCH,
    SETTINGS,
    CALENDAR,
    DOCUMENT,
    MESSAGING,
    VISION,
    SYSTEM,
    APP_LAUNCH
}

@Serializable
data class ToolCallRequest(
    val tool: String,
    val arguments: Map<String, String> = emptyMap()
)

@Serializable
data class ActionPlan(
    val intent: String,
    val interpretation: String,
    val steps: List<String> = emptyList(),
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val assistantReply: String = "",
    val engineUsed: String = "DETERMINISTIC"
)

data class ToolResult(
    val toolName: String,
    val status: ExecutionStatus,
    val message: String,
    val verificationNote: String = "",
    val data: Map<String, String> = emptyMap(),
    val missingPermissions: List<String> = emptyList(),
    val requiresAccessibility: Boolean = false,
    val requiresWriteSettings: Boolean = false,
    val requiresScreenCapture: Boolean = false,
    val confirmationPreview: String? = null,
    val category: ActionCategory = ActionCategory.SYSTEM
)

data class PendingConfirmation(
    val commandText: String,
    val plan: ActionPlan,
    val previewTitle: String,
    val previewBody: String,
    val stepIndex: Int
)

enum class SpeechLanguageOption(val code: String, val displayName: String) {
    AUTO("auto", "Auto (EN + TE + HI)"),
    EN_IN("en-IN", "English (India/US)"),
    TE_IN("te-IN", "Telugu (తెలుగు)"),
    HI_IN("hi-IN", "Hindi (हिन्दी)")
}

enum class DeviceTier(val label: String, val recommendation: String) {
    LOW_RAM("Low Memory (< 4 GB RAM)", "Deterministic Local Command Engine + lightweight KWS recommended."),
    MEDIUM_RAM("Medium Memory (4–6 GB RAM)", "Recommended: Qwen3-0.6B (~614 MB) or Gemma 3 1B quantized."),
    HIGH_RAM("High Memory (6+ GB RAM)", "Capable of Gemma 4 E2B IT (~2.6 GB) or Qwen3-0.6B on CPU/GPU.")
}

data class DeviceCompatibilityInfo(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val availableStorageMb: Long,
    val cpuAbi: String,
    val isArm64: Boolean,
    val supportsGpuAcceleration: Boolean,
    val tier: DeviceTier
)

enum class ModelInstallStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
    READY,
    FAILED,
    CORRUPTED
}

data class LocalModelSpec(
    val id: String,
    val displayName: String,
    val family: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val minRamMb: Long,
    val minStorageMb: Long,
    val license: String,
    val description: String,
    val recommendedTier: DeviceTier,
    val expectedSha256: String? = null
)

data class ModelRuntimeState(
    val activeModelId: String? = null,
    val loadedModelName: String? = null,
    val backendUsed: String = "None",
    val isLoading: Boolean = false,
    val isReady: Boolean = false,
    val commandOnlyMode: Boolean = false,
    val error: String? = null
)

@Serializable
data class ToolSchemaInfo(
    val name: String,
    val description: String,
    val requiredPermissions: List<String>,
    val requiresConfirmation: Boolean,
    val parameters: Map<String, String>,
    val category: String
)
