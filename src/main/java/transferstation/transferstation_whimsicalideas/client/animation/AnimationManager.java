// AnimationManager.java - manages loading and applying animations
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AnimationManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Animations are stored in AnimationProcessor's single registry so that config-loaded
    // animations are actually reachable by the renderer (which only queries AnimationProcessor).
    // Keeping a separate map here made loadAnimationFromConfig() load into a dead registry.

    public static void registerAnimation(AnimationData animation) {
        AnimationProcessor.registerAnimation(animation);
        LOGGER.info("[AnimationManager] Registered animation: {} ({} fps, {} frames)",
            animation.name, animation.fps, animation.frameCount);
    }

    public static AnimationData getAnimation(String name) {
        return AnimationProcessor.getAnimation(name);
    }

    public static boolean hasAnimation(String name) {
        return AnimationProcessor.getAnimation(name) != null;
    }

    public static void loadAnimationFromConfig(Path configDir) throws IOException {
        Path animationFolder = configDir.resolve("CustomAnim");
        if (!Files.exists(animationFolder)) {
            LOGGER.warn("[AnimationManager] CustomAnim folder not found: {}", animationFolder);
            Files.createDirectories(animationFolder);
        }

        int[] loadedCount = {0};
        try (var files = Files.list(animationFolder)) {
            files.filter(p -> p.getFileName().toString().endsWith(".vmd"))
                .forEach(p -> {
                    try {
                        AnimationData animation = VmdAnimationLoader.loadFromVMD(p);
                        AnimationProcessor.registerAnimation(animation);
                        loadedCount[0]++;
                    } catch (IOException e) {
                        LOGGER.error("[AnimationManager] Failed to load animation from config: {}", p, e);
                    }
                });
        } catch (IOException e) {
            LOGGER.error("[AnimationManager] Error scanning animation folder: {}", animationFolder, e);
        }

        if (loadedCount[0] > 0) {
            LOGGER.info("[AnimationManager] Loaded {} animation(s) from config", loadedCount[0]);
        }
    }

    public static void addAnimationToModel(String modelName, String animationName, AnimationData animation) {
        registerAnimation(animation);
        LOGGER.info("[AnimationManager] Added animation '{}' to model '{}'", animationName, modelName);
    }

    public static void clearAllAnimations() {
        AnimationProcessor.clearMorph();
        LOGGER.info("[AnimationManager] Cleared all animations");
    }

    public static java.util.Collection<AnimationData> getAllAnimations() {
        return AnimationProcessor.getAllRegisteredAnimations();
    }
}