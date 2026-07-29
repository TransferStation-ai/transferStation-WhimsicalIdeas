package transferstation.transferstation_whimsicalideas.client.morph;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MorphManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, VpdMorphLoader.VpdMorphData> morphRegistry = new ConcurrentHashMap<>();
    private static Path customMorphDir;

    public static void init(Path configDir) {
        customMorphDir = configDir.resolve("CustomMorph");
        try {
            Files.createDirectories(customMorphDir);
            LOGGER.info("[MorphManager] CustomMorph directory: {}", customMorphDir);
        } catch (IOException e) {
            LOGGER.error("[MorphManager] Failed to create CustomMorph directory", e);
        }
        refreshMorphs();
    }

    public static void refreshMorphs() {
        morphRegistry.clear();
        if (customMorphDir == null || !Files.exists(customMorphDir)) return;

        try (var files = Files.list(customMorphDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".vpd"))
                .forEach(p -> {
                    try {
                        VpdMorphLoader.VpdMorphData morph = VpdMorphLoader.loadFromFile(p);
                        morphRegistry.put(morph.name, morph);
                        LOGGER.info("[MorphManager] Loaded morph: {} ({} bones)", morph.name, morph.boneDataList.size());
                    } catch (IOException e) {
                        LOGGER.error("[MorphManager] Failed to load morph from {}", p, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("[MorphManager] Error scanning CustomMorph directory", e);
        }

        LOGGER.info("[MorphManager] Loaded {} morphs total", morphRegistry.size());
    }

    public static VpdMorphLoader.VpdMorphData getMorph(String name) {
        return morphRegistry.get(name);
    }

    public static boolean hasMorph(String name) {
        return morphRegistry.containsKey(name);
    }

    public static List<String> getMorphNames() {
        return new ArrayList<>(morphRegistry.keySet());
    }

    public static List<VpdMorphLoader.VpdMorphData> getAllMorphs() {
        return new ArrayList<>(morphRegistry.values());
    }

    public static void applyMorph(String morphName, Map<String, float[]> boneOutMap) {
        VpdMorphLoader.VpdMorphData morph = morphRegistry.get(morphName);
        if (morph == null) return;

        for (VpdMorphLoader.VpdBoneData boneData : morph.boneDataList) {
            float[] transform = new float[7];
            transform[0] = boneData.translation.x;
            transform[1] = boneData.translation.y;
            transform[2] = boneData.translation.z;
            transform[3] = boneData.rotation[0];
            transform[4] = boneData.rotation[1];
            transform[5] = boneData.rotation[2];
            transform[6] = boneData.rotation[3];
            boneOutMap.put(boneData.boneName, transform);
        }
    }

    public static Path getCustomMorphDir() {
        return customMorphDir;
    }

    public static void clearAll() {
        morphRegistry.clear();
    }
}
