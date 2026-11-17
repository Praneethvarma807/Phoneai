package com.example.ai

import android.content.Context
import com.example.domain.model.ActionPlan
import com.example.domain.model.ConversationTurn
import com.example.domain.model.LanguageDetectionResult
import com.example.domain.model.ModelRuntimeState
import com.example.domain.model.ToolCallRequest
import com.example.domain.model.ToolSchemaInfo
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LocalLlmEngine(
    private val context: Context
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isGenerating = false

    private val _runtimeState = MutableStateFlow(ModelRuntimeState())
    val runtimeState: StateFlow<ModelRuntimeState> = _runtimeState.asStateFlow()

    suspend fun loadModel(
        modelId: String,
        displayName: String,
        modelPath: String,
        preferGpu: Boolean,
        toolSchemas: List<ToolSchemaInfo>
    ): Result<Unit> = withContext(engineDispatcher) {
        mutex.withLock {
            val file = File(modelPath)
            if (!file.exists() || !file.isFile || !file.canRead()) {
                val msg = "Model file not found or unreadable at $modelPath"
                _runtimeState.update { it.copy(isLoading = false, isReady = false, error = msg) }
                return@withLock Result.failure(IllegalStateException(msg))
            }
            if (file.length() < MIN_VALID_MODEL_BYTES) {
                val msg = "Model file is incomplete or corrupted (${file.length()} bytes). Re-download or import a valid .litertlm model."
                _runtimeState.update { it.copy(isLoading = false, isReady = false, error = msg) }
                return@withLock Result.failure(IllegalStateException(msg))
            }

            if (engine?.engineConfig?.modelPath == modelPath && _runtimeState.value.isReady) {
                return@withLock Result.success(Unit)
            }

            releaseInternal()
            _runtimeState.update {
                it.copy(
                    activeModelId = modelId,
                    loadedModelName = displayName,
                    isLoading = true,
                    isReady = false,
                    error = null
                )
            }

            val compat = DeviceCompatibilityManager(context)
            if (!compat.isNativeLlmSupported()) {
                val msg = "64-bit ABI (arm64-v8a / x86_64) required for native LiteRT-LM. Continuing with Deterministic Command Engine."
                _runtimeState.update {
                    it.copy(
                        activeModelId = null,
                        loadedModelName = null,
                        backendUsed = "None",
                        isLoading = false,
                        isReady = false,
                        commandOnlyMode = true,
                        error = msg
                    )
                }
                return@withLock Result.failure(UnsupportedOperationException(msg))
            }

            val allowGpu = preferGpu && compat.inspectDevice().supportsGpuAcceleration
            val firstBackend = if (allowGpu) Backend.GPU() else Backend.CPU()
            val systemInstruction = buildSystemPrompt(toolSchemas)

            val loaded = tryInitialize(modelPath, firstBackend, systemInstruction)
            val finalResult = if (loaded.isFailure && allowGpu) {
                tryInitialize(modelPath, Backend.CPU(), systemInstruction).map { "CPU (GPU fallback)" }
            } else {
                loaded
            }

            finalResult.fold(
                onSuccess = { backendLabel ->
                    _runtimeState.update {
                        it.copy(
                            activeModelId = modelId,
                            loadedModelName = displayName,
                            backendUsed = backendLabel,
                            isLoading = false,
                            isReady = true,
                            commandOnlyMode = false,
                            error = null
                        )
                    }
                    Result.success(Unit)
                },
                onFailure = { err ->
                    releaseInternal()
                    val friendly = when (err) {
                        is UnsatisfiedLinkError -> "Native LiteRT-LM runtime is unavailable on this CPU architecture (${err.message})."
                        else -> err.message ?: "Failed to initialize LiteRT-LM model"
                    }
                    _runtimeState.update {
                        it.copy(
                            activeModelId = null,
                            loadedModelName = null,
                            backendUsed = "None",
                            isLoading = false,
                            isReady = false,
                            error = friendly
                        )
                    }
                    Result.failure(err)
                }
            )
        }
    }

    private fun tryInitialize(
        modelPath: String,
        backend: Backend,
        systemInstruction: String
    ): Result<String> = try {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = context.cacheDir.path
        )
        val createdEngine = Engine(config)
        createdEngine.initialize()
        engine = createdEngine
        conversation = createdEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemInstruction),
                samplerConfig = SamplerConfig(topK = 16, topP = 0.9, temperature = 0.2)
            )
        )
        Result.success(if (backend is Backend.GPU) "GPU" else "CPU")
    } catch (t: Throwable) {
        if (t is Error && t !is UnsatisfiedLinkError && t !is NoClassDefFoundError) {
            throw t
        }
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        Result.failure(t)
    }

    suspend fun planCommand(
        userInput: String,
        registeredTools: Set<String>,
        languageInfo: LanguageDetectionResult = LanguageDetector.detect(userInput),
        recentTurns: List<ConversationTurn> = emptyList(),
        memoryContext: List<String> = emptyList(),
        isOffline: Boolean = false
    ): Result<ActionPlan> = withContext(engineDispatcher) {
        mutex.withLock {
            val activeConversation = conversation
            if (!_runtimeState.value.isReady || activeConversation == null || _runtimeState.value.commandOnlyMode) {
                return@withLock Result.failure(IllegalStateException("Local LLM is not loaded"))
            }
            isGenerating = true
            try {
                val prompt = buildString {
                    if (isOffline) {
                        appendLine("Network status: OFFLINE. Prefer strictly on-device tools; do not select web_search.")
                    }
                    if (memoryContext.isNotEmpty()) {
                        appendLine("Verified personal user preferences (non-sensitive local memory):")
                        memoryContext.take(4).forEach { memo ->
                            appendLine("- $memo")
                        }
                    }
                    if (recentTurns.isNotEmpty()) {
                        appendLine("Recent conversation context (preserve topic/language if relevant):")
                        recentTurns.takeLast(4).forEach { turn ->
                            appendLine("- ${turn.role} (${turn.language.shortBadge}): ${turn.text.take(120)}")
                        }
                    }
                    appendLine("Detected user language: ${languageInfo.primaryLanguage.displayName} (badge=${languageInfo.badgeLabel})")
                    appendLine("Required assistant_reply language: ${languageInfo.responseLanguage.displayName}")
                    appendLine("User command: \"$userInput\"")
                    appendLine("Never invent facts or pretend a tool succeeded. Respond ONLY with valid JSON matching the ActionPlan schema.")
                }
                val rawBuffer = StringBuilder()
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    activeConversation.sendMessageAsync(prompt).collect { chunk ->
                        rawBuffer.append(chunk.toString())
                    }
                }
                val parsed = JsonPlanParser.parse(rawBuffer.toString(), registeredTools)
                if (parsed != null) {
                    Result.success(
                        parsed.copy(
                            engineUsed = "LOCAL_LLM (${_runtimeState.value.loadedModelName ?: "LiteRT-LM"})",
                            detectedLanguage = languageInfo.badgeLabel,
                            responseLanguage = languageInfo.responseLanguage.shortBadge
                        )
                    )
                } else {
                    Result.failure(IllegalStateException("Model output was not a valid JSON action plan"))
                }
            } catch (_: TimeoutCancellationException) {
                runCatching { activeConversation.cancelProcess() }
                Result.failure(IllegalStateException("Local AI response timed out after ${INFERENCE_TIMEOUT_MS / 1000}s; falling back safely."))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                isGenerating = false
            }
        }
    }

    suspend fun summarizeLocally(prompt: String): Result<String> = withContext(engineDispatcher) {
        mutex.withLock {
            val activeConversation = conversation
            if (!_runtimeState.value.isReady || activeConversation == null) {
                return@withLock Result.failure(IllegalStateException("Local LLM is not loaded"))
            }
            isGenerating = true
            try {
                val sb = StringBuilder()
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    activeConversation.sendMessageAsync(prompt).collect { chunk ->
                        sb.append(chunk.toString())
                    }
                }
                Result.success(sb.toString().trim())
            } catch (_: TimeoutCancellationException) {
                runCatching { activeConversation.cancelProcess() }
                Result.failure(IllegalStateException("Local summarization timed out."))
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                isGenerating = false
            }
        }
    }

    fun setCommandOnlyMode(enabled: Boolean) {
        _runtimeState.update { it.copy(commandOnlyMode = enabled) }
    }

    suspend fun unloadModel() = withContext(engineDispatcher) {
        mutex.withLock {
            releaseInternal()
            _runtimeState.update {
                it.copy(
                    activeModelId = null,
                    loadedModelName = null,
                    backendUsed = "None",
                    isLoading = false,
                    isReady = false,
                    error = null
                )
            }
        }
    }

    private suspend fun releaseInternal() {
        _runtimeState.update { it.copy(isReady = false) }
        var attempts = 0
        while (isGenerating && attempts < 20) {
            runCatching { conversation?.cancelProcess() }
            delay(100)
            attempts++
        }
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }

    fun shutdown() {
        scope.launch {
            mutex.withLock { releaseInternal() }
            executor.shutdown()
        }.invokeOnCompletion { scope.cancel() }
    }

    private fun buildSystemPrompt(schemas: List<ToolSchemaInfo>): String = buildString {
        appendLine("You are AI Phone Commander, an offline on-device Android automation planner.")
        appendLine("Never output arbitrary code. Never fabricate results for actions not performed by tools.")
        appendLine("Respond in the same language the user used (English, Telugu, Hindi, Tamil, Kannada, or Malayalam) unless asked to switch.")
        appendLine("Output ONLY a single JSON object with keys:")
        appendLine("""{"intent":"string","interpretation":"string","steps":["string"],"tool_calls":[{"tool":"tool_name","arguments":{"key":"value"}}],"requires_confirmation":false,"confirmation_prompt":null,"assistant_reply":"string"}""")
        appendLine("Available tools:")
        schemas.forEach { schema ->
            appendLine("- ${schema.name}: ${schema.description} | params=${schema.parameters} | confirm=${schema.requiresConfirmation}")
        }
    }

    companion object {
        private const val INFERENCE_TIMEOUT_MS = 45_000L
        private const val MIN_VALID_MODEL_BYTES = 1_000_000L
    }
}

object JsonPlanParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(rawOutput: String, allowedTools: Set<String>): ActionPlan? {
        val start = rawOutput.indexOf('{')
        val end = rawOutput.lastIndexOf('}')
        if (start == -1 || end <= start) return null
        val jsonSlice = rawOutput.substring(start, end + 1)

        return runCatching {
            val root: JsonObject = json.parseToJsonElement(jsonSlice).jsonObject
            val intent = root["intent"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: "general_command"
            val interpretation = root["interpretation"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: intent
            val steps = root["steps"]?.jsonArray?.mapNotNull {
                it.takeUnless { el -> el is JsonNull }?.jsonPrimitive?.contentOrNull
            } ?: emptyList()
            val requiresConfirmation = root["requires_confirmation"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.booleanOrNull ?: false
            val confirmationPrompt = root["confirmation_prompt"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
            val reply = root["assistant_reply"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: ""
            val calls = root["tool_calls"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val toolName = obj["tool"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (toolName !in allowedTools) return@mapNotNull null
                val argsMap = obj["arguments"]?.takeUnless { it is JsonNull }?.jsonObject
                    ?.mapNotNull { (k, v) ->
                        val str = v.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        k to str
                    }?.toMap() ?: emptyMap()
                ToolCallRequest(tool = toolName, arguments = argsMap)
            } ?: emptyList()

            if (calls.isEmpty() && reply.isBlank()) return null

            ActionPlan(
                intent = intent,
                interpretation = interpretation,
                steps = steps,
                toolCalls = calls,
                requiresConfirmation = requiresConfirmation,
                confirmationPrompt = confirmationPrompt,
                assistantReply = reply
            )
        }.getOrNull()
    }
}
