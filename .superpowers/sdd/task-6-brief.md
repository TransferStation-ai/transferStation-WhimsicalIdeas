### 任务 6：修改 AiConfigScreen — 添加语音设置区

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/AiConfigScreen.java`

在 AI 配置界面底部添加语音输入设置区域（启用/禁用、自动发送、下载模型、测试麦克风）。

- [ ] **步骤 1：在 init() 方法末尾添加语音设置控件**

在 `init()` 方法的末尾（`clear_history` 按钮之后）添加：

```java
        // ──── Voice Input Section ────
        y += 16;
        y += 16;
        // Enable voice toggle
        var voiceToggle = addRenderableWidget(Button.builder(
            Component.translatable(
                VoiceConfig.isEnabled()
                    ? "gui.transferstation_whimsicalideas.voice_enabled"
                    : "gui.transferstation_whimsicalideas.voice_disabled"),
            btn -> {
                VoiceConfig.setEnabled(!VoiceConfig.isEnabled());
                btn.setMessage(Component.translatable(
                    VoiceConfig.isEnabled()
                        ? "gui.transferstation_whimsicalideas.voice_enabled"
                        : "gui.transferstation_whimsicalideas.voice_disabled"));
            }
        ).pos(cx - 50, y).size(280, 18).build());

        y += 22;
        // Auto-send toggle
        var autoSendToggle = addRenderableWidget(Button.builder(
            Component.translatable(
                VoiceConfig.isAutoSend()
                    ? "gui.transferstation_whimsicalideas.voice_autosend_on"
                    : "gui.transferstation_whimsicalideas.voice_autosend_off"),
            btn -> {
                VoiceConfig.setAutoSend(!VoiceConfig.isAutoSend());
                btn.setMessage(Component.translatable(
                    VoiceConfig.isAutoSend()
                        ? "gui.transferstation_whimsicalideas.voice_autosend_on"
                        : "gui.transferstation_whimsicalideas.voice_autosend_off"));
            }
        ).pos(cx - 50, y).size(280, 18).build());

        y += 22;
        // Download model button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.voice_download_model"),
            btn -> {
                btn.active = false;
                btn.setMessage(Component.literal("§e下载中..."));
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    boolean ok = VoiceConfig.downloadDefaultModel();
                    net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        if (ok) {
                            btn.setMessage(Component.literal("§a下载完成"));
                            statusMessage = net.minecraft.network.chat.Component.translatable(
                                "gui.transferstation_whimsicalideas.voice_download_done").getString();
                            statusTimer = 80;
                            statusType = StatusType.SUCCESS;
                            VoskSttEngine.initialize()
                                .thenRunAsync(() -> {}, net.minecraft.client.Minecraft.getInstance());
                        } else {
                            btn.setMessage(Component.translatable(
                                "gui.transferstation_whimsicalideas.voice_download_model"));
                            btn.active = true;
                            statusMessage = net.minecraft.network.chat.Component.translatable(
                                "gui.transferstation_whimsicalideas.voice_download_failed").getString();
                            statusTimer = 100;
                            statusType = StatusType.ERROR;
                        }
                    });
                });
            }
        ).pos(cx - 50, y).size(280, 18).build());

        y += 22;
        // Test mic button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.voice_test_mic"),
            btn -> {
                if (VoiceCaptureService.isRecording()) {
                    VoiceCaptureService.stopRecording();
                    btn.setMessage(Component.translatable("gui.transferstation_whimsicalideas.voice_test_mic"));
                    statusMessage = net.minecraft.network.chat.Component.translatable(
                        "gui.transferstation_whimsicalideas.voice_test_done").getString();
                    statusTimer = 60;
                    statusType = StatusType.SUCCESS;
                } else {
                    VoiceCaptureService.startRecording(data -> {
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            statusMessage = net.minecraft.network.chat.Component.translatable(
                                "gui.transferstation_whimsicalideas.voice_test_received",
                                data.length).getString();
                            statusTimer = 80;
                            statusType = StatusType.SUCCESS;
                        });
                    });
                    btn.setMessage(Component.literal("§c停止测试"));
                    statusMessage = net.minecraft.network.chat.Component.translatable(
                        "gui.transferstation_whimsicalideas.voice_test_recording").getString();
                    statusTimer = 0;
                    statusType = StatusType.INFO;
                }
            }
        ).pos(cx - 50, y).size(280, 18).build());
```

- [ ] **步骤 2：在 onClose() 中保存配置**

在 `onClose()` 方法末尾（`minecraft.setScreen(null);` 之前）添加：

```java
        VoiceConfig.save();
```

- [ ] **步骤 3：添加 imports**

确保文件顶部有这些 import（如果缺失则添加）：

```java
import transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService;
import transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig;
import transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine;
```

- [ ] **步骤 4：验证编译**

运行：`.\gradlew compileJava`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/AiConfigScreen.java
git commit -m "feat(voice): add voice settings to AiConfigScreen"
```
