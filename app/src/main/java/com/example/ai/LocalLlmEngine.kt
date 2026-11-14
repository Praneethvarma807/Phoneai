package com.example.ai

import android.content.Context
import com.example.domain.model.ActionPlan
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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
            if (!file.exists() || !file.isFile) {
                val msg = "Model file not found at $modelPath"
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

            val firstBackend = if (preferGpu) Backend.GPU() else Backend.CPU()
            val systemInstruction = buildSystemPrompt(toolSchemas)

            val loaded = tryInitialize(modelPath, firstBackend, systemInstruction)
            val finalResult = if (loaded.isFailure && preferGpu) {
                tryInitialize(modelPath, Backend.CPU(), systemInstruction)
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
                    _runtimeState.update {
                        it.copy(
                            activeModelId = null,
                            loadedModelName = null,
                            backendUsed = "None",
                            isLoading = false,
                            isReady = false,
                            error = err.message ?: "Failed to initialize LiteRT-LM model"
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
    ): Result<String> = runCatching {
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
        if (backend is Backend.GPU) "GPU" else "CPU"
    }

    suspend fun planCommand(
        userInput: String,
        registeredTools: Set<String>
    ): Result<ActionPlan> = withContext(engineDispatcher) {
        mutex.withLock {
            val activeConversation = conversation
            if (!_runtimeState.value.isReady || activeConversation == null || _runtimeState.value.commandOnlyMode) {
                return@withLock Result.failure(IllegalStateException("Local LLM is not loaded"))
            }
            isGenerating = true
            try {
                val prompt = buildString {
                    appendLine("User command: \"$userInput\"")
                    appendLine("Respond ONLY with valid JSON matching the ActionPlan schema.")
                }
                val rawBuffer = StringBuilder()
                activeConversation.sendMessageAsync(prompt).collect { chunk ->
                    rawBuffer.append(chunk.toString())
                }
                val parsed = JsonPlanParser.parse(rawBuffer.toString(), registeredTools)
                if (parsed != null) {
                    Result.success(parsed.copy(engineUsed = "LOCAL_LLM (${_runtimeState.value.loadedModelName ?: "LiteRT-LM"})"))
                } else {
                    Result.failure(IllegalStateException("Model output was not a valid JSON action plan"))
                }
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
                activeConversation.sendMessageAsync(prompt).collect { chunk ->
                    sb.append(chunk.toString())
                }
                Result.success(sb.toString().trim())
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
        appendLine("Never output arbitrary code. Output ONLY a single JSON object with keys:")
        appendLine("""{"intent":"string","interpretation":"string","steps":["string"],"tool_calls":[{"tool":"tool_name","arguments":{"key":"value"}}],"requires_confirmation":false,"confirmation_prompt":null,"assistant_reply":"string"}""")
        appendLine("Available tools:")
        schemas.forEach { schema ->
            appendLine("- ${schema.name}: ${schema.description} | params=${schema.parameters} | confirm=${schema.requiresConfirmation}")
        }
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
            val intent = root["intent"]?.jsonPrimitive?.content ?: "general_command"
            val interpretation = root["interpretation"]?.jsonPrimitive?.content ?: intent
            val steps = root["steps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            val requiresConfirmation = root["requires_confirmation"]?.jsonPrimitive?.booleanOrNull ?: false
            val confirmationPrompt = root["confirmation_prompt"]?.jsonPrimitive?.content
            val reply = root["assistant_reply"]?.jsonPrimitive?.content ?: ""
            val calls = root["tool_calls"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val toolName = obj["tool"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (toolName !in allowedTools) return@mapNotNull null
                val argsMap = obj["arguments"]?.jsonObject?.mapValues { (_, v) ->
                    v.jsonPrimitive.content
                } ?: emptyMap()
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
