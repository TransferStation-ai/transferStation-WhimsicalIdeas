# Task 6 Report — Add voice settings to AiConfigScreen

**Status: DONE**

## Summary
Added voice input configuration controls (enable/disable, auto-send, download model, test microphone) to the bottom of the AI configuration screen.

## Changes Made
- **AiConfigScreen.java**:
  - Added imports for `VoiceCaptureService`, `VoiceConfig`, `VoskSttEngine`
  - Added voice section UI controls in `init()` after the `clear_history` button:
    - Voice enable/disable toggle button
    - Auto-send toggle button
    - Download model button (async with status feedback)
    - Test microphone button (start/stop recording with status feedback)
  - Added `VoiceConfig.save();` call in `onClose()` before `minecraft.setScreen(null)`

## Verification
- **Compilation**: BUILD SUCCESSFUL (only pre-existing deprecation warnings about `ResourceLocation`, unrelated to this change)
- **Commit**: `feat(voice): add voice settings to AiConfigScreen` — 1 file changed, 101 insertions(+)
