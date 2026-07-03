// AnimationProcessor.java - core animation processing class for managing models
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.GmodModelRenderer;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

public class AnimationProcessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, AnimationData> animationRegistry = new HashMap<>();

    private AnimationProcessor() {
    }

    public static void registerAnimation(AnimationData animation) {
        animationRegistry.put(animation.name, animation);
        LOGGER.info("[AnimationProcessor] Registered animation: {} ({} fps, {} frames)", 
            animation.name, animation.fps, animation.frameCount);
    }

    public static AnimationData getAnimation(String name) {
        return animationRegistry.get(name);
    }

    public static boolean loadDefaultAnimations(Path configDir) {
        String modid = "transferstation_whimsicalideas";
        Path modelConfigDir = configDir.resolve(modid);
        if (!java.nio.file.Files.exists(modelConfigDir)) {
            LOGGER.warn("[AnimationProcessor] Config directory not found: {}", modelConfigDir);
            return false;
        }

        Path animationFolder = modelConfigDir.resolve("animation");
        if (!java.nio.file.Files.exists(animationFolder)) {
            LOGGER.warn("[AnimationProcessor] Animation folder not found: {}", animationFolder);
            return false;
        }

        boolean[] loadedAny = {false};
        try (var files = java.nio.file.Files.list(animationFolder)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        String jsonContent = java.nio.file.Files.readString(p);
                        AnimationData animation = JsonAnimationLoader.loadFromJson(jsonContent);
                        animationRegistry.put(animation.name, animation);
                        LOGGER.info("[AnimationProcessor] Loaded default animation from config: {}", p);
                        loadedAny[0] = true;
                    } catch (Exception e) {
                        LOGGER.error("[AnimationProcessor] Failed to load animation from {}: {}", p, e);
                    }
                });
        } catch (Exception e) {
            LOGGER.error("[AnimationProcessor] Error scanning animation folder: {}", animationFolder, e);
        }

        if (loadedAny[0]) {
            LOGGER.info("[AnimationProcessor] Successfully loaded {} default animations", animationRegistry.size());
        }
        return loadedAny[0];
    }

    public static List<AnimationData> getAllRegisteredAnimations() {
        return new ArrayList<>(animationRegistry.values());
    }

    public static AnimationData createDefaultAnimation(String name, String modelName, int durationTicks) {
        AnimationData animation = JsonAnimationLoader.createDefaultAnimation(name, durationTicks);
        animationRegistry.put(name, animation);
        LOGGER.info("[AnimationProcessor] Created default animation '{}' for model '{}'", name, modelName);
        return animation;
    }

    public static void applyAnimationToModel(String modelName, String animationName, LivingEntity entity, 
                                             PoseStack poseStack, MultiBufferSource bufferSource,
                                             int packedLight, float partialTicks, float scale) {
        AnimationData animation = animationRegistry.get(animationName);
        if (animation == null) {
            LOGGER.warn("[AnimationProcessor] Animation '{}' not found for model '{}'", animationName, modelName);
            return;
        }

        LOGGER.info("[AnimationProcessor] Applying animation '{}' to model '{}'", animationName, modelName);
        applyAnimation(animation, entity, poseStack, bufferSource, packedLight, partialTicks, scale);
    }

    private static void applyAnimation(AnimationData animation, LivingEntity entity, 
                                       PoseStack poseStack, MultiBufferSource bufferSource,
                                       int packedLight, float partialTicks, float scale) {
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);

        float elapsedTime = (entity.tickCount + partialTicks) % animation.frameCount;
        int currentFrame = (int) ((elapsedTime * animation.fps) % animation.frameCount);

        for (AnimationData.AnimationTrack track : animation.tracks) {
            applyTrackAnimation(track, entity, poseStack, currentFrame, partialTicks);
        }

        GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
        poseStack.popPose();
    }

    private static void applyTrackAnimation(AnimationData.AnimationTrack track, LivingEntity entity,
                                            PoseStack poseStack, int currentFrame, float partialTicks) {
        AnimationData.KeyFrame keyframe = getCurrentKeyFrame(track.keyFrames, currentFrame);
        if (keyframe == null) return;

        poseStack.pushPose();

        if (keyframe.translation != null) {
            poseStack.translate(keyframe.translation[0], keyframe.translation[1], keyframe.translation[2]);
        }

        if (keyframe.rotation != null) {
            float angle = (float) Math.acos(keyframe.rotation[3]);
            float sinAngle = (float) Math.sqrt(1.0f - keyframe.rotation[3] * keyframe.rotation[3]);
            float axisX = keyframe.rotation[0] * sinAngle;
            float axisY = keyframe.rotation[1] * sinAngle;
            float axisZ = keyframe.rotation[2] * sinAngle;
            float rotationAngle = 2.0f * angle;
            poseStack.mulPose(com.mojang.math.Axis.of(new Vector3f(axisX, axisY, axisZ)).rotation(rotationAngle));
        }

        if (keyframe.scale != null) {
            poseStack.scale(keyframe.scale[0], keyframe.scale[1], keyframe.scale[2]);
        }

        poseStack.popPose();
    }

    private static AnimationData.KeyFrame getCurrentKeyFrame(List<AnimationData.KeyFrame> keyFrames, int frame) {
        if (keyFrames == null || keyFrames.isEmpty()) return null;

        AnimationData.KeyFrame closestKeyFrame = null;
        int minDiff = Integer.MAX_VALUE;

        for (AnimationData.KeyFrame kf : keyFrames) {
            int diff = Math.abs(kf.frame - frame);
            if (diff < minDiff) {
                minDiff = diff;
                closestKeyFrame = kf;
            }
        }

        return closestKeyFrame;
    }

    public static String getActiveAnimationName(LivingEntity entity) {
        float elapsed = (entity.tickCount + 0.5f) % 60.0f;
        if (elapsed < 20.0f) return "idle";
        if (elapsed < 40.0f) return "walk";
        return "wave";
    }

    public static void cleanup() {
        animationRegistry.clear();
        LOGGER.info("[AnimationProcessor] Cleared all animations");
    }
}