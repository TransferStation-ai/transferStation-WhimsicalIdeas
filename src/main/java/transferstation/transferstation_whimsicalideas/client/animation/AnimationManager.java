// AnimationManager.java - manages loading and applying animations
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;

public class AnimationManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, AnimationData> animations = new HashMap<>();

    public static void registerAnimation(AnimationData animation) {
        animations.put(animation.name, animation);
        LOGGER.info("[AnimationManager] Registered animation: {} ({} fps, {} frames)", 
            animation.name, animation.fps, animation.frameCount);
    }

    public static AnimationData getAnimation(String name) {
        return animations.get(name);
    }

    public static boolean hasAnimation(String name) {
        return animations.containsKey(name);
    }

    public static void loadAnimationFromConfig(Path configDir, String modelName) {
        Path modelConfigDir = configDir.resolve("transferstation_whimsicalideas");
        if (!Files.exists(modelConfigDir)) {
            LOGGER.warn("[AnimationManager] Config directory not found: {}", modelConfigDir);
            return;
        }

        Path animationFolder = modelConfigDir.resolve("animation");
        if (!Files.exists(animationFolder)) {
            LOGGER.warn("[AnimationManager] Animation folder not found: {}", animationFolder);
            return;
        }

        boolean[] loadedAny = {false};
        try (var files = Files.list(animationFolder)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        String jsonContent = Files.readString(p);
                        AnimationData animation = JsonAnimationLoader.loadFromJson(jsonContent);
                        animations.put(animation.name, animation);
                        LOGGER.info("[AnimationManager] Loaded animation from config: {}", p);
                        loadedAny[0] = true;
                    } catch (IOException e) {
                        LOGGER.error("[AnimationManager] Failed to load animation from config: {}", p, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("[AnimationManager] Error scanning animation folder: {}", animationFolder, e);
        }

        if (loadedAny[0]) {
            LOGGER.info("[AnimationManager] Loaded {} animations for model: {}", animations.size(), modelName);
        }
    }

    public static void addAnimationToModel(String modelName, String animationName, AnimationData animation) {
        registerAnimation(animation);
        LOGGER.info("[AnimationManager] Added animation '{}' to model '{}'", animationName, modelName);
    }

    public static void clearAllAnimations() {
        animations.clear();
        LOGGER.info("[AnimationManager] Cleared all animations");
    }

    public static java.util.Collection<AnimationData> getAllAnimations() {
        return animations.values();
    }
}