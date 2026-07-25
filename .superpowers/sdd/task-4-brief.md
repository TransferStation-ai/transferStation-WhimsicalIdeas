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
