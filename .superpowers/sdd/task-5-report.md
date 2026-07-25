# Task 5 Report: Add microphone button to NpcChatScreen

## Status: DONE

## Summary

Implemented voice input UI in `NpcChatScreen.java`:

1. **Imports** — Added `VoiceCaptureService`, `VoiceConfig`, `VoskSttEngine` imports.
2. **Member variables** — Added `voiceModeAvailable`, `isRecording`, `micButton`, `voiceStatusText`, `voiceStatusTimer` after `awaitingReply`.
3. **Mic button in `init()`** — Added a microphone button (`🎤`) positioned left of the input field at `cx - 165`. Buttons disabled if voice service unavailable.
4. **`handleMicPress()`** — Added before `sendMessage()`:
   - Starts recording via `VoiceCaptureService.startRecording()`.
   - Updates mic button to red dot (`🔴`) with "录音中..." status.
   - On recording complete, calls `VoskSttEngine.transcribe()` and shows "识别中...".
   - On transcription result: auto-sends if `VoiceConfig.isAutoSend()`, otherwise just fills input field.
   - On failure: shows "未检测到语音" for 40 ticks.
   - On stop: calls `VoiceCaptureService.stopRecording()`.
5. **Voice timer in `tick()`** — Decrements `voiceStatusTimer`, clears `voiceStatusText` when it reaches 0.
6. **Voice status in `render()`** — Draws centered voice status text below the chat area (`chatBottom + 16`).

## Verification

- **Compilation**: `BUILD SUCCESSFUL` (2 pre-existing deprecation warnings in unrelated files)
- **Commit**: `5d3a243` — `feat(voice): add mic button to NpcChatScreen`
