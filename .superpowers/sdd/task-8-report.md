# Task 8: Add voice input translations

**Status**: DONE

## Summary

Added 12 voice input translation keys to both `zh_cn.json` and `en_us.json` language files.

## Changes

### `src/main/resources/assets/transferstation_whimsicalideas/lang/zh_cn.json`
- Added 12 Chinese translation entries for voice input features:
  - `gui.transferstation_whimsicalideas.voice_section` → "语音输入"
  - `gui.transferstation_whimsicalideas.voice_enabled` → "语音输入：已启用"
  - `gui.transferstation_whimsicalideas.voice_disabled` → "语音输入：已禁用"
  - `gui.transferstation_whimsicalideas.voice_autosend_on` → "自动发送：开"
  - `gui.transferstation_whimsicalideas.voice_autosend_off` → "自动发送：关"
  - `gui.transferstation_whimsicalideas.voice_download_model` → "下载语音模型 (~42MB)"
  - `gui.transferstation_whimsicalideas.voice_download_done` → "模型下载完成，请重新打开聊天界面"
  - `gui.transferstation_whimsicalideas.voice_download_failed` → "模型下载失败，请检查网络后重试"
  - `gui.transferstation_whimsicalideas.voice_test_mic` → "测试麦克风"
  - `gui.transferstation_whimsicalideas.voice_test_recording` → "录音中... 点击停止"
  - `gui.transferstation_whimsicalideas.voice_test_done` → "麦克风测试完成"
  - `gui.transferstation_whimsicalideas.voice_test_received` → "已收到 %d 字节音频数据"

### `src/main/resources/assets/transferstation_whimsicalideas/lang/en_us.json`
- Added 12 English translation entries for voice input features:
  - `gui.transferstation_whimsicalideas.voice_section` → "Voice Input"
  - `gui.transferstation_whimsicalideas.voice_enabled` → "Voice Input: ENABLED"
  - `gui.transferstation_whimsicalideas.voice_disabled` → "Voice Input: DISABLED"
  - `gui.transferstation_whimsicalideas.voice_autosend_on` → "Auto-Send: ON"
  - `gui.transferstation_whimsicalideas.voice_autosend_off` → "Auto-Send: OFF"
  - `gui.transferstation_whimsicalideas.voice_download_model` → "Download Voice Model (~42MB)"
  - `gui.transferstation_whimsicalideas.voice_download_done` → "Model downloaded. Reopen chat to use voice input."
  - `gui.transferstation_whimsicalideas.voice_download_failed` → "Download failed. Check your network and try again."
  - `gui.transferstation_whimsicalideas.voice_test_mic` → "Test Microphone"
  - `gui.transferstation_whimsicalideas.voice_test_recording` → "Recording... Click to stop"
  - `gui.transferstation_whimsicalideas.voice_test_done` → "Microphone test complete"
  - `gui.transferstation_whimsicalideas.voice_test_received` → "Received %d bytes of audio data"

## Verification

- **JSON validation**: Both files validated correctly using `System.Web.Script.Serialization.JavaScriptSerializer`.
- **Trailing comma check**: Last entry in each file has no trailing comma before the closing `}`.
- **Commit**: `ad761e9 - feat(voice): add voice input translations`
