package transferstation.transferstation_whimsicalideas.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import transferstation.transferstation_whimsicalideas.client.model.MdlModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.ModelPackage;
import transferstation.transferstation_whimsicalideas.client.model.NpcModelRegistry;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class GmodModelConfig {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean playerModelEnabled = false;
    private static boolean mobModelEnabled = false;
    private static boolean randomModelEnabled = false;
    private static String selectedModelName = "";
    private static Path modelsDir = null;
    private static Path cacheDir = null;

    public static void init(Path configDir) {
        modelsDir = configDir.resolve("models");
        cacheDir = configDir.resolve("cache");
        try {
            Files.createDirectories(modelsDir);
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create config directories", e);
        }
        MdlModelRenderer.setModelsDir(modelsDir);
        MdlModelRenderer.setCacheDir(cacheDir);
    }

    public static Path getModelsDir() {
        return modelsDir;
    }

    public static Path getCacheDir() {
        return cacheDir;
    }

    public static List<String> scanModels() {
        List<String> result = new ArrayList<>();
        if (modelsDir == null || !Files.exists(modelsDir)) return result;
        try (Stream<Path> dirs = Files.walk(modelsDir)) {
            dirs.filter(Files::isDirectory)
                .filter(dir -> !dir.equals(modelsDir))
                .forEach(dir -> {
                    if (hasAnyModelFile(dir)) {
                        result.add(modelsDir.relativize(dir).toString().replace('\\', '/'));
                    }
                });
        } catch (IOException ignored) {
        }
        return result;
    }

    public static List<ModelPackage> scanModelPackages() {
        List<ModelPackage> result = new ArrayList<>();
        if (modelsDir == null || !Files.exists(modelsDir)) return result;
        try (Stream<Path> dirs = Files.walk(modelsDir)) {
            dirs.filter(Files::isDirectory)
                .filter(dir -> !dir.equals(modelsDir))
                .forEach(dir -> {
                    if (hasAnyModelFile(dir)) {
                        String relativePath = modelsDir.relativize(dir).toString().replace('\\', '/');
                        ModelPackage pkg = new ModelPackage(relativePath, dir);
                        pkg.discover();
                        result.add(pkg);
                    }
                });
        } catch (IOException ignored) {
        }

        try (Stream<Path> dirs = Files.walk(modelsDir)) {
            dirs.filter(Files::isDirectory)
                .filter(dir -> !dir.equals(modelsDir))
                .forEach(dir -> {
                    Path addonJson = dir.resolve("addon.json");
                    if (Files.exists(addonJson) && Files.isRegularFile(addonJson)) {
                        Path packDir = dir.getParent();
                        if (packDir == null || !packDir.startsWith(modelsDir)) return;
                        if (!hasAnyModelFile(packDir)) return;
                        String relativePath = modelsDir.relativize(packDir).toString().replace('\\', '/');
                        boolean alreadyAdded = result.stream()
                            .anyMatch(p -> p.getName().equals(relativePath));
                        if (!alreadyAdded) {
                            ModelPackage pkg = new ModelPackage(relativePath, packDir);
                            pkg.discover();
                            result.add(pkg);
                        }
                    }
                });
        } catch (IOException ignored) {
        }
        return result;
    }

    private static boolean hasAnyModelFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase();
                return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".lua") || name.endsWith(".smd") || name.endsWith(".bbmodel");
            });
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isPlayerModelEnabled() {
        return playerModelEnabled;
    }

    public static void setPlayerModelEnabled(boolean enabled) {
        playerModelEnabled = enabled;
        persist();
    }

    public static boolean isMobModelEnabled() {
        return mobModelEnabled;
    }

    public static void setMobModelEnabled(boolean enabled) {
        mobModelEnabled = enabled;
        persist();
    }

    public static void togglePlayerModel() {
        playerModelEnabled = !playerModelEnabled;
        persist();
    }

    public static void toggleMobModel() {
        mobModelEnabled = !mobModelEnabled;
        persist();
    }

    public static boolean isRandomModelEnabled() {
        return randomModelEnabled;
    }

    public static void setRandomModelEnabled(boolean enabled) {
        randomModelEnabled = enabled;
        persist();
    }

    public static void toggleRandomModel() {
        randomModelEnabled = !randomModelEnabled;
        persist();
    }

    public static String getSelectedModelName() {
        return selectedModelName;
    }

    public static void setSelectedModelName(String name) {
        selectedModelName = name;
        MdlModelRenderer.setCurrentModel(name);
        if (name != null && !name.isEmpty() && modelsDir != null) {
            // 异步加载避免阻塞渲染线程导致服务器看门狗超时断连
            MdlModelRenderer.loadModelAsync(modelsDir, name)
                .exceptionally(e -> {
                    LOGGER.error("[GmodModelConfig] Failed to load model '{}'", name, e);
                    return null;
                });
        }
        persist();
    }

    public static String getRandomModelName() {
        if (modelsDir == null || !Files.exists(modelsDir)) {
            return "";
        }
        List<String> models = scanModels();
        if (models.isEmpty()) {
            return "";
        }
        return models.get((int) (Math.random() * models.size()));
    }

    public static void loadRandomModel() {
        if (modelsDir == null) return;
        String randomModel = getRandomModelName();
        if (randomModel != null && !randomModel.isEmpty()) {
            MdlModelRenderer.loadModelAsync(modelsDir, randomModel)
                .exceptionally(e -> {
                    LOGGER.error("[GmodModelConfig] Failed to load random model '{}'", randomModel, e);
                    return null;
                });
        }
    }

    // ==================== Persistence ====================

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Data class for JSON serialization of player model config.
     */
    private static class PersistedState {
        boolean playerModelEnabled = false;
        boolean mobModelEnabled = false;
        boolean randomModelEnabled = false;
        String selectedModelName = "";
    }

    private static Path getPersistFile() {
        if (modelsDir == null) return null;
        Path parent = modelsDir.getParent();
        return parent != null ? parent.resolve("player_model.json") : null;
    }

    /**
     * Save current player model settings to disk.
     */
    private static void persist() {
        Path file = getPersistFile();
        if (file == null) return;
        try {
            PersistedState state = new PersistedState();
            state.playerModelEnabled = playerModelEnabled;
            state.mobModelEnabled = mobModelEnabled;
            state.randomModelEnabled = randomModelEnabled;
            state.selectedModelName = selectedModelName != null ? selectedModelName : "";
            Files.writeString(file, GSON.toJson(state));
            LOGGER.debug("[GmodModelConfig] Persisted player model: enabled={}, model='{}'",
                state.playerModelEnabled, state.selectedModelName);
        } catch (IOException e) {
            LOGGER.error("[GmodModelConfig] Failed to persist player model config", e);
        }
    }

    /**
     * Restore player model settings from disk and auto-load the model if applicable.
     * Called when the client player joins a world (single player or multiplayer).
     */
    public static void loadPersisted() {
        Path file = getPersistFile();
        if (file == null || !Files.exists(file)) {
            LOGGER.debug("[GmodModelConfig] No persisted config found at {}", file);
            return;
        }
        try {
            String json = Files.readString(file);
            PersistedState state = GSON.fromJson(json, PersistedState.class);
            if (state == null) return;

            playerModelEnabled = state.playerModelEnabled;
            mobModelEnabled = state.mobModelEnabled;
            randomModelEnabled = state.randomModelEnabled;
            selectedModelName = state.selectedModelName != null ? state.selectedModelName : "";

            LOGGER.info("[GmodModelConfig] Restored player model config: enabled={}, model='{}'",
                playerModelEnabled, selectedModelName);

            // Set current model on the renderer (even if disabled, so ModelSelectButton highlights it)
            MdlModelRenderer.setCurrentModel(selectedModelName);

            // Trigger async model loading if player model is active and a model was selected
            if (playerModelEnabled && !selectedModelName.isEmpty() && modelsDir != null) {
                MdlModelRenderer.loadModelAsync(modelsDir, selectedModelName)
                    .exceptionally(e -> {
                        LOGGER.error("[GmodModelConfig] Failed to auto-load persisted model '{}'", selectedModelName, e);
                        return null;
                    });
            }
        } catch (IOException e) {
            LOGGER.error("[GmodModelConfig] Failed to load persisted player model config", e);
        }
    }
}