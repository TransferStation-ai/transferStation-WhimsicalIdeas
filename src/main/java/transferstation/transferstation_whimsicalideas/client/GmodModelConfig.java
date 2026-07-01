package transferstation.transferstation_whimsicalideas.client;

import transferstation.transferstation_whimsicalideas.client.model.MdlModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.ModelPackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class GmodModelConfig {
    private static boolean playerModelEnabled = false;
    private static boolean mobModelEnabled = false;
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
        return result;
    }

    private static boolean hasAnyModelFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase();
                return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".lua");
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
    }

    public static boolean isMobModelEnabled() {
        return mobModelEnabled;
    }

    public static void setMobModelEnabled(boolean enabled) {
        mobModelEnabled = enabled;
    }

    public static void togglePlayerModel() {
        playerModelEnabled = !playerModelEnabled;
    }

    public static void toggleMobModel() {
        mobModelEnabled = !mobModelEnabled;
    }

    public static String getSelectedModelName() {
        return selectedModelName;
    }

    public static void setSelectedModelName(String name) {
        selectedModelName = name;
        MdlModelRenderer.setCurrentModel(name);
        if (name != null && !name.isEmpty() && modelsDir != null) {
            try {
                MdlModelRenderer.loadModel(modelsDir, name);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}