# 麦克风语音输入 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 NPC 聊天界面中添加麦克风语音输入功能，使用 Java Sound API 录制音频 + Vosk 本地离线语音识别。

**架构：** 纯客户端功能。VoiceCaptureService 封装 Java Sound API 录音 → VoskSttEngine 调用 Vosk 进行离线转写 → 文本注入 NpcChatScreen 输入框。Push-to-Talk 模式（按住录音，松手转写）。

**技术栈：** Java Sound API (JDK 内置) + Vosk 0.3.45 + JNA 5.14.0

---

## 文件清单

### 创建
- `client/voice/VoiceConfig.java` — 语音输入配置数据类
- `client/voice/VoiceCaptureService.java` — 音频录制核心服务
- `client/voice/VoskSttEngine.java` — Vosk 语音识别引擎封装

### 修改
- `build.gradle` — 添加 Vosk + JNA 依赖
- `client/NpcChatScreen.java` — 添加麦克风按钮 + 录音状态 + 转写注入
- `client/AiConfigScreen.java` — 添加语音设置区域
- `Transferstation_whimsicalideas.java` — ClientModEvents 中初始化语音服务
- `lang/en_us.json` — 添加语音相关翻译
- `lang/zh_cn.json` — 添加语音相关翻译

---

### 任务 1：添加 Vosk + JNA 依赖

**文件：**
- 修改：`build.gradle:166-195`

- [ ] **步骤 1：编辑 build.gradle 添加依赖**

编辑 `build.gradle`，在 `dependencies` 块中（`annotationProcessor` 行之前）添加：

```groovy
    // Vosk offline speech recognition
    implementation 'org.alphacep:vosk:0.3.45'
    implementation 'net.java.dev.jna:jna:5.14.0'
```

- [ ] **步骤 2：验证 Gradle 同步**

运行：`gradlew dependencies --configuration runtimeClasspath`
预期：输出中包含 `org.alphacep:vosk:0.3.45` 和 `net.java.dev.jna:jna:5.14.0`

- [ ] **步骤 3：Commit**

```bash
git add build.gradle
git commit -m "build: add Vosk + JNA dependencies for voice input"
```

---

### 任务 2：创建 VoiceConfig 配置类

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoiceConfig.java`

- [ ] **步骤 1：创建 VoiceConfig.java**

创建包 `client/voice`，新增 `VoiceConfig` 类：

```java
package transferstation.transferstation_whimsicalideas.client.voice;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;

/**
 * Voice input configuration: model path, language, auto-send toggle.
 * Persisted to config/transferstation_whimsicalideas/voice.properties.
 */
public class VoiceConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path CONFIG_DIR = FMLPaths.CONFIGDIR.get()
            .resolve("transferstation_whimsicalideas");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("voice.properties");
    private static final Path MODEL_DIR = CONFIG_DIR.resolve("vosk-model");

    // Default small Chinese+English model (~42MB)
    private static final String DEFAULT_MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    private static final String DEFAULT_MODEL_DIR_NAME = "vosk-model-small-cn-0.22";

    private static boolean enabled = true;
    private static String modelPath = "";
    private static String language = "cn";
    private static boolean autoSend = true;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }

    public static String getModelPath() {
        if (!modelPath.isEmpty()) return modelPath;
        // Default: check if model exists in config dir
        Path defaultModel = MODEL_DIR.resolve(DEFAULT_MODEL_DIR_NAME);
        if (Files.exists(defaultModel)) {
            return defaultModel.toAbsolutePath().toString();
        }
        return MODEL_DIR.resolve(DEFAULT_MODEL_DIR_NAME).toAbsolutePath().toString();
    }
    public static void setModelPath(String p) { modelPath = p; }

    public static String getLanguage() { return language; }
    public static void setLanguage(String l) { language = l; }

    public static boolean isAutoSend() { return autoSend; }
    public static void setAutoSend(boolean v) { autoSend = v; }

    /** Check if the default model directory exists and has model files. */
    public static boolean isModelAvailable() {
        Path path = Path.of(getModelPath());
        if (!Files.exists(path)) return false;
        // Vosk model must contain an "am" (acoustic model) subdirectory
        return Files.exists(path.resolve("am")) || Files.exists(path.resolve("graph"));
    }

    /** Returns the config directory containing voice.properties. */
    public static Path getConfigDir() { return CONFIG_DIR; }

    public static void load() {
        Properties props = new Properties();
        if (Files.exists(CONFIG_PATH)) {
            try (var reader = Files.newBufferedReader(CONFIG_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                LOGGER.warn("[VoiceConfig] Failed to load", e);
            }
        }
        enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
        modelPath = props.getProperty("modelPath", "");
        language = props.getProperty("language", "cn");
        autoSend = Boolean.parseBoolean(props.getProperty("autoSend", "true"));
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            Properties props = new Properties();
            props.setProperty("enabled", String.valueOf(enabled));
            props.setProperty("modelPath", modelPath);
            props.setProperty("language", language);
            props.setProperty("autoSend", String.valueOf(autoSend));
            try (var writer = Files.newBufferedWriter(CONFIG_PATH)) {
                props.store(writer, "Voice Input Configuration");
            }
        } catch (IOException e) {
            LOGGER.error("[VoiceConfig] Failed to save", e);
        }
    }

    /**
     * Download the default Vosk model from alphacephei.com.
     * Runs in a background thread, returns true if successful.
     */
    public static boolean downloadDefaultModel() {
        try {
            Files.createDirectories(MODEL_DIR);
            Path zipPath = MODEL_DIR.resolve("model.zip");
            LOGGER.info("[VoiceConfig] Downloading Vosk model from {} ...", DEFAULT_MODEL_URL);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DEFAULT_MODEL_URL))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<Path> response = client.send(request,
                    HttpResponse.BodyHandlers.ofFile(zipPath));

            if (response.statusCode() != 200) {
                LOGGER.error("[VoiceConfig] Download failed: HTTP {}", response.statusCode());
                return false;
            }

            // Extract zip
            try (var zis = new java.util.zip.ZipInputStream(
                    Files.newInputStream(zipPath))) {
                java.util.zip.ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    Path target = MODEL_DIR.resolve(entry.getName()).normalize();
                    if (!target.startsWith(MODEL_DIR)) continue; // security check
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (var out = Files.newOutputStream(target)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                out.write(buffer, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                }
            }

            Files.deleteIfExists(zipPath);
            LOGGER.info("[VoiceConfig] Model downloaded and extracted to {}", MODEL_DIR);
            return true;
        } catch (Exception e) {
            LOGGER.error("[VoiceConfig] Failed to download model", e);
            return false;
        }
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoiceConfig.java
git commit -m "feat(voice): add VoiceConfig with model download"
```

---

### 任务 3：创建 VoiceCaptureService 音频录制服务

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoiceCaptureService.java`

- [ ] **步骤 1：创建 VoiceCaptureService.java**

```java
package transferstation.transferstation_whimsicalideas.client.voice;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Captures microphone audio using Java Sound API.
 * Push-to-talk: call startRecording() to begin, stopRecording() to finish.
 * The resulting WAV bytes are passed to the callback for transcription.
 */
public class VoiceCaptureService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float SAMPLE_RATE = 16000.0f;
    private static final int SAMPLE_BITS = 16;
    private static final int CHANNELS = 1;

    private static TargetDataLine line;
    private static Thread captureThread;
    private static volatile boolean recording = false;
    private static volatile boolean available = false;
    private static Consumer<byte[]> onRecordingComplete;

    /**
     * Check if a microphone is available on this system.
     */
    public static boolean isMicrophoneAvailable() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info info : mixers) {
            Mixer mixer = AudioSystem.getMixer(info);
            Line.Info[] targetLines = mixer.getTargetLineInfo();
            for (Line.Info li : targetLines) {
                if (li.getLineClass() == TargetDataLine.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Initialize: check and open the default microphone line.
     * Called once during mod client setup.
     */
    public static void initialize() {
        if (available) return;
        if (!isMicrophoneAvailable()) {
            LOGGER.warn("[VoiceCapture] No microphone found on this system");
            available = false;
            return;
        }
        available = true;
        LOGGER.info("[VoiceCapture] Microphone is available");
    }

    public static boolean isAvailable() { return available; }
    public static boolean isRecording() { return recording; }

    /**
     * Start recording from the default microphone.
     * The callback receives the complete WAV byte[] when stopRecording() is called.
     */
    public static void startRecording(Consumer<byte[]> onDone) {
        if (!available || recording) return;
        onRecordingComplete = onDone;

        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_BITS, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                LOGGER.warn("[VoiceCapture] Line format not supported: {} Hz {} bit {} ch",
                        SAMPLE_RATE, SAMPLE_BITS, CHANNELS);
                available = false;
                return;
            }

            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            recording = true;

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            captureThread = new Thread(() -> {
                byte[] chunk = new byte[4096];
                try {
                    while (recording && line.isOpen()) {
                        int bytesRead = line.read(chunk, 0, chunk.length);
                        if (bytesRead > 0) {
                            buffer.write(chunk, 0, bytesRead);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("[VoiceCapture] Error during recording", e);
                } finally {
                    byte[] audioData = buffer.toByteArray();
                    if (onRecordingComplete != null && audioData.length > 0) {
                        byte[] wavData = createWavFile(audioData, (int) SAMPLE_RATE);
                        onRecordingComplete.accept(wavData);
                    }
                }
            }, "VoiceCapture");
            captureThread.setDaemon(true);
            captureThread.start();

        } catch (LineUnavailableException e) {
            LOGGER.error("[VoiceCapture] Failed to open microphone line", e);
            available = false;
        }
    }

    /**
     * Stop recording. Closes the line and signals the capture thread to finish.
     */
    public static void stopRecording() {
        recording = false;
        if (line != null && line.isOpen()) {
            line.stop();
            line.close();
        }
        if (captureThread != null && captureThread.isAlive()) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException ignored) {
                captureThread.interrupt();
            }
            captureThread = null;
        }
    }

    /**
     * Creates a WAV byte array from raw PCM data.
     */
    private static byte[] createWavFile(byte[] pcmData, int sampleRate) {
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        int dataSize = pcmData.length;
        int channels = CHANNELS;
        int bitsPerSample = SAMPLE_BITS;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        try {
            // RIFF header
            wav.write("RIFF".getBytes());
            writeInt(wav, 36 + dataSize);
            wav.write("WAVE".getBytes());

            // fmt chunk
            wav.write("fmt ".getBytes());
            writeInt(wav, 16);                  // chunk size
            writeShort(wav, 1);                  // PCM format
            writeShort(wav, channels);           // channels
            writeInt(wav, sampleRate);           // sample rate
            writeInt(wav, byteRate);             // byte rate
            writeShort(wav, blockAlign);         // block align
            writeShort(wav, bitsPerSample);      // bits per sample

            // data chunk
            wav.write("data".getBytes());
            writeInt(wav, dataSize);
            wav.write(pcmData);
        } catch (IOException e) {
            LOGGER.error("[VoiceCapture] Failed to build WAV", e);
            return pcmData; // fallback: return raw PCM
        }
        return wav.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /** Clean up resources. Call on mod shutdown. */
    public static void shutdown() {
        if (recording) stopRecording();
        available = false;
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoiceCaptureService.java
git commit -m "feat(voice): add VoiceCaptureService for mic recording"
```

---

### 任务 4：创建 VoskSttEngine 语音识别引擎

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoskSttEngine.java`

- [ ] **步骤 1：创建 VoskSttEngine.java**

```java
package transferstation.transferstation_whimsicalideas.client.voice;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vosk offline speech recognition engine.
 * Wraps the Vosk Java API (org.vosk) for speech-to-text.
 * Runs recognition on a background thread pool.
 */
public class VoskSttEngine {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static Model model;
    private static boolean initialized = false;
    private static String lastError = "";
    private static final ExecutorService sttExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoskSTT");
        t.setDaemon(true);
        return t;
    });

    /**
     * Initialize the Vosk model. Must be called from a background thread
     * (model loading is I/O intensive).
     */
    public static CompletableFuture<Boolean> initialize() {
        if (initialized) return CompletableFuture.completedFuture(true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                String modelPath = VoiceConfig.getModelPath();
                LOGGER.info("[VoskSTT] Loading model from: {}", modelPath);
                model = new Model(modelPath);
                initialized = true;
                lastError = "";
                LOGGER.info("[VoskSTT] Model loaded successfully");
                return true;
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                LOGGER.error("[VoskSTT] Failed to load model: {}", lastError);
                initialized = false;
                return false;
            }
        }, sttExecutor);
    }

    public static boolean isInitialized() { return initialized; }
    public static String getLastError() { return lastError; }

    /**
     * Transcribe WAV audio bytes to text.
     * Runs on the background executor; returns a CompletableFuture.
     * The audio must be 16000 Hz, 16-bit, mono PCM (with WAV header).
     */
    public static CompletableFuture<String> transcribe(byte[] wavData) {
        if (!initialized || model == null) {
            return CompletableFuture.completedFuture("");
        }

        return CompletableFuture.supplyAsync(() -> {
            Recognizer recognizer = null;
            try {
                // Skip WAV header (44 bytes), pass raw PCM to Vosk
                int offset = 0;
                if (wavData.length > 44
                        && wavData[0] == 'R' && wavData[1] == 'I'
                        && wavData[2] == 'F' && wavData[3] == 'F') {
                    offset = 44;
                }

                recognizer = new Recognizer(model, 16000.0f);
                recognizer.acceptWaveform(wavData, offset, wavData.length - offset);
                String result = recognizer.getResult();

                // Parse JSON result to extract text field
                // Vosk returns: {"text": "hello world"}
                String text = parseVoskResult(result);
                LOGGER.debug("[VoskSTT] Recognized: '{}'", text);
                return text;

            } catch (Exception e) {
                LOGGER.error("[VoskSTT] Recognition failed", e);
                return "";
            } finally {
                if (recognizer != null) {
                    recognizer.delete();
                }
            }
        }, sttExecutor);
    }

    /**
     * Parse Vosk JSON result to extract the "text" field.
     * Handles both partial and final result formats.
     */
    private static String parseVoskResult(String json) {
        try {
            // Simple JSON parse without Gson dependency
            String key = "\"text\":\"";
            int start = json.indexOf(key);
            if (start < 0) {
                // Try partial format: {"partial": "..."}
                key = "\"partial\":\"";
                start = json.indexOf(key);
                if (start < 0) return "";
            }
            start += key.length();
            int end = json.indexOf('"', start);
            return (end > start) ? json.substring(start, end).trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Shutdown the executor. Call on mod shutdown. */
    public static void shutdown() {
        sttExecutor.shutdown();
        if (model != null) {
            model.close();
            model = null;
        }
        initialized = false;
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/voice/VoskSttEngine.java
git commit -m "feat(voice): add VoskSttEngine for offline STT"
```

---

### 任务 5：修改 NpcChatScreen — 添加麦克风按钮

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/NpcChatScreen.java`

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

- [ ] **步骤 2：在 init() 中添加 Mic 按钮**

在 `init()` 方法中找到发送按钮的创建代码：

```java
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.send"),
            btn -> sendMessage()
        ).bounds(cx + 125, height - 30, 40, 18).build());
```

在这之前添加 Mic 按钮：

```java
        // Mic button (to the left of the input field)
        boolean micAvail = transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.isAvailable()
                && transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isEnabled()
                && transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine.isInitialized();
        micButton = addRenderableWidget(Button.builder(
            Component.literal(micAvail ? "🎤" : "§7🎤"),
            btn -> handleMicPress()
        ).bounds(cx - 165, height - 30, 20, 18).build());
        micButton.active = micAvail;
        this.voiceModeAvailable = micAvail;
```

同时修改输入框的 x 位置，从 `cx - 140` 改为 `cx - 140` 不变（mic 按钮在 -165 到 -145 位置）。

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

            transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.startRecording(wavData -> {
                // This callback runs on a background thread
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    micButton.setMessage(Component.literal("§e⏳"));
                    voiceStatusText = "§e识别中...";

                    transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine.transcribe(wavData)
                        .thenAccept(text -> {
                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                isRecording = false;
                                if (text != null && !text.isEmpty()) {
                                    if (transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isAutoSend()) {
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
            transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.stopRecording();
        }
    }
```

- [ ] **步骤 4：在 tick() 中更新时间**

在 `NpcChatScreen.tick()` 方法的 `inputField.tick();` 之后添加：

```java
        // Voice status timer
        if (voiceStatusTimer > 0) {
            voiceStatusTimer--;
            if (voiceStatusTimer <= 0) voiceStatusText = "";
        }
```

- [ ] **步骤 5：在 render() 中绘制语音状态**

在 `render()` 方法中，在 `if (awaitingReply)` 块之后添加：

```java
        // Voice status text
        if (!voiceStatusText.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(voiceStatusText), cx, chatBottom + 16, 0xFFFFFF);
        }
```

- [ ] **步骤 6：添加导入语句**

确保文件顶部有正确的导入：

```java
// 已有导入保持不变，无需额外添加（所有引用使用全限定名）
```

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/NpcChatScreen.java
git commit -m "feat(voice): add mic button to NpcChatScreen"
```

---

### 任务 6：修改 AiConfigScreen — 添加语音设置区

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/AiConfigScreen.java`

- [ ] **步骤 1：在 init() 方法末尾添加语音设置控件**

在 `init()` 方法的末尾（`clear_history` 按钮之后）添加：

```java
        // ──── Voice Input Section ────
        y += 16;
        // Section separator line (just a label for now)
        addRenderableWidget(Button.builder(
            Component.literal("── " + net.minecraft.network.chat.Component.translatable(
                "gui.transferstation_whimsicalideas.voice_section").getString() + " ──"),
            btn -> {}
        ).pos(cx - 150, y).size(300, 2).build());
        // invisible dummy button used as separator

        y += 16;
        // Enable voice toggle
        var voiceToggle = addRenderableWidget(Button.builder(
            Component.translatable(
                transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isEnabled()
                    ? "gui.transferstation_whimsicalideas.voice_enabled"
                    : "gui.transferstation_whimsicalideas.voice_disabled"),
            btn -> {
                transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.setEnabled(
                    !transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isEnabled());
                btn.setMessage(Component.translatable(
                    transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isEnabled()
                        ? "gui.transferstation_whimsicalideas.voice_enabled"
                        : "gui.transferstation_whimsicalideas.voice_disabled"));
            }
        ).pos(cx - 50, y).size(280, 18).build());

        y += 22;
        // Auto-send toggle
        var autoSendToggle = addRenderableWidget(Button.builder(
            Component.translatable(
                transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isAutoSend()
                    ? "gui.transferstation_whimsicalideas.voice_autosend_on"
                    : "gui.transferstation_whimsicalideas.voice_autosend_off"),
            btn -> {
                transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.setAutoSend(
                    !transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isAutoSend());
                btn.setMessage(Component.translatable(
                    transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isAutoSend()
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
                    boolean ok = transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.downloadDefaultModel();
                    net.minecraft.client.Minecraft.getInstance().execute(() -> {
                        if (ok) {
                            btn.setMessage(Component.literal("§a下载完成"));
                            statusMessage = net.minecraft.network.chat.Component.translatable(
                                "gui.transferstation_whimsicalideas.voice_download_done").getString();
                            statusTimer = 80;
                            statusType = StatusType.SUCCESS;
                            // Reinitialize Vosk
                            transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine.initialize()
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
                if (transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.isRecording()) {
                    transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.stopRecording();
                    btn.setMessage(Component.translatable("gui.transferstation_whimsicalideas.voice_test_mic"));
                    statusMessage = net.minecraft.network.chat.Component.translatable(
                        "gui.transferstation_whimsicalideas.voice_test_done").getString();
                    statusTimer = 60;
                    statusType = StatusType.SUCCESS;
                } else {
                    transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.startRecording(data -> {
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

同时在 `onClose()` 方法末尾添加：

```java
        transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.save();
```

- [ ] **步骤 2：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/AiConfigScreen.java
git commit -m "feat(voice): add voice settings to AiConfigScreen"
```

---

### 任务 7：主类初始化语音服务

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/Transferstation_whimsicalideas.java`

- [ ] **步骤 1：在 ClientModEvents.onClientSetup 中初始化**

在 `ClientModEvents.onClientSetup()` 方法中，找到 `initializeClientComponents();` 调用，在其之前添加：

```java
            // Initialize voice input services
            initializeVoiceInput();
```

- [ ] **步骤 2：添加初始化方法**

在 `ClientModEvents` 类中添加新方法：

```java
        private static void initializeVoiceInput() {
            // Load voice config
            transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.load();

            // Initialize microphone detection
            transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService.initialize();

            // Check if Vosk model exists and initialize on background thread
            if (transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.isModelAvailable()) {
                transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine.initialize();
            } else {
                LOGGER.info("[TransferStation] Vosk model not found at {}; voice input disabled until model is downloaded",
                        transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig.getModelPath());
            }
        }
```

- [ ] **步骤 3：添加 shutdown 钩子（可选）**

在 `CleanupHandler` 类中，如果希望 shutdown 时清理语音资源，可在 `onServerStopping` 中添加：

```java
            // Voice capture cleanup (client-side, only on client)
```

（并不强制清理，因为客户端关闭时 JVM 会回收）

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/Transferstation_whimsicalideas.java
git commit -m "feat(voice): initialize voice services on client setup"
```

---

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

---

## 执行顺序

```
任务 1 (Gradle 依赖)
    │
    ▼
任务 2 (VoiceConfig)
    │
    ▼
任务 3 (VoiceCaptureService)
    │
    ▼
任务 4 (VoskSttEngine)
    │
    ▼
任务 5 (NpcChatScreen) ─── 依赖任务 3, 4
    │
    ▼
任务 6 (AiConfigScreen) ─── 依赖任务 2
    │
    ▼
任务 7 (主类初始化) ─────── 依赖任务 2, 3, 4
    │
    ▼
任务 8 (翻译) ─────────── 可独立执行
```

非阻塞任务（8）可以在任何时间执行。任务 5-7 需要在 2-4 完成后执行。
