package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.SpeechLanguageOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.commanderDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "commander_settings"
)

data class CommanderSettings(
    val wakeWordEnabled: Boolean = false,
    val wakeWordSensitivity: Float = 0.65f,
    val activationSoundEnabled: Boolean = true,
    val voiceResponseEnabled: Boolean = true,
    val continuousListeningEnabled: Boolean = false,
    val speechSpeed: Float = 1.0f,
    val selectedTtsVoice: String = "",
    val speechLanguage: SpeechLanguageOption = SpeechLanguageOption.AUTO,
    val commandOnlyMode: Boolean = false,
    val kwsModelPath: String = "",
    val requestedPermissions: Set<String> = emptySet(),
    val memoryInjectionEnabled: Boolean = true,
    val notificationIntelligenceEnabled: Boolean = false,
    val notificationBlockedPackages: Set<String> = DEFAULT_BLOCKED_NOTIFICATION_PACKAGES,
    val notificationAllowedPackages: Set<String> = emptySet(),
    val offlineStrictMode: Boolean = false,
    val developerModeEnabled: Boolean = false,
    val primarySpeakerOnlyEnabled: Boolean = false,
    val speakerVerificationThreshold: Float = 0.72f,
    val primarySpeakerEmbeddingJson: String = "",
    val primarySpeakerSamplesCount: Int = 0
) {
    val hasPrimarySpeakerProfile: Boolean
        get() = primarySpeakerEmbeddingJson.isNotBlank() && primarySpeakerSamplesCount > 0

    companion object {
        val DEFAULT_BLOCKED_NOTIFICATION_PACKAGES = setOf(
            "com.android.systemui",
            "android"
        )
    }
}

class CommanderPreferences(private val context: Context) {
    private object Keys {
        val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val WAKE_WORD_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        val ACTIVATION_SOUND_ENABLED = booleanPreferencesKey("activation_sound_enabled")
        val VOICE_RESPONSE_ENABLED = booleanPreferencesKey("voice_response_enabled")
        val CONTINUOUS_LISTENING = booleanPreferencesKey("continuous_listening")
        val SPEECH_SPEED = floatPreferencesKey("speech_speed")
        val SELECTED_TTS_VOICE = stringPreferencesKey("selected_tts_voice")
        val SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val COMMAND_ONLY_MODE = booleanPreferencesKey("command_only_mode")
        val KWS_MODEL_PATH = stringPreferencesKey("kws_model_path")
        val AUTHORIZED_DOCUMENTS_JSON = stringPreferencesKey("authorized_documents_json")
        val REQUESTED_PERMISSIONS_JSON = stringPreferencesKey("requested_permissions_json")
        val MEMORY_INJECTION_ENABLED = booleanPreferencesKey("memory_injection_enabled")
        val NOTIFICATION_INTELLIGENCE_ENABLED = booleanPreferencesKey("notification_intelligence_enabled")
        val NOTIFICATION_BLOCKED_PACKAGES = stringPreferencesKey("notification_blocked_packages")
        val NOTIFICATION_ALLOWED_PACKAGES = stringPreferencesKey("notification_allowed_packages")
        val OFFLINE_STRICT_MODE = booleanPreferencesKey("offline_strict_mode")
        val DEVELOPER_MODE_ENABLED = booleanPreferencesKey("developer_mode_enabled")
        val PRIMARY_SPEAKER_ONLY_ENABLED = booleanPreferencesKey("primary_speaker_only_enabled")
        val SPEAKER_VERIFICATION_THRESHOLD = floatPreferencesKey("speaker_verification_threshold")
        val PRIMARY_SPEAKER_EMBEDDING_JSON = stringPreferencesKey("primary_speaker_embedding_json")
        val PRIMARY_SPEAKER_SAMPLES_COUNT = intPreferencesKey("primary_speaker_samples_count")
    }

    val authorizedDocumentsJson: Flow<String> = context.commanderDataStore.data.map { prefs ->
        prefs[Keys.AUTHORIZED_DOCUMENTS_JSON] ?: ""
    }

    val settings: Flow<CommanderSettings> = context.commanderDataStore.data.map { prefs ->
        val langCode = prefs[Keys.SPEECH_LANGUAGE] ?: SpeechLanguageOption.AUTO.code
        val langOption = SpeechLanguageOption.entries.firstOrNull { it.code == langCode }
            ?: SpeechLanguageOption.AUTO
        val rawReqPerms = prefs[Keys.REQUESTED_PERMISSIONS_JSON].orEmpty()
        val reqPerms = if (rawReqPerms.isBlank()) emptySet() else rawReqPerms.split("|").filter { it.isNotBlank() }.toSet()
        val rawBlocked = prefs[Keys.NOTIFICATION_BLOCKED_PACKAGES]
        val blockedSet = if (rawBlocked == null) {
            CommanderSettings.DEFAULT_BLOCKED_NOTIFICATION_PACKAGES
        } else {
            rawBlocked.split("|").filter { it.isNotBlank() }.toSet()
        }
        val rawAllowed = prefs[Keys.NOTIFICATION_ALLOWED_PACKAGES].orEmpty()
        val allowedSet = if (rawAllowed.isBlank()) emptySet() else rawAllowed.split("|").filter { it.isNotBlank() }.toSet()

        CommanderSettings(
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
            wakeWordSensitivity = prefs[Keys.WAKE_WORD_SENSITIVITY] ?: 0.65f,
            activationSoundEnabled = prefs[Keys.ACTIVATION_SOUND_ENABLED] ?: true,
            voiceResponseEnabled = prefs[Keys.VOICE_RESPONSE_ENABLED] ?: true,
            continuousListeningEnabled = prefs[Keys.CONTINUOUS_LISTENING] ?: false,
            speechSpeed = prefs[Keys.SPEECH_SPEED] ?: 1.0f,
            selectedTtsVoice = prefs[Keys.SELECTED_TTS_VOICE] ?: "",
            speechLanguage = langOption,
            commandOnlyMode = prefs[Keys.COMMAND_ONLY_MODE] ?: false,
            kwsModelPath = prefs[Keys.KWS_MODEL_PATH] ?: "",
            requestedPermissions = reqPerms,
            memoryInjectionEnabled = prefs[Keys.MEMORY_INJECTION_ENABLED] ?: true,
            notificationIntelligenceEnabled = prefs[Keys.NOTIFICATION_INTELLIGENCE_ENABLED] ?: false,
            notificationBlockedPackages = blockedSet,
            notificationAllowedPackages = allowedSet,
            offlineStrictMode = prefs[Keys.OFFLINE_STRICT_MODE] ?: false,
            developerModeEnabled = prefs[Keys.DEVELOPER_MODE_ENABLED] ?: false,
            primarySpeakerOnlyEnabled = prefs[Keys.PRIMARY_SPEAKER_ONLY_ENABLED] ?: false,
            speakerVerificationThreshold = prefs[Keys.SPEAKER_VERIFICATION_THRESHOLD] ?: 0.72f,
            primarySpeakerEmbeddingJson = prefs[Keys.PRIMARY_SPEAKER_EMBEDDING_JSON] ?: "",
            primarySpeakerSamplesCount = prefs[Keys.PRIMARY_SPEAKER_SAMPLES_COUNT] ?: 0
        )
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    suspend fun setWakeWordSensitivity(value: Float) {
        context.commanderDataStore.edit { it[Keys.WAKE_WORD_SENSITIVITY] = value.coerceIn(0.2f, 0.95f) }
    }

    suspend fun setActivationSoundEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.ACTIVATION_SOUND_ENABLED] = enabled }
    }

    suspend fun setVoiceResponseEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.VOICE_RESPONSE_ENABLED] = enabled }
    }

    suspend fun setContinuousListeningEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.CONTINUOUS_LISTENING] = enabled }
    }

    suspend fun setSpeechSpeed(speed: Float) {
        context.commanderDataStore.edit { it[Keys.SPEECH_SPEED] = speed.coerceIn(0.6f, 1.8f) }
    }

    suspend fun setSelectedTtsVoice(voiceName: String) {
        context.commanderDataStore.edit { it[Keys.SELECTED_TTS_VOICE] = voiceName }
    }

    suspend fun setSpeechLanguage(option: SpeechLanguageOption) {
        context.commanderDataStore.edit { it[Keys.SPEECH_LANGUAGE] = option.code }
    }

    suspend fun setCommandOnlyMode(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.COMMAND_ONLY_MODE] = enabled }
    }

    suspend fun setKwsModelPath(path: String) {
        context.commanderDataStore.edit { it[Keys.KWS_MODEL_PATH] = path }
    }

    suspend fun setAuthorizedDocumentsJson(json: String) {
        context.commanderDataStore.edit { it[Keys.AUTHORIZED_DOCUMENTS_JSON] = json }
    }

    suspend fun setMemoryInjectionEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.MEMORY_INJECTION_ENABLED] = enabled }
    }

    suspend fun setNotificationIntelligenceEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.NOTIFICATION_INTELLIGENCE_ENABLED] = enabled }
    }

    suspend fun setNotificationBlockedPackages(packages: Set<String>) {
        context.commanderDataStore.edit {
            it[Keys.NOTIFICATION_BLOCKED_PACKAGES] = packages.filter { pkg -> pkg.isNotBlank() }.joinToString("|")
        }
    }

    suspend fun setNotificationAllowedPackages(packages: Set<String>) {
        context.commanderDataStore.edit {
            it[Keys.NOTIFICATION_ALLOWED_PACKAGES] = packages.filter { pkg -> pkg.isNotBlank() }.joinToString("|")
        }
    }

    suspend fun setOfflineStrictMode(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.OFFLINE_STRICT_MODE] = enabled }
    }

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.DEVELOPER_MODE_ENABLED] = enabled }
    }

    suspend fun setPrimarySpeakerOnlyEnabled(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.PRIMARY_SPEAKER_ONLY_ENABLED] = enabled }
    }

    suspend fun setSpeakerVerificationThreshold(threshold: Float) {
        context.commanderDataStore.edit { it[Keys.SPEAKER_VERIFICATION_THRESHOLD] = threshold.coerceIn(0.55f, 0.92f) }
    }

    suspend fun savePrimarySpeakerProfile(embeddingJson: String, samplesCount: Int) {
        context.commanderDataStore.edit {
            it[Keys.PRIMARY_SPEAKER_EMBEDDING_JSON] = embeddingJson
            it[Keys.PRIMARY_SPEAKER_SAMPLES_COUNT] = samplesCount
        }
    }

    suspend fun clearPrimarySpeakerProfile() {
        context.commanderDataStore.edit {
            it[Keys.PRIMARY_SPEAKER_EMBEDDING_JSON] = ""
            it[Keys.PRIMARY_SPEAKER_SAMPLES_COUNT] = 0
            it[Keys.PRIMARY_SPEAKER_ONLY_ENABLED] = false
        }
    }

    suspend fun markPermissionsRequested(permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        context.commanderDataStore.edit { prefs ->
            val current = prefs[Keys.REQUESTED_PERMISSIONS_JSON].orEmpty()
                .split("|")
                .filter { it.isNotBlank() }
                .toMutableSet()
            current.addAll(permissions)
            prefs[Keys.REQUESTED_PERMISSIONS_JSON] = current.joinToString("|")
        }
    }
}
