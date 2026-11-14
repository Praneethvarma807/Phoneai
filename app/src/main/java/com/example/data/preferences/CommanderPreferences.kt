package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val preferredBackend: String = "CPU",
    val commandOnlyMode: Boolean = false,
    val kwsModelPath: String = ""
)

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
        val PREFERRED_BACKEND = stringPreferencesKey("preferred_backend")
        val COMMAND_ONLY_MODE = booleanPreferencesKey("command_only_mode")
        val KWS_MODEL_PATH = stringPreferencesKey("kws_model_path")
    }

    val settings: Flow<CommanderSettings> = context.commanderDataStore.data.map { prefs ->
        val langCode = prefs[Keys.SPEECH_LANGUAGE] ?: SpeechLanguageOption.AUTO.code
        val langOption = SpeechLanguageOption.entries.firstOrNull { it.code == langCode }
            ?: SpeechLanguageOption.AUTO
        CommanderSettings(
            wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
            wakeWordSensitivity = prefs[Keys.WAKE_WORD_SENSITIVITY] ?: 0.65f,
            activationSoundEnabled = prefs[Keys.ACTIVATION_SOUND_ENABLED] ?: true,
            voiceResponseEnabled = prefs[Keys.VOICE_RESPONSE_ENABLED] ?: true,
            continuousListeningEnabled = prefs[Keys.CONTINUOUS_LISTENING] ?: false,
            speechSpeed = prefs[Keys.SPEECH_SPEED] ?: 1.0f,
            selectedTtsVoice = prefs[Keys.SELECTED_TTS_VOICE] ?: "",
            speechLanguage = langOption,
            preferredBackend = prefs[Keys.PREFERRED_BACKEND] ?: "CPU",
            commandOnlyMode = prefs[Keys.COMMAND_ONLY_MODE] ?: false,
            kwsModelPath = prefs[Keys.KWS_MODEL_PATH] ?: ""
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

    suspend fun setPreferredBackend(backend: String) {
        context.commanderDataStore.edit { it[Keys.PREFERRED_BACKEND] = backend }
    }

    suspend fun setCommandOnlyMode(enabled: Boolean) {
        context.commanderDataStore.edit { it[Keys.COMMAND_ONLY_MODE] = enabled }
    }

    suspend fun setKwsModelPath(path: String) {
        context.commanderDataStore.edit { it[Keys.KWS_MODEL_PATH] = path }
    }
}
