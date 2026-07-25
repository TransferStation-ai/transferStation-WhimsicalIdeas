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
