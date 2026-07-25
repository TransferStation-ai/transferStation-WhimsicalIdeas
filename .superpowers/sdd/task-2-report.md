# Task 2 Report: VoiceConfig configuration class

**Status:** DONE

## Summary
Created `VoiceConfig.java` in the `client/voice` package — a static utility/config holder for voice input settings (enabled, model path, language, auto-send) with persistence to `config/transferstation_whimsicalideas/voice.properties` and model download from alphacephei.com.

## Files Changed
- **Created:** `src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoiceConfig.java` (161 lines)

## Verification
- **Compilation:** `.\gradlew compileJava` — BUILD SUCCESSFUL (0 errors, 2 pre-existing warnings unrelated to this change)
- **Commit hash:** `9920a1f`

## Implementation Details
- Uses `FMLPaths.CONFIGDIR` (Forge) to locate config directory
- Default model: `vosk-model-small-cn-0.22` from alphacephei.com (~42MB)
- `load()` / `save()` read/write Properties to `voice.properties`
- `downloadDefaultModel()` downloads zip via `HttpClient`, extracts via `ZipInputStream`, cleans up
- Security: path traversal check in zip extraction (`!target.startsWith(MODEL_DIR)`)
