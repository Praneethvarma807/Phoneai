package com.example.domain.model

import kotlinx.serialization.Serializable

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

enum class AppLanguage(
    val bcp47: String,
    val displayName: String,
    val shortBadge: String,
    val nativeName: String
) {
    ENGLISH("en-IN", "English", "EN", "English"),
    TELUGU("te-IN", "Telugu", "TE", "తెలుగు"),
    HINDI("hi-IN", "Hindi", "HI", "हिन्दी"),
    TAMIL("ta-IN", "Tamil", "TA", "தமிழ்"),
    KANNADA("kn-IN", "Kannada", "KN", "ಕನ್ನಡ"),
    MALAYALAM("ml-IN", "Malayalam", "ML", "മലയാളം"),
    UNSUPPORTED("en-IN", "Other / Unsupported", "OTHER", "Other")
}

data class LanguageDetectionResult(
    val primaryLanguage: AppLanguage,
    val secondaryLanguage: AppLanguage? = null,
    val isMixed: Boolean = false,
    val explicitRequestedLanguage: AppLanguage? = null,
    val bcp47Tag: String = primaryLanguage.bcp47,
    val confidence: Float = 1.0f,
    val badgeLabel: String = if (isMixed && secondaryLanguage != null) {
        "${primaryLanguage.shortBadge} + ${secondaryLanguage.shortBadge}"
    } else {
        primaryLanguage.shortBadge
    }
) {
    val responseLanguage: AppLanguage
        get() = explicitRequestedLanguage ?: primaryLanguage
}

data class ConversationTurn(
    val role: String,
    val text: String,
    val language: AppLanguage
)

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
    val engineUsed: String = "DETERMINISTIC",
    val detectedLanguage: String = "EN",
    val responseLanguage: String = "EN"
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
    val stepIndex: Int,
    val responseLanguage: AppLanguage = AppLanguage.ENGLISH
)

enum class RecognizerBackendMode(val label: String) {
    ON_DEVICE("On-Device"),
    SYSTEM_DEFAULT("Platform Service"),
    CUSTOM_SERVICE("Alt Recognition Service"),
    UNAVAILABLE("Unavailable")
}

enum class LanguageSupportStatus(val label: String) {
    VERIFIED_OFFLINE("Offline Ready"),
    VERIFIED_ONLINE_ONLY("Network Only"),
    UNSUPPORTED("Unsupported"),
    UNVERIFIED_ERROR_14("Unverified"),
    UNKNOWN("Checking…")
}

enum class SpeechLanguageOption(val code: String, val displayName: String, val appLanguage: AppLanguage?) {
    AUTO("auto", "Auto (Multilingual)", null),
    EN_IN("en-IN", "English (IN)", AppLanguage.ENGLISH),
    EN_US("en-US", "English (US)", AppLanguage.ENGLISH),
    TE_IN("te-IN", "Telugu (తెలుగు)", AppLanguage.TELUGU),
    HI_IN("hi-IN", "Hindi (हिन्दी)", AppLanguage.HINDI),
    TA_IN("ta-IN", "Tamil (தமிழ்)", AppLanguage.TAMIL),
    KN_IN("kn-IN", "Kannada (ಕನ್ನಡ)", AppLanguage.KANNADA),
    ML_IN("ml-IN", "Malayalam (മലയാളം)", AppLanguage.MALAYALAM)
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
