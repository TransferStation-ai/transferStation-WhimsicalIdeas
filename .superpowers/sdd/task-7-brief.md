### 任务 7：主类初始化语音服务

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/Transferstation_whimsicalideas.java`

- [ ] **步骤 1：在 ClientModEvents.onClientSetup 中添加初始化调用**

在 `ClientModEvents.onClientSetup()` 方法中，在 `initializeClientComponents();` 调用之前添加：

```java
            // Initialize voice input services
            initializeVoiceInput();
```

- [ ] **步骤 2：添加初始化方法**

在 `ClientModEvents` 类中添加新方法：

```java
        private static void initializeVoiceInput() {
            // Load voice config
            VoiceConfig.load();

            // Initialize microphone detection
            VoiceCaptureService.initialize();

            // Check if Vosk model exists and initialize on background thread
            if (VoiceConfig.isModelAvailable()) {
                VoskSttEngine.initialize();
            } else {
                LOGGER.info("[TransferStation] Vosk model not found at {}; voice input disabled until model is downloaded",
                        VoiceConfig.getModelPath());
            }
        }
```

- [ ] **步骤 3：添加 imports**

确保文件顶部有这些 import：

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
git add src/main/java/transferstation/transferstation_whimsicalideas/Transferstation_whimsicalideas.java
git commit -m "feat(voice): initialize voice services on client setup"
```
