### 任务 8：添加翻译键值

**文件：**
- 修改：`src/main/resources/assets/transferstation_whimsicalideas/lang/zh_cn.json`
- 修改：`src/main/resources/assets/transferstation_whimsicalideas/lang/en_us.json`

- [ ] **步骤 1：添加到 zh_cn.json**

在 `zh_cn.json` 末尾添加：

```json

    "gui.transferstation_whimsicalideas.voice_section": "语音输入",
    "gui.transferstation_whimsicalideas.voice_enabled": "语音输入：已启用",
    "gui.transferstation_whimsicalideas.voice_disabled": "语音输入：已禁用",
    "gui.transferstation_whimsicalideas.voice_autosend_on": "自动发送：开",
    "gui.transferstation_whimsicalideas.voice_autosend_off": "自动发送：关",
    "gui.transferstation_whimsicalideas.voice_download_model": "下载语音模型 (~42MB)",
    "gui.transferstation_whimsicalideas.voice_download_done": "模型下载完成，请重新打开聊天界面",
    "gui.transferstation_whimsicalideas.voice_download_failed": "模型下载失败，请检查网络后重试",
    "gui.transferstation_whimsicalideas.voice_test_mic": "测试麦克风",
    "gui.transferstation_whimsicalideas.voice_test_recording": "录音中... 点击停止",
    "gui.transferstation_whimsicalideas.voice_test_done": "麦克风测试完成",
    "gui.transferstation_whimsicalideas.voice_test_received": "已收到 %d 字节音频数据"
```

- [ ] **步骤 2：添加到 en_us.json**

在 `en_us.json` 末尾添加：

```json

    "gui.transferstation_whimsicalideas.voice_section": "Voice Input",
    "gui.transferstation_whimsicalideas.voice_enabled": "Voice Input: ENABLED",
    "gui.transferstation_whimsicalideas.voice_disabled": "Voice Input: DISABLED",
    "gui.transferstation_whimsicalideas.voice_autosend_on": "Auto-Send: ON",
    "gui.transferstation_whimsicalideas.voice_autosend_off": "Auto-Send: OFF",
    "gui.transferstation_whimsicalideas.voice_download_model": "Download Voice Model (~42MB)",
    "gui.transferstation_whimsicalideas.voice_download_done": "Model downloaded. Reopen chat to use voice input.",
    "gui.transferstation_whimsicalideas.voice_download_failed": "Download failed. Check your network and try again.",
    "gui.transferstation_whimsicalideas.voice_test_mic": "Test Microphone",
    "gui.transferstation_whimsicalideas.voice_test_recording": "Recording... Click to stop",
    "gui.transferstation_whimsicalideas.voice_test_done": "Microphone test complete",
    "gui.transferstation_whimsicalideas.voice_test_received": "Received %d bytes of audio data"
```

- [ ] **步骤 3：Commit**

```bash
git add src/main/resources/assets/transferstation_whimsicalideas/lang/
git commit -m "feat(voice): add voice input translations"
```
