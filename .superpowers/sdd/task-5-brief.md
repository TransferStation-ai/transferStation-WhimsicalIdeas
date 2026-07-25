### 任务 5：修改 NpcChatScreen — 添加麦克风按钮

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/NpcChatScreen.java`

需要在 NpcChatScreen 中添加：
1. 语音相关的成员变量
2. 麦克风按钮（🎤）在输入框左侧
3. `handleMicPress()` 方法
4. tick() 中的语音状态计时器
5. render() 中的语音状态文字

- [ ] **步骤 1：在类中添加语音相关成员变量**

在 `class NpcChatScreen` 的成员变量区域（`private boolean awaitingReply = false;` 之后）添加：

```java
    // Voice input state
    private boolean voiceModeAvailable = false;
    private boolean isRecording = false;
    private Button micButton;
    private String voiceStatusText = "";
    private int voiceStatusTimer = 0;
```

同时添加 import：

```java
// 在文件顶部导入区，确认已有这些 package 引用：
// transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService
// transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig
// transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine
// 如果编译器报错，使用全限定名
```

- [ ] **步骤 2：在 init() 中添加 Mic 按钮**

在 `init()` 方法中找到发送按钮的创建代码，修改 micButton 和 inputField 位置。

替换旧的发送按钮创建代码区域（`cx + 125, height - 30, 40, 18` 那一段和紧接其后的 inputField 位置）：

找到：
```java
        // Input field at bottom
        inputField = new EditBox(font, cx - 140, height - 30, 260, 18,
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.input_hint"));
        inputField.setMaxLength(INPUT_MAX_LENGTH);
        inputField.setResponder(this::onInputChanged);
        addWidget(inputField);

        // Send button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.send"),
            btn -> sendMessage()
        ).bounds(cx + 125, height - 30, 40, 18).build());
```

替换为：

```java
        // Mic button (to the left of the input field)
        boolean micAvail = VoiceCaptureService.isAvailable()
                && VoiceConfig.isEnabled()
                && VoskSttEngine.isInitialized();
        micButton = addRenderableWidget(Button.builder(
            Component.literal(micAvail ? "🎤" : "§7🎤"),
            btn -> handleMicPress()
        ).bounds(cx - 165, height - 30, 20, 18).build());
        micButton.active = micAvail;
        this.voiceModeAvailable = micAvail;

        // Input field at bottom (shifted right to make room for mic button)
        inputField = new EditBox(font, cx - 140, height - 30, 260, 18,
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.input_hint"));
        inputField.setMaxLength(INPUT_MAX_LENGTH);
        inputField.setResponder(this::onInputChanged);
        addWidget(inputField);

        // Send button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.send"),
            btn -> sendMessage()
        ).bounds(cx + 125, height - 30, 40, 18).build());
```

确保文件顶部有这些 import（如果缺失，用全限定名）：

```java
import transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService;
import transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig;
import transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine;
```

- [ ] **步骤 3：添加 handleMicPress 方法**

在 `sendMessage()` 方法之前添加：

```java
    private void handleMicPress() {
        if (!voiceModeAvailable) return;

        if (!isRecording) {
            // Start recording
            isRecording = true;
            micButton.setMessage(Component.literal("§c🔴"));
            voiceStatusText = "§c录音中...";
            voiceStatusTimer = 0;

            VoiceCaptureService.startRecording(wavData -> {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    micButton.setMessage(Component.literal("§e⏳"));
                    voiceStatusText = "§e识别中...";

                    VoskSttEngine.transcribe(wavData)
                        .thenAccept(text -> {
                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                isRecording = false;
                                if (text != null && !text.isEmpty()) {
                                    if (VoiceConfig.isAutoSend()) {
                                        inputField.setValue(text);
                                        sendMessage();
                                    } else {
                                        inputField.setValue(text);
                                    }
                                    voiceStatusText = "";
                                } else {
                                    voiceStatusText = "§7未检测到语音";
                                    voiceStatusTimer = 40;
                                }
                                micButton.setMessage(Component.literal("🎤"));
                            });
                        });
                });
            });
        } else {
            // Stop recording
            VoiceCaptureService.stopRecording();
        }
    }
```

- [ ] **步骤 4：在 tick() 中更新时间**

在 `NpcChatScreen.tick()` 方法的 `inputField.tick();` 之后添加：

```java
        // Voice status timer (add this line)
        if (voiceStatusTimer > 0) {
            voiceStatusTimer--;
            if (voiceStatusTimer <= 0) voiceStatusText = "";
        }
```

- [ ] **步骤 5：在 render() 中绘制语音状态**

在 `render()` 方法中，找到 `if (awaitingReply)` 块之后添加：

```java
        // Voice status text
        if (!voiceStatusText.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(voiceStatusText), cx, chatBottom + 16, 0xFFFFFF);
        }
```

- [ ] **步骤 6：验证编译**

运行：`.\gradlew compileJava`
预期：BUILD SUCCESSFUL

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/NpcChatScreen.java
git commit -m "feat(voice): add mic button to NpcChatScreen"
```
