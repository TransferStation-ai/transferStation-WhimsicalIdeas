# Task 4 Report: Create VoskSttEngine

**Status:** DONE

## Summary

Created `VoskSttEngine.java` — a static wrapper around the Vosk Java API (`org.vosk`) providing offline speech-to-text functionality.

## Changes

- **Created** `src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoskSttEngine.java` (131 lines)

## Implementation Details

- Package: `transferstation.transferstation_whimsicalideas.client.voice`
- All methods are static, backed by a single-thread daemon executor (`VoskSTT`)
- `initialize()` — loads Vosk model from `VoiceConfig.getModelPath()` on background thread, returns `CompletableFuture<Boolean>`
- `transcribe(byte[] wavData)` — skips 44-byte WAV header if detected, passes raw PCM to Vosk `Recognizer.acceptWaveForm()`, returns `CompletableFuture<String>`
- `parseVoskResult(String)` — simple string-based JSON parse for `"text"` and `"partial"` fields (no Gson dependency)
- `shutdown()` — shuts down executor and closes model

## Deviation from Brief

The brief specified `acceptWaveform(wavData, offset, wavData.length - offset)` (3 params) and `recognizer.delete()`. The actual Vosk 0.3.45 API uses:
- `acceptWaveForm(byte[], int)` (capital F, 2 params: data, length)
- `close()` instead of `delete()` (implements `AutoCloseable`)

Both were corrected to match the real API.

## Verification

- `.\gradlew compileJava` — **BUILD SUCCESSFUL** (no errors, only pre-existing deprecation warnings in unrelated files)
- **Committed** with message: `feat(voice): add VoskSttEngine for offline STT`
