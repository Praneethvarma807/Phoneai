package com.example.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.ai.LanguageDetector
import com.example.domain.model.AppLanguage
import com.example.domain.model.LanguageSupportStatus
import com.example.domain.model.RecognizerBackendMode
import com.example.domain.model.SpeechLanguageOption
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VoiceListeningState(
    val isListening: Boolean = false,
    val isStarting: Boolean = false,
    val rmsNormalized: Float = 0f,
    val partialTranscript: String = "",
    val durationMs: Long = 0L,
    val isRecognitionAvailable: Boolean = true,
    val isOfflineRecognitionSupported: Boolean = false,
    val backendMode: RecognizerBackendMode = RecognizerBackendMode.UNAVAILABLE,
    val servicePackageName: String? = null,
    val discoveredServices: List<String> = emptyList(),
    val detectedSpokenLocale: String? = null,
    val detectedAppLanguage: AppLanguage? = null,
    val languageSupportMap: Map<String, LanguageSupportStatus> = emptyMap(),
    val hasNetworkConnection: Boolean = false,
    val lastErrorCode: Int? = null,
    val canRetry: Boolean = false,
    val modelDownloadStatus: String? = null,
    val missingPackWarning: String? = null,
    val error: String? = null
)

internal interface SpeechRecognizerHandle {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun checkRecognitionSupport(
        intent: Intent,
        executor: Executor,
        callback: RecognitionSupportCallback
    )
    fun triggerModelDownload(
        intent: Intent,
        executor: Executor,
        listener: ModelDownloadListener?
    ): Boolean
}

internal interface SpeechRecognizerFactory {
    fun isRecognitionAvailable(context: Context): Boolean
    fun isOnDeviceRecognitionAvailable(context: Context): Boolean
    fun discoverServices(context: Context): List<ComponentName>
    fun hasInternetConnection(context: Context): Boolean
    fun isRecordAudioGranted(context: Context): Boolean
    fun createOnDeviceRecognizer(context: Context): SpeechRecognizerHandle?
    fun createDefaultRecognizer(context: Context): SpeechRecognizerHandle?
    fun createComponentRecognizer(context: Context, componentName: ComponentName): SpeechRecognizerHandle?
}

private class AndroidSpeechRecognizerHandle(
    private val delegate: SpeechRecognizer
) : SpeechRecognizerHandle {
    override fun setRecognitionListener(listener: RecognitionListener) {
        delegate.setRecognitionListener(listener)
    }

    override fun startListening(intent: Intent) {
        delegate.startListening(intent)
    }

    override fun stopListening() {
        delegate.stopListening()
    }

    override fun cancel() {
        delegate.cancel()
    }

    override fun destroy() {
        delegate.destroy()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun checkRecognitionSupport(
        intent: Intent,
        executor: Executor,
        callback: RecognitionSupportCallback
    ) {
        delegate.checkRecognitionSupport(intent, executor, callback)
    }

    override fun triggerModelDownload(
        intent: Intent,
        executor: Executor,
        listener: ModelDownloadListener?
    ): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && listener != null -> {
                delegate.triggerModelDownload(intent, executor, listener)
                true
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                delegate.triggerModelDownload(intent)
                true
            }
            else -> false
        }
    }
}

private class DefaultSpeechRecognizerFactory : SpeechRecognizerFactory {
    override fun isRecognitionAvailable(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun isOnDeviceRecognitionAvailable(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            false
        }

    override fun discoverServices(context: Context): List<ComponentName> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val pm = context.packageManager
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
            }
        }.getOrDefault(emptyList())

        val preferredPackages = listOf(
            "com.google.android.tts",
            "com.google.android.googlequicksearchbox",
            "com.google.android.as"
        )
        return resolved
            .mapNotNull { info ->
                info.serviceInfo?.let { ComponentName(it.packageName, it.name) }
            }
            .distinct()
            .sortedBy { component ->
                val idx = preferredPackages.indexOf(component.packageName)
                if (idx >= 0) idx else preferredPackages.size
            }
    }

    override fun hasInternetConnection(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isRecordAudioGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun createOnDeviceRecognizer(context: Context): SpeechRecognizerHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            AndroidSpeechRecognizerHandle(SpeechRecognizer.createOnDeviceSpeechRecognizer(context))
        }.getOrNull()
    }

    override fun createDefaultRecognizer(context: Context): SpeechRecognizerHandle? =
        runCatching {
            AndroidSpeechRecognizerHandle(SpeechRecognizer.createSpeechRecognizer(context))
        }.getOrNull()

    override fun createComponentRecognizer(
        context: Context,
        componentName: ComponentName
    ): SpeechRecognizerHandle? =
        runCatching {
            AndroidSpeechRecognizerHandle(SpeechRecognizer.createSpeechRecognizer(context, componentName))
        }.getOrNull()
}

private data class RecognitionAttemptSpec(
    val mode: RecognizerBackendMode,
    val componentName: ComponentName?,
    val preferOffline: Boolean
)

class VoiceRecognitionManager internal constructor(
    context: Context,
    private val factory: SpeechRecognizerFactory = DefaultSpeechRecognizerFactory()
) {
    constructor(context: Context) : this(context, DefaultSpeechRecognizerFactory())

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainExecutor = Executor { runnable ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private val activeSessionId = AtomicLong(0L)
    private var recognizer: SpeechRecognizerHandle? = null
    private var supportChecker: SpeechRecognizerHandle? = null
    private var activeDownloadRecognizer: SpeechRecognizerHandle? = null
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null
    private var startedAtMs: Long = 0L

    // Track temporary cooldown when OEM/system on-device binder disconnects with Error 11/14
    @Volatile
    private var onDeviceDisqualifiedUntilMs: Long = 0L

    @Volatile
    private var rateLimitedUntilMs: Long = 0L

    @Volatile
    private var discoveredComponents: List<ComponentName> = emptyList()

    private val _state = MutableStateFlow(VoiceListeningState())
    val state: StateFlow<VoiceListeningState> = _state.asStateFlow()

    init {
        refreshCapabilitiesAsync()
    }

    private fun isOnDeviceDisqualified(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < onDeviceDisqualifiedUntilMs

    private fun markOnDeviceTemporarilyDisqualified() {
        onDeviceDisqualifiedUntilMs = System.currentTimeMillis() + ON_DEVICE_COOLDOWN_MS
    }

    fun resetTemporaryDisqualification() {
        onDeviceDisqualifiedUntilMs = 0L
    }

    fun isCurrentlyListening(): Boolean = _state.value.isListening || _state.value.isStarting

    fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitedUntilMs

    fun checkCapabilities(): VoiceListeningState {
        val available = factory.isRecognitionAvailable(appContext)
        val components = factory.discoverServices(appContext)
        discoveredComponents = components
        val hasService = available || components.isNotEmpty()
        val rawOnDevice = factory.isOnDeviceRecognitionAvailable(appContext)
        val onDeviceCoolingDown = isOnDeviceDisqualified()
        val hasNetwork = factory.hasInternetConnection(appContext)
        val existingMap = _state.value.languageSupportMap

        val effectiveBackend = when {
            rawOnDevice && !onDeviceCoolingDown -> RecognizerBackendMode.ON_DEVICE
            available -> RecognizerBackendMode.SYSTEM_DEFAULT
            components.isNotEmpty() -> RecognizerBackendMode.CUSTOM_SERVICE
            else -> RecognizerBackendMode.UNAVAILABLE
        }

        val primaryPkg = components.firstOrNull()?.packageName

        val warning = when {
            !hasService ->
                "No Speech Recognition service is installed or enabled on this device. Install Google Speech Services or use text input."
            onDeviceCoolingDown && !hasNetwork ->
                "On-device recognition service disconnected (Error 11) and no internet is available. Install offline language packs in Voice Settings or type your command."
            onDeviceCoolingDown ->
                "On-device speech service disconnected; temporarily using platform recognition service."
            !rawOnDevice && !hasNetwork ->
                "On-device offline speech pack was not verified by the OS. Connect to internet or install offline voice packs in Language Settings."
            !rawOnDevice ->
                "On-device offline speech pack not reported by OS; using platform recognition service."
            else -> null
        }

        val updated = _state.value.copy(
            isRecognitionAvailable = hasService,
            isOfflineRecognitionSupported = rawOnDevice && !onDeviceCoolingDown,
            backendMode = effectiveBackend,
            servicePackageName = primaryPkg,
            discoveredServices = components.map { it.flattenToShortString() },
            hasNetworkConnection = hasNetwork,
            languageSupportMap = existingMap,
            missingPackWarning = warning
        )
        _state.value = updated
        return updated
    }

    fun refreshCapabilitiesAsync(
        targetLanguage: SpeechLanguageOption = SpeechLanguageOption.AUTO,
        onCompleted: ((VoiceListeningState) -> Unit)? = null
    ) {
        resetTemporaryDisqualification()
        val base = checkCapabilities()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !base.isRecognitionAvailable) {
            onCompleted?.invoke(base)
            return
        }

        runOnMain {
            val checker = when {
                factory.isOnDeviceRecognitionAvailable(appContext) && !isOnDeviceDisqualified() ->
                    factory.createOnDeviceRecognizer(appContext) ?: factory.createDefaultRecognizer(appContext)
                else -> factory.createDefaultRecognizer(appContext)
                    ?: discoveredComponents.firstOrNull()?.let { factory.createComponentRecognizer(appContext, it) }
            }

            if (checker == null) {
                markSupportUnverified(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, targetLanguage)
                onCompleted?.invoke(_state.value)
                return@runOnMain
            }

            runCatching { supportChecker?.destroy() }
            supportChecker = checker
            val completed = AtomicBoolean(false)
            val probeIntent = buildSupportCheckIntent(resolveLocaleTag(targetLanguage))

            val timeoutRunnable = Runnable {
                if (completed.compareAndSet(false, true)) {
                    runCatching { checker.destroy() }
                    if (supportChecker === checker) supportChecker = null
                    markSupportUnverified(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, targetLanguage)
                    onCompleted?.invoke(_state.value)
                }
            }
            mainHandler.postDelayed(timeoutRunnable, SUPPORT_CHECK_TIMEOUT_MS)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    probeSupportApi33(checker, probeIntent, completed, timeoutRunnable, targetLanguage, onCompleted)
                } else {
                    if (completed.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeoutRunnable)
                        runCatching { checker.destroy() }
                        if (supportChecker === checker) supportChecker = null
                        markSupportUnverified(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, targetLanguage)
                        onCompleted?.invoke(_state.value)
                    }
                }
            } catch (_: Throwable) {
                if (completed.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    runCatching { checker.destroy() }
                    if (supportChecker === checker) supportChecker = null
                    markSupportUnverified(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, targetLanguage)
                    onCompleted?.invoke(_state.value)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun probeSupportApi33(
        checker: SpeechRecognizerHandle,
        probeIntent: Intent,
        completed: AtomicBoolean,
        timeoutRunnable: Runnable,
        targetLanguage: SpeechLanguageOption,
        onCompleted: ((VoiceListeningState) -> Unit)?
    ) {
        checker.checkRecognitionSupport(
            probeIntent,
            mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    if (!completed.compareAndSet(false, true)) return
                    mainHandler.removeCallbacks(timeoutRunnable)
                    runCatching { checker.destroy() }
                    if (supportChecker === checker) supportChecker = null

                    applyRecognitionSupportResult(recognitionSupport)
                    onCompleted?.invoke(_state.value)
                }

                override fun onError(error: Int) {
                    if (!completed.compareAndSet(false, true)) return
                    mainHandler.removeCallbacks(timeoutRunnable)
                    runCatching { checker.destroy() }
                    if (supportChecker === checker) supportChecker = null

                    if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        markOnDeviceTemporarilyDisqualified()
                    }
                    markSupportUnverified(error, targetLanguage)
                    onCompleted?.invoke(_state.value)
                }
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    internal fun applyRecognitionSupportResult(support: RecognitionSupport) {
        val installed = support.installedOnDeviceLanguages.map { it.lowercase(Locale.ROOT) }
        val supportedOnDevice = support.supportedOnDeviceLanguages.map { it.lowercase(Locale.ROOT) }
        val online = support.onlineLanguages.map { it.lowercase(Locale.ROOT) }
        val hasInternet = factory.hasInternetConnection(appContext)

        val statusMap = buildMap {
            for (opt in SpeechLanguageOption.entries) {
                if (opt == SpeechLanguageOption.AUTO) continue
                val codeLower = opt.code.lowercase(Locale.ROOT)
                val langPrefix = codeLower.substringBefore("-")
                val matchesInstalled = installed.any { it == codeLower || it.startsWith("$langPrefix-") || it == langPrefix }
                val matchesOnDevice = supportedOnDevice.any { it == codeLower || it.startsWith("$langPrefix-") || it == langPrefix }
                val matchesOnline = online.any { it == codeLower || it.startsWith("$langPrefix-") || it == langPrefix }

                val status = when {
                    matchesInstalled -> LanguageSupportStatus.VERIFIED_OFFLINE
                    matchesOnDevice && matchesOnline -> LanguageSupportStatus.VERIFIED_ONLINE_ONLY
                    matchesOnline -> LanguageSupportStatus.VERIFIED_ONLINE_ONLY
                    matchesOnDevice -> LanguageSupportStatus.VERIFIED_ONLINE_ONLY
                    else -> LanguageSupportStatus.UNSUPPORTED
                }
                put(opt.code, status)
            }
        }

        val anyOfflineReady = statusMap.values.any { it == LanguageSupportStatus.VERIFIED_OFFLINE }
        _state.update { prev ->
            prev.copy(
                isOfflineRecognitionSupported = anyOfflineReady,
                hasNetworkConnection = hasInternet,
                languageSupportMap = statusMap,
                missingPackWarning = when {
                    anyOfflineReady -> null
                    hasInternet -> "Offline packs are not installed yet for some languages; network recognition will be used when needed."
                    else -> "Offline speech packs could not be confirmed as installed. Download a language pack in Voice Settings."
                }
            )
        }
    }

    private fun markSupportUnverified(errorCode: Int, language: SpeechLanguageOption) {
        val map = _state.value.languageSupportMap.toMutableMap()
        if (language != SpeechLanguageOption.AUTO) {
            map[language.code] = LanguageSupportStatus.UNVERIFIED_ERROR_14
        } else {
            for (opt in SpeechLanguageOption.entries) {
                if (opt != SpeechLanguageOption.AUTO && opt.code !in map) {
                    map[opt.code] = LanguageSupportStatus.UNVERIFIED_ERROR_14
                }
            }
        }
        val base = checkCapabilities()
        val warning = when (errorCode) {
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "On-device Speech Service disconnected (Error 11); falling back to Platform Recognition Service."
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
                "Device speech service could not verify language support (Error 14); recognition will use available system provider."
            else -> base.missingPackWarning
        }
        _state.update {
            it.copy(
                backendMode = base.backendMode,
                isOfflineRecognitionSupported = base.isOfflineRecognitionSupported,
                languageSupportMap = map,
                missingPackWarning = warning
            )
        }
    }

    fun startListening(
        language: SpeechLanguageOption,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        allowBusyRetry: Boolean = true
    ) {
        val sessionId = activeSessionId.incrementAndGet()
        runOnMain {
            if (sessionId != activeSessionId.get()) return@runOnMain

            if (!factory.isRecordAudioGranted(appContext)) {
                val msg = mapSpeechError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, language)
                _state.update {
                    it.copy(
                        isListening = false,
                        isStarting = false,
                        rmsNormalized = 0f,
                        lastErrorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        canRetry = true,
                        error = msg
                    )
                }
                onError(msg)
                return@runOnMain
            }

            val now = System.currentTimeMillis()
            if (now < rateLimitedUntilMs) {
                val msg = mapSpeechError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, language)
                _state.update {
                    it.copy(
                        isListening = false,
                        isStarting = false,
                        lastErrorCode = SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
                        canRetry = true,
                        error = msg
                    )
                }
                onError(msg)
                return@runOnMain
            }

            val caps = checkCapabilities()
            if (!caps.isRecognitionAvailable) {
                val msg = "No Speech Recognition service is available on this device. Install Google Speech Services or use text input."
                _state.update {
                    it.copy(
                        isListening = false,
                        isStarting = false,
                        isRecognitionAvailable = false,
                        backendMode = RecognizerBackendMode.UNAVAILABLE,
                        lastErrorCode = SpeechRecognizer.ERROR_CLIENT,
                        canRetry = false,
                        error = msg
                    )
                }
                onError(msg)
                return@runOnMain
            }

            val localeTag = resolveLocaleTag(language)
            val attemptPlan = buildAttemptPlan(language, localeTag, caps)

            val hadActiveRecognizer = recognizer != null
            destroyRecognizerInternal()
            stopTimers()

            _state.update {
                it.copy(
                    isStarting = true,
                    isListening = false,
                    partialTranscript = "",
                    durationMs = 0L,
                    detectedSpokenLocale = null,
                    detectedAppLanguage = null,
                    lastErrorCode = null,
                    canRetry = false,
                    error = null
                )
            }

            if (hadActiveRecognizer) {
                // Allow a brief Binder settle delay after tearing down the previous recognizer
                // to prevent OEM ERROR_SERVER_DISCONNECTED (11) / ERROR_RECOGNIZER_BUSY (8).
                scope.launch {
                    delay(REBIND_SETTLE_DELAY_MS)
                    if (sessionId == activeSessionId.get()) {
                        launchAttempt(
                            sessionId = sessionId,
                            language = language,
                            localeTag = localeTag,
                            attempts = attemptPlan,
                            attemptIndex = 0,
                            busyRetriesRemaining = if (allowBusyRetry) 1 else 0,
                            onResult = onResult,
                            onError = onError
                        )
                    }
                }
            } else {
                launchAttempt(
                    sessionId = sessionId,
                    language = language,
                    localeTag = localeTag,
                    attempts = attemptPlan,
                    attemptIndex = 0,
                    busyRetriesRemaining = if (allowBusyRetry) 1 else 0,
                    onResult = onResult,
                    onError = onError
                )
            }
        }
    }

    private fun buildAttemptPlan(
        language: SpeechLanguageOption,
        localeTag: String,
        caps: VoiceListeningState
    ): List<RecognitionAttemptSpec> {
        val attempts = mutableListOf<RecognitionAttemptSpec>()
        val hasNetwork = caps.hasNetworkConnection
        val langSupport = caps.languageSupportMap[localeTag]
        val isOfflineConfirmed = langSupport == LanguageSupportStatus.VERIFIED_OFFLINE
        val shouldTryOnDeviceFirst = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            factory.isOnDeviceRecognitionAvailable(appContext) &&
            !isOnDeviceDisqualified() &&
            langSupport != LanguageSupportStatus.VERIFIED_ONLINE_ONLY

        if (shouldTryOnDeviceFirst) {
            attempts += RecognitionAttemptSpec(
                mode = RecognizerBackendMode.ON_DEVICE,
                componentName = null,
                preferOffline = true
            )
        }

        if (factory.isRecognitionAvailable(appContext)) {
            attempts += RecognitionAttemptSpec(
                mode = RecognizerBackendMode.SYSTEM_DEFAULT,
                componentName = null,
                // Do not force EXTRA_PREFER_OFFLINE when network is available unless pack is confirmed offline,
                // avoiding Error 11/12/13 when OEM offline models are missing.
                preferOffline = !hasNetwork && isOfflineConfirmed
            )
        }

        val altComponent = discoveredComponents.firstOrNull()
        if (altComponent != null &&
            attempts.none { it.componentName == altComponent } &&
            attempts.size < MAX_ATTEMPT_COUNT
        ) {
            attempts += RecognitionAttemptSpec(
                mode = RecognizerBackendMode.CUSTOM_SERVICE,
                componentName = altComponent,
                preferOffline = !hasNetwork
            )
        }

        return attempts.take(MAX_ATTEMPT_COUNT)
    }

    private fun launchAttempt(
        sessionId: Long,
        language: SpeechLanguageOption,
        localeTag: String,
        attempts: List<RecognitionAttemptSpec>,
        attemptIndex: Int,
        busyRetriesRemaining: Int,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (sessionId != activeSessionId.get()) return
        if (attemptIndex >= attempts.size) {
            val msg = "Unable to connect to a working Speech Recognition service. Please check Speech Services or type your command."
            _state.update {
                it.copy(
                    isStarting = false,
                    isListening = false,
                    rmsNormalized = 0f,
                    lastErrorCode = SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                    canRetry = true,
                    error = msg
                )
            }
            onError(msg)
            return
        }

        val spec = attempts[attemptIndex]
        val handle = when (spec.mode) {
            RecognizerBackendMode.ON_DEVICE -> factory.createOnDeviceRecognizer(appContext)
            RecognizerBackendMode.SYSTEM_DEFAULT -> factory.createDefaultRecognizer(appContext)
            RecognizerBackendMode.CUSTOM_SERVICE -> spec.componentName?.let {
                factory.createComponentRecognizer(appContext, it)
            }
            RecognizerBackendMode.UNAVAILABLE -> null
        }

        if (handle == null) {
            if (spec.mode == RecognizerBackendMode.ON_DEVICE) {
                markOnDeviceTemporarilyDisqualified()
            }
            launchAttempt(
                sessionId = sessionId,
                language = language,
                localeTag = localeTag,
                attempts = attempts,
                attemptIndex = attemptIndex + 1,
                busyRetriesRemaining = busyRetriesRemaining,
                onResult = onResult,
                onError = onError
            )
            return
        }

        recognizer = handle
        _state.update {
            it.copy(
                backendMode = spec.mode,
                servicePackageName = spec.componentName?.packageName ?: it.servicePackageName
            )
        }

        val intent = buildRecognizeIntent(language, localeTag, spec.preferOffline)
        handle.setRecognitionListener(
            createSessionListener(
                sessionId = sessionId,
                language = language,
                localeTag = localeTag,
                attempts = attempts,
                attemptIndex = attemptIndex,
                spec = spec,
                busyRetriesRemaining = busyRetriesRemaining,
                onResult = onResult,
                onError = onError
            )
        )

        try {
            handle.startListening(intent)
        } catch (_: Throwable) {
            destroyRecognizerInternal()
            if (spec.mode == RecognizerBackendMode.ON_DEVICE) {
                markOnDeviceTemporarilyDisqualified()
            }
            // Bounded exponential backoff fallback to next backend if available
            if (attemptIndex + 1 < attempts.size) {
                val backoffDelay = FALLBACK_RETRY_DELAY_MS * (1L shl attemptIndex.coerceAtMost(2))
                scope.launch {
                    delay(backoffDelay)
                    if (sessionId == activeSessionId.get()) {
                        launchAttempt(
                            sessionId = sessionId,
                            language = language,
                            localeTag = localeTag,
                            attempts = attempts,
                            attemptIndex = attemptIndex + 1,
                            busyRetriesRemaining = busyRetriesRemaining,
                            onResult = onResult,
                            onError = onError
                        )
                    }
                }
            } else {
                val msg = mapSpeechError(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, language)
                _state.update {
                    it.copy(
                        isStarting = false,
                        isListening = false,
                        rmsNormalized = 0f,
                        lastErrorCode = SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        canRetry = true,
                        error = msg
                    )
                }
                onError(msg)
            }
        }
    }

    private fun createSessionListener(
        sessionId: Long,
        language: SpeechLanguageOption,
        localeTag: String,
        attempts: List<RecognitionAttemptSpec>,
        attemptIndex: Int,
        spec: RecognitionAttemptSpec,
        busyRetriesRemaining: Int,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ): RecognitionListener = object : RecognitionListener {
        private var lastPartialText = ""

        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionId != activeSessionId.get()) return
            startedAtMs = System.currentTimeMillis()
            _state.update {
                it.copy(
                    isStarting = false,
                    isListening = true,
                    partialTranscript = "",
                    durationMs = 0L,
                    lastErrorCode = null,
                    canRetry = false,
                    error = null
                )
            }
            startTimers(sessionId, language, onResult, onError)
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            if (sessionId != activeSessionId.get()) return
            val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _state.update { it.copy(rmsNormalized = norm) }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            if (sessionId != activeSessionId.get()) return
            stopTimers()
            _state.update { it.copy(isListening = false, isStarting = false, rmsNormalized = 0f) }
        }

        override fun onLanguageDetection(results: Bundle) {
            if (sessionId != activeSessionId.get()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val detectedTag = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
                    ?: results.getStringArrayList(SpeechRecognizer.TOP_LOCALE_ALTERNATIVES)?.firstOrNull()
                if (!detectedTag.isNullOrBlank()) {
                    val mapped = appLanguageFromBcp47(detectedTag)
                    _state.update {
                        it.copy(
                            detectedSpokenLocale = detectedTag,
                            detectedAppLanguage = mapped ?: it.detectedAppLanguage
                        )
                    }
                }
            }
        }

        override fun onError(error: Int) {
            if (sessionId != activeSessionId.get()) return
            stopTimers()

            if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED &&
                spec.mode == RecognizerBackendMode.ON_DEVICE
            ) {
                markOnDeviceTemporarilyDisqualified()
            }

            if (error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS) {
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            }

            // Bounded exponential backoff fallback for binder disconnect / unsupported check / language pack unavailable
            val canFallbackBackend = error in FALLBACK_ELIGIBLE_ERRORS &&
                attemptIndex + 1 < attempts.size

            if (canFallbackBackend) {
                destroyRecognizerInternal()
                _state.update {
                    it.copy(
                        isStarting = true,
                        isListening = false,
                        rmsNormalized = 0f,
                        missingPackWarning = if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                            "Primary speech service disconnected (Error 11); switching to fallback recognition service…"
                        } else {
                            it.missingPackWarning
                        }
                    )
                }
                val backoffDelay = FALLBACK_RETRY_DELAY_MS * (1L shl attemptIndex.coerceAtMost(2))
                scope.launch {
                    delay(backoffDelay)
                    if (sessionId == activeSessionId.get()) {
                        launchAttempt(
                            sessionId = sessionId,
                            language = language,
                            localeTag = localeTag,
                            attempts = attempts,
                            attemptIndex = attemptIndex + 1,
                            busyRetriesRemaining = busyRetriesRemaining,
                            onResult = onResult,
                            onError = onError
                        )
                    }
                }
                return
            }

            // Bounded 1-shot retry for transient busy / audio / network errors
            if (error in TRANSIENT_RETRYABLE_ERRORS && busyRetriesRemaining > 0) {
                destroyRecognizerInternal()
                scope.launch {
                    delay(BUSY_RETRY_DELAY_MS)
                    if (sessionId == activeSessionId.get()) {
                        launchAttempt(
                            sessionId = sessionId,
                            language = language,
                            localeTag = localeTag,
                            attempts = attempts,
                            attemptIndex = attemptIndex,
                            busyRetriesRemaining = busyRetriesRemaining - 1,
                            onResult = onResult,
                            onError = onError
                        )
                    }
                }
                return
            }

            // If timeout/no-match occurred after user spoke valid partial words, rescue them cleanly
            if ((error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) &&
                lastPartialText.trim().length >= 3
            ) {
                val rescued = deduplicateTranscript(lastPartialText)
                val detected = LanguageDetector.detect(rescued, language)
                activeSessionId.incrementAndGet()
                destroyRecognizerInternal()
                _state.update {
                    it.copy(
                        isStarting = false,
                        isListening = false,
                        rmsNormalized = 0f,
                        partialTranscript = rescued,
                        detectedAppLanguage = it.detectedAppLanguage ?: detected.primaryLanguage,
                        lastErrorCode = null,
                        error = null
                    )
                }
                onResult(rescued)
                return
            }

            activeSessionId.incrementAndGet()
            destroyRecognizerInternal()
            val msg = mapSpeechError(error, language)
            val retryable = error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            _state.update {
                it.copy(
                    isStarting = false,
                    isListening = false,
                    rmsNormalized = 0f,
                    lastErrorCode = error,
                    canRetry = retryable,
                    error = msg,
                    isOfflineRecognitionSupported = if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        false
                    } else {
                        it.isOfflineRecognitionSupported
                    }
                )
            }
            onError(msg)
        }

        override fun onResults(results: Bundle?) {
            if (sessionId != activeSessionId.get()) return
            stopTimers()
            activeSessionId.incrementAndGet()

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val transcript = pickBestTranscript(matches, scores).ifBlank {
                deduplicateTranscript(lastPartialText)
            }

            destroyRecognizerInternal()

            if (transcript.isNotBlank()) {
                val detected = LanguageDetector.detect(transcript, language)
                _state.update {
                    it.copy(
                        isStarting = false,
                        isListening = false,
                        rmsNormalized = 0f,
                        partialTranscript = transcript,
                        detectedAppLanguage = it.detectedAppLanguage ?: detected.primaryLanguage,
                        lastErrorCode = null,
                        canRetry = false,
                        error = null
                    )
                }
                onResult(transcript)
            } else {
                val msg = mapSpeechError(SpeechRecognizer.ERROR_NO_MATCH, language)
                _state.update {
                    it.copy(
                        isStarting = false,
                        isListening = false,
                        rmsNormalized = 0f,
                        lastErrorCode = SpeechRecognizer.ERROR_NO_MATCH,
                        canRetry = true,
                        error = msg
                    )
                }
                onError(msg)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionId != activeSessionId.get()) return
            val incoming = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (incoming.isBlank()) return

            val stabilized = stabilizePartialTranscript(lastPartialText, incoming)
            if (stabilized.isNotBlank()) {
                lastPartialText = stabilized
                val detected = LanguageDetector.detect(stabilized, language)
                _state.update {
                    it.copy(
                        partialTranscript = stabilized,
                        detectedAppLanguage = it.detectedAppLanguage ?: detected.primaryLanguage
                    )
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    internal fun buildRecognizeIntent(
        language: SpeechLanguageOption,
        localeTag: String,
        preferOffline: Boolean
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, localeTag)
        putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // Balanced pause & endpointing thresholds for mixed-language speaking speeds
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            val allowedLocales = if (language == SpeechLanguageOption.AUTO) {
                arrayListOf("en-IN", "en-US", "te-IN", "hi-IN", "ta-IN", "kn-IN", "ml-IN")
            } else {
                arrayListOf(localeTag, "en-IN", "en-US").distinct().let(::ArrayList)
            }
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                allowedLocales
            )
            putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                allowedLocales
            )
        }
    }

    private fun buildSupportCheckIntent(localeTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

    fun triggerLanguageModelDownload(language: SpeechLanguageOption): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val targetLocale = resolveLocaleTag(language)
        runCatching { activeDownloadRecognizer?.destroy() }
        activeDownloadRecognizer = null
        val downloader = when {
            factory.isOnDeviceRecognitionAvailable(appContext) && !isOnDeviceDisqualified() ->
                factory.createOnDeviceRecognizer(appContext)
            else -> factory.createDefaultRecognizer(appContext)
        } ?: return false
        activeDownloadRecognizer = downloader

        val intent = buildSupportCheckIntent(targetLocale)
        val listener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            createModelDownloadListenerApi34(targetLocale, language, downloader)
        } else {
            null
        }

        val launched = runCatching {
            downloader.triggerModelDownload(intent, mainExecutor, listener)
        }.getOrDefault(false)

        if (!launched) {
            runCatching { downloader.destroy() }
            if (activeDownloadRecognizer === downloader) activeDownloadRecognizer = null
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            _state.update { it.copy(modelDownloadStatus = "Requested system download for $targetLocale voice model.") }
            scope.launch {
                delay(1_500L)
                runCatching { downloader.destroy() }
                if (activeDownloadRecognizer === downloader) activeDownloadRecognizer = null
            }
        }
        return launched
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun createModelDownloadListenerApi34(
        targetLocale: String,
        language: SpeechLanguageOption,
        downloader: SpeechRecognizerHandle
    ): ModelDownloadListener = object : ModelDownloadListener {
        override fun onProgress(completedPercent: Int) {
            _state.update { it.copy(modelDownloadStatus = "Downloading $targetLocale voice model: $completedPercent%") }
        }

        override fun onSuccess() {
            runCatching { downloader.destroy() }
            if (activeDownloadRecognizer === downloader) activeDownloadRecognizer = null
            _state.update { it.copy(modelDownloadStatus = "$targetLocale offline speech model installed.") }
            refreshCapabilitiesAsync(language)
        }

        override fun onScheduled() {
            _state.update { it.copy(modelDownloadStatus = "$targetLocale voice model download scheduled by system.") }
        }

        override fun onError(error: Int) {
            runCatching { downloader.destroy() }
            if (activeDownloadRecognizer === downloader) activeDownloadRecognizer = null
            _state.update {
                it.copy(
                    modelDownloadStatus = "System download unavailable (code $error); open Voice Input Settings to install $targetLocale."
                )
            }
        }
    }

    fun resolveLocaleTag(language: SpeechLanguageOption): String = when (language) {
        SpeechLanguageOption.AUTO -> {
            val sysTag = Locale.getDefault().toLanguageTag()
            when {
                sysTag.startsWith("te", ignoreCase = true) -> "te-IN"
                sysTag.startsWith("hi", ignoreCase = true) -> "hi-IN"
                sysTag.startsWith("ta", ignoreCase = true) -> "ta-IN"
                sysTag.startsWith("kn", ignoreCase = true) -> "kn-IN"
                sysTag.startsWith("ml", ignoreCase = true) -> "ml-IN"
                sysTag.equals("en-US", ignoreCase = true) -> "en-US"
                else -> "en-IN"
            }
        }
        else -> language.code
    }

    internal fun appLanguageFromBcp47(tag: String): AppLanguage? = when {
        tag.startsWith("te", ignoreCase = true) -> AppLanguage.TELUGU
        tag.startsWith("hi", ignoreCase = true) -> AppLanguage.HINDI
        tag.startsWith("ta", ignoreCase = true) -> AppLanguage.TAMIL
        tag.startsWith("kn", ignoreCase = true) -> AppLanguage.KANNADA
        tag.startsWith("ml", ignoreCase = true) -> AppLanguage.MALAYALAM
        tag.startsWith("en", ignoreCase = true) -> AppLanguage.ENGLISH
        else -> null
    }

    internal fun stabilizePartialTranscript(previous: String, incoming: String): String {
        val cleaned = deduplicateTranscript(incoming)
        if (cleaned.isBlank()) return previous
        // Suppress regressive jitter where recognizer flickers to a 1-char truncated buffer
        if (previous.length > 6 && cleaned.length <= 2) return previous
        return cleaned
    }

    internal fun deduplicateTranscript(raw: String): String {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return ""

        val words = normalized.split(" ")
        if (words.size >= 4 && words.size % 2 == 0) {
            val half = words.size / 2
            val firstHalf = words.subList(0, half).joinToString(" ")
            val secondHalf = words.subList(half, words.size).joinToString(" ")
            if (firstHalf.equals(secondHalf, ignoreCase = true)) {
                return firstHalf
            }
        }

        // Collapse accidental consecutive duplicate n-grams inside streamed partials
        val result = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            if (i + 1 < words.size && words[i].equals(words[i + 1], ignoreCase = true)) {
                result.add(words[i])
                i += 2
            } else {
                result.add(words[i])
                i += 1
            }
        }
        return result.joinToString(" ").trim()
    }

    internal fun pickBestTranscript(
        candidates: List<String>,
        confidenceScores: FloatArray? = null
    ): String {
        val clean = candidates
            .mapIndexedNotNull { idx, text ->
                val dedup = deduplicateTranscript(text)
                if (dedup.isBlank()) null else idx to dedup
            }
        if (clean.isEmpty()) return ""

        if (confidenceScores != null && confidenceScores.isNotEmpty()) {
            val ranked = clean.maxByOrNull { (idx, _) ->
                if (idx < confidenceScores.size && confidenceScores[idx] >= 0f) {
                    confidenceScores[idx]
                } else {
                    0f
                }
            }
            if (ranked != null) return ranked.second
        }

        // Fallback: prefer candidate containing native Indic script or richer multi-word content
        return clean.maxByOrNull { (idx, text) ->
            val hasIndic = text.any { ch -> ch.code in 0x0900..0x0D7F }
            val orderBonus = (clean.size - idx) * 2
            val scriptBonus = if (hasIndic) 4 else 0
            orderBonus + scriptBonus + text.split(" ").size
        }?.second ?: clean.first().second
    }

    fun stopListening() {
        runOnMain {
            runCatching { recognizer?.stopListening() }
            stopTimers()
            _state.update { it.copy(isListening = false, isStarting = false, rmsNormalized = 0f) }
        }
    }

    fun cancelListening() {
        activeSessionId.incrementAndGet()
        runOnMain {
            runCatching { recognizer?.cancel() }
            destroyRecognizerInternal()
            stopTimers()
            _state.update {
                it.copy(
                    isListening = false,
                    isStarting = false,
                    rmsNormalized = 0f,
                    partialTranscript = ""
                )
            }
        }
    }

    private fun destroyRecognizerInternal() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun startTimers(
        sessionId: Long,
        language: SpeechLanguageOption,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (sessionId == activeSessionId.get() && _state.value.isListening) {
                _state.update { it.copy(durationMs = System.currentTimeMillis() - startedAtMs) }
                delay(100L)
            }
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RECOGNITION_HARD_TIMEOUT_MS)
            if (sessionId == activeSessionId.get() && _state.value.isListening) {
                val partial = deduplicateTranscript(_state.value.partialTranscript)
                cancelListening()
                if (partial.length >= 3) {
                    _state.update { it.copy(partialTranscript = partial, error = null) }
                    onResult(partial)
                } else {
                    val msg = "Voice capture timed out for ${language.displayName}. Please retry or type your command."
                    _state.update {
                        it.copy(
                            lastErrorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            canRetry = true,
                            error = msg
                        )
                    }
                    onError(msg)
                }
            }
        }
    }

    private fun stopTimers() {
        timerJob?.cancel()
        timeoutJob?.cancel()
    }

    internal fun mapSpeechError(code: Int, language: SpeechLanguageOption): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO ->
            "Microphone audio capture failed. Ensure no other app is using the mic and retry."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required to use voice commands."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs either an installed offline pack for ${language.displayName} or an active internet connection."
        SpeechRecognizer.ERROR_SERVER ->
            "Speech recognition server returned an error. Tap Retry or use text input."
        SpeechRecognizer.ERROR_CLIENT ->
            "Speech recognizer client could not start cleanly. Tap Retry to reinitialize."
        SpeechRecognizer.ERROR_NO_MATCH ->
            "Could not understand speech clearly. Speak a bit closer to the mic or type your command."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Speech recognizer is still wrapping up the previous session; please tap Retry."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech detected before timeout. Tap the Orb and start speaking."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Speech service temporarily rate-limited requests (Error 10). Wait a few seconds and tap Retry."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "Speech Recognition service disconnected (Error 11). Open Voice Settings to verify Google Speech Services / ${language.displayName} pack, or retry."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "${language.displayName} speech pack is not available offline. Download it in Voice Settings or switch to a supported language."
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
            "Speech service could not check offline language support (Error 14). Recognition may still work via Platform Service or text input."
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
            "System could not attach download progress listener (Error 15). Open Voice Input Settings manually."
        else ->
            "Speech recognition error (code $code). Tap Retry or use text input."
    }

    fun shutdown() {
        activeSessionId.incrementAndGet()
        cancelListening()
        runOnMain {
            runCatching { supportChecker?.destroy() }
            supportChecker = null
            runCatching { activeDownloadRecognizer?.destroy() }
            activeDownloadRecognizer = null
        }
        scope.cancel()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val RECOGNITION_HARD_TIMEOUT_MS = 22_000L
        private const val SUPPORT_CHECK_TIMEOUT_MS = 3_500L
        private const val REBIND_SETTLE_DELAY_MS = 120L
        private const val FALLBACK_RETRY_DELAY_MS = 240L
        private const val BUSY_RETRY_DELAY_MS = 300L
        private const val RATE_LIMIT_COOLDOWN_MS = 4_000L
        private const val ON_DEVICE_COOLDOWN_MS = 120_000L
        private const val MAX_ATTEMPT_COUNT = 3

        private val FALLBACK_ELIGIBLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_CLIENT
        )

        private val TRANSIENT_RETRYABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        )
    }
}
