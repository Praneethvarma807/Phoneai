# AI Phone Commander

A 100% API-key-free, offline-first Android AI phone automation assistant built with Kotlin, Jetpack Compose, Material 3, Room, LiteRT-LM, LiteRT, ML Kit On-Device Text Recognition, Accessibility Service, and MediaProjection.

## Core Architecture

- **Zero Cloud LLM APIs / Zero API Keys**: No Gemini, OpenAI, or OpenRouter API keys. All natural-language planning and screen understanding run locally on-device.
- **Dual-Engine Intelligence**:
  - **Local LLM Engine (`LocalLlmEngine`)**: Runs open-weight `.litertlm` models (`Qwen3-0.6B`, `Gemma 3 1B IT`, `Gemma 4 E2B IT`, or user-imported `.litertlm` files) via `com.google.ai.edge.litertlm:litertlm-android` on a single-thread background executor with strict JSON tool-call parsing.
  - **Deterministic Local Command Engine (`DeterministicCommandEngine`)**: Fast zero-LLM fallback supporting English, Telugu, Hindi, and mixed Telugu-English ("Tenglish") single-step and multi-step commands.
- **Model Manager (`ModelDownloadManager` & `DeviceCompatibilityManager`)**: Inspects device RAM, storage, CPU ABI, and GPU/OpenCL support; downloads public `litert-community` `.litertlm` weights with HTTP Range pause/resume, SHA-256 verification, load/unload, and local SAF import.
- **Dynamic Tool Registry (`ToolRegistry` & `PhoneTools`)**: 23 schema-validated Android tools (`open_app`, `open_url`, `web_search`, `browser_navigate`, `browser_click`, `browser_type`, `browser_scroll`, `get_screen_text`, `analyze_screenshot`, `adjust_brightness`, `adjust_volume`, `open_settings`, `create_reminder`, `create_calendar_event`, `search_contacts`, `compose_sms`, `compose_email`, `send_whatsapp_draft`, `search_files`, `create_note`, `check_app_installed`, `set_alarm`, `open_downloads`).
- **Offline Voice & "Hello Buddy" Wake Word**:
  - `WakeWordForegroundService` (`foregroundServiceType="microphone"`) monitors audio locally in RAM without recording or uploading audio.
  - Supports custom LiteRT `.tflite` KWS models (`LiteRtKwsClassifier`) or dual-stage acoustic VAD + offline `SpeechRecognizer` verification (`WakeWordMatcher`), with sensitivity tuning and explicit notification Stop controls.
- **Screen Understanding (`ScreenCaptureService` & `ScreenUnderstandingManager`)**: Captures a single local frame via `MediaProjection`, extracts text on-device with ML Kit Text Recognition (`com.google.mlkit:text-recognition`), fuses Accessibility nodes when enabled, and summarizes locally.

## Build & Test Instructions

### Build with Android Studio

1. Open this folder in Android Studio (current stable version with Android SDK 36.1 support).
2. Allow Gradle sync to finish and install the Android SDK platform/build-tools requested by the project.
3. Select the `app` run configuration and run it on an Android 8.0+ device (API 26+).

### Build from a terminal

This source archive does not include the Gradle Wrapper scripts/JAR. If Gradle 9.3.1 is installed locally:

```bash
gradle clean testDebugUnitTest assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Installing updated APKs

The package ID is `com.aistudio.aiphonecommander.kxmpzq`. Android only accepts an update when the installed app has the same package ID and signing certificate. If you build from a different machine or a GitHub Actions run without a persistent signing key, uninstall the older Phoneai app first (this deletes its local app data) and then install the new APK.

For repeatable updates from GitHub Actions, add repository secrets `DEBUG_KEYSTORE_BASE64`, `DEBUG_STORE_PASSWORD`, `DEBUG_KEY_ALIAS`, and `DEBUG_KEY_PASSWORD` using one dedicated debug keystore. Never use this debug key to publish a production release.
