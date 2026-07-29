// AnimationProcessor.java - core animation processing class for managing models
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.GmodModelRenderer;
import transferstation.transferstation_whimsicalideas.client.morph.MorphManager;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;
import transferstation.transferstation_whimsicalideas.client.model.MdlDataTypes;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

public class AnimationProcessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, AnimationData> animationRegistry = new HashMap<>();

    private static final Map<String, AnimationData> defaultAnimRegistry = new HashMap<>();
    private static final Map<String, AnimationData> customAnimRegistry = new HashMap<>();

    private static String currentMorph = null;

    private AnimationProcessor() {
    }

    public static void registerAnimation(AnimationData animation) {
        animationRegistry.put(animation.name, animation);
        LOGGER.info("[AnimationProcessor] Registered animation: {} ({} fps, {} frames)", 
            animation.name, animation.fps, animation.frameCount);
    }

    public static AnimationData getAnimation(String name) {
        AnimationData anim = animationRegistry.get(name);
        if (anim != null) return anim;
        anim = customAnimRegistry.get(name);
        if (anim != null) return anim;
        return defaultAnimRegistry.get(name);
    }

    public static boolean loadDefaultAnimations(Path configDir) {
        defaultAnimRegistry.clear();
        customAnimRegistry.clear();

        Path defaultAnimDir = configDir.resolve("DefaultAnim");
        Path customAnimDir = configDir.resolve("CustomAnim");

        try {
            java.nio.file.Files.createDirectories(defaultAnimDir);
            java.nio.file.Files.createDirectories(customAnimDir);
        } catch (Exception e) {
            LOGGER.error("[AnimationProcessor] Failed to create animation directories", e);
        }

        boolean loadedAny = loadAnimationsFromDir(defaultAnimDir, defaultAnimRegistry);
        loadedAny |= loadAnimationsFromDir(customAnimDir, customAnimRegistry);

        if (loadedAny) {
            LOGGER.info("[AnimationProcessor] Loaded {} default + {} custom = {} total animations",
                defaultAnimRegistry.size(), customAnimRegistry.size(), defaultAnimRegistry.size() + customAnimRegistry.size());
        }
        return loadedAny;
    }

    private static boolean loadAnimationsFromDir(Path dir, Map<String, AnimationData> registry) {
        if (!java.nio.file.Files.exists(dir)) return false;
        boolean[] loadedAny = {false};
        try (var files = java.nio.file.Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".vmd"))
                .forEach(p -> {
                    try {
                        AnimationData animation = VmdAnimationLoader.loadFromVMD(p);
                        String name = p.getFileName().toString();
                        if (name.toLowerCase().endsWith(".vmd")) {
                            name = name.substring(0, name.length() - 4);
                        }
                        registry.put(name, animation);
                        loadedAny[0] = true;
                    } catch (Exception e) {
                        LOGGER.error("[AnimationProcessor] Failed to load animation from {}: {}", p, e);
                    }
                });
        } catch (Exception e) {
            LOGGER.error("[AnimationProcessor] Error scanning directory: {}", dir, e);
        }
        return loadedAny[0];
    }

    public static List<AnimationData> getAllRegisteredAnimations() {
        List<AnimationData> all = new ArrayList<>(animationRegistry.values());
        all.addAll(defaultAnimRegistry.values());
        all.addAll(customAnimRegistry.values());
        return all;
    }

    public static AnimationData createDefaultAnimation(String name, String modelName, int durationTicks) {
        AnimationData animation = JsonAnimationLoader.createDefaultAnimation(name, durationTicks);
        animationRegistry.put(name, animation);
        return animation;
    }

    public static void applyAnimationToModel(String modelName, String animationName, LivingEntity entity, 
                                             PoseStack poseStack, MultiBufferSource bufferSource,
                                             int packedLight, float partialTicks, float scale) {
        AnimationData animation = getAnimation(animationName);
        if (animation == null) return;

        applyAnimation(animation, entity, poseStack, bufferSource, packedLight, partialTicks, scale);
    }

    private static void applyAnimation(AnimationData animation, LivingEntity entity, 
                                       PoseStack poseStack, MultiBufferSource bufferSource,
                                       int packedLight, float partialTicks, float scale) {
        poseStack.pushPose();
        poseStack.scale(scale, scale, scale);

        float elapsedSec = (entity.tickCount + partialTicks) / 20.0f;
        int currentFrame = animation.frameCount > 0 ? (int) (elapsedSec * animation.fps) % animation.frameCount : 0;

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
            // Clamp w to [-1, 1] before acos: un-normalized quaternions (e.g. from VMD)
            // can yield |w| > 1, making acos return NaN and corrupting the whole pose.
            float w = Math.max(-1.0f, Math.min(1.0f, keyframe.rotation[3]));
            float angle = (float) Math.acos(w);
            float sinAngle = (float) Math.sqrt(Math.max(0.0f, 1.0f - w * w));
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
        String mapped = GameStateAnimationMapper.getAnimationForEntity(entity);
        if (getAnimation(mapped) != null) return mapped;
        return "idle";
    }

    /**
     * Returns per-bone transformation matrices for the given entity at the current animation frame.
     * Used by the skinned renderer for per-vertex skinning.
     * 
     * The transforms are computed as: bindPose * animationDelta
     * where bindPose comes from the MDL's reference pose (srcBoneTransforms).
     */
    public static float[][] getBoneTransforms(LivingEntity entity, SourceModelData modelData, float partialTicks) {
        if (modelData == null || modelData.bones.isEmpty()) return null;

        int boneCount = modelData.bones.size();
        float[][] localTransforms = new float[boneCount][16];

        // 1. Initialize with bind pose (reference pose from MDL)
        // This is the model's rest pose - vertices are authored in this pose
        initializeBindPose(modelData, localTransforms);

        // 2. Try to get VMD animation
        String animName = getActiveAnimationName(entity);
        AnimationData anim = getAnimation(animName);
        
        // 3. If no VMD animation, try MDL's built-in sequence animation data
        if (anim == null || anim.tracks.isEmpty()) {
            anim = getMdlSequenceAnimation(entity, modelData, animName);
        }

        // 4. Apply animation delta on top of bind pose
        if (anim != null && !anim.tracks.isEmpty()) {
            applyAnimationDelta(entity, anim, modelData, localTransforms, partialTicks);
        }

        // 5. Convert local transforms to world-space by walking hierarchy in
        // topological order so a parent's world matrix is always ready before
        // its children, regardless of bone array ordering.
        Set<Integer> computed = new HashSet<>();
        for (int i = 0; i < boneCount; i++) {
            computeWorldBone(i, modelData, localTransforms, computed);
        }

        // 6. Apply morph transforms on top of animation (per-bone adjustments)
        if (currentMorph != null && !currentMorph.isEmpty()) {
            Map<String, float[]> morphTransforms = new HashMap<>();
            MorphManager.applyMorph(currentMorph, morphTransforms);
            for (Map.Entry<String, float[]> entry : morphTransforms.entrySet()) {
                int boneIdx = findBoneIndex(modelData, entry.getKey());
                if (boneIdx < 0) continue;

                float[] t = entry.getValue();
                org.joml.Matrix4f morphMat = new org.joml.Matrix4f();
                morphMat.identity();
                morphMat.translate(t[0], t[1], t[2]);
                if (t.length >= 7) {
                    float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, t[6]))));
                    float s = (float) Math.sqrt(1.0 - t[6] * t[6]);
                    if (s > 0.001f) {
                        float invS = 1.0f / s;
                        morphMat.rotate(angle, t[3] * invS, t[4] * invS, t[5] * invS);
                    }
                }

                org.joml.Matrix4f existing = new org.joml.Matrix4f();
                existing.set(localTransforms[boneIdx]);
                existing.mul(morphMat);
                existing.get(localTransforms[boneIdx]);
            }
        }

        return localTransforms;
    }

    /**
     * Compute the world-space transform for bone {@code index}, recursively
     * ensuring its parent is computed first. {@code computed} guards against
     * cycles so there is no infinite recursion.
     */
    private static void computeWorldBone(int index, SourceModelData modelData,
                                         float[][] localTransforms, Set<Integer> computed) {
        if (index < 0 || index >= modelData.bones.size() || computed.contains(index)) return;
        int parent = modelData.bones.get(index).parent;
        if (parent >= 0 && parent < modelData.bones.size() && !computed.contains(parent)) {
            computeWorldBone(parent, modelData, localTransforms, computed);
        }
        if (parent >= 0 && parent < modelData.bones.size()) {
            org.joml.Matrix4f parentWorld = new org.joml.Matrix4f();
            parentWorld.set(localTransforms[parent]);
            org.joml.Matrix4f local = new org.joml.Matrix4f();
            local.set(localTransforms[index]);
            parentWorld.mul(local);
            parentWorld.get(localTransforms[index]);
        }
        computed.add(index);
    }

    /**
     * Initialize bone transforms with the model's bind pose (reference pose).
     * Uses srcBoneTransforms from MDL's studiohdr2, or falls back to bone pos/quat from MDL header.
     */
    private static void initializeBindPose(SourceModelData modelData, float[][] localTransforms) {
        // Try srcBoneTransforms first (from studiohdr2 - most accurate)
        if (!modelData.srcBoneTransforms.isEmpty()) {
            for (int i = 0; i < Math.min(modelData.srcBoneTransforms.size(), localTransforms.length); i++) {
                MdlDataTypes.SrcBoneTransform bt = modelData.srcBoneTransforms.get(i);
                // Vertices are stored ALREADY swapped into MC space at parse time
                // (ModelLoadManager: (-sy, sz, sx)). To keep bone matrices in the SAME
                // MC space as the vertices, the Source-space bone transform must be
                // conjugated by the swap S:  M_mc = S * M_src * S^T  (S orthonormal).
                // Post-multiplying by S (the old code) applied the swap TWICE and left
                // the translation in Source space -> scattered triangles.
                org.joml.Matrix4f S = new org.joml.Matrix4f(
                    0f, -1f, 0f, 0f,
                    0f,  0f, 1f, 0f,
                    1f,  0f, 0f, 0f,
                    0f,  0f, 0f, 1f);
                org.joml.Matrix4f mSrc = new org.joml.Matrix4f();
                mSrc.identity();
                if (bt.pos != null) {
                    mSrc.translate(bt.pos[0], bt.pos[1], bt.pos[2]);
                }
                if (bt.quat != null) {
                    // quat is [x, y, z, w]
                    float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, bt.quat[3]))));
                    float s = (float) Math.sqrt(1.0 - bt.quat[3] * bt.quat[3]);
                    if (s > 0.001f) {
                        float invS = 1.0f / s;
                        mSrc.rotate(angle, bt.quat[0] * invS, bt.quat[1] * invS, bt.quat[2] * invS);
                    }
                }
                if (bt.scale != null) {
                    mSrc.scale(bt.scale[0], bt.scale[1], bt.scale[2]);
                }
                org.joml.Matrix4f mat = new org.joml.Matrix4f(S).mul(mSrc).mul(S.transpose());
                mat.get(localTransforms[i]);
            }
            return;
        }

        // Fallback: use bone pos/quat from MDL header.
        // Same Source->MC conjugation as the srcBoneTransforms branch:
        // vertices are pre-swapped to MC at parse time, so bone matrices must be
        // conjugated by S (M_mc = S * M_src * S^T) to live in the same space.
        org.joml.Matrix4f S = new org.joml.Matrix4f(
            0f, -1f, 0f, 0f,
            0f,  0f, 1f, 0f,
            1f,  0f, 0f, 0f,
            0f,  0f, 0f, 1f);
        for (int i = 0; i < modelData.bones.size(); i++) {
            SourceModelData.BoneInfo bone = modelData.bones.get(i);
            org.joml.Matrix4f mSrc = new org.joml.Matrix4f();
            mSrc.identity();
            if (bone.pos != null) {
                mSrc.translate(bone.pos[0], bone.pos[1], bone.pos[2]);
            }
            if (bone.quat != null) {
                float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, bone.quat[3]))));
                float s = (float) Math.sqrt(1.0 - bone.quat[3] * bone.quat[3]);
                if (s > 0.001f) {
                    float invS = 1.0f / s;
                    mSrc.rotate(angle, bone.quat[0] * invS, bone.quat[1] * invS, bone.quat[2] * invS);
                }
            }
            if (bone.rot != null) {
                // Euler rotation fallback (radians)
                mSrc.rotateXYZ(bone.rot[0], bone.rot[1], bone.rot[2]);
            }
            org.joml.Matrix4f mat = new org.joml.Matrix4f(S).mul(mSrc).mul(S.transpose());
            mat.get(localTransforms[i]);
        }
    }

    /**
     * Try to get animation from MDL's built-in sequence data.
     * This uses the localAnims and sequenceAnimData parsed from the MDL file.
     */
    private static AnimationData getMdlSequenceAnimation(LivingEntity entity, SourceModelData modelData, String animName) {
        // Check if we have reference pose or A-pose sequence data
        if (modelData.hasReferencePose()) {
            MdlDataTypes.AnimFrameData refFrame = modelData.getReferenceFrameData();
            if (refFrame != null && !refFrame.boneTransforms.isEmpty()) {
                return convertMdlFrameToAnimationData(refFrame, animName + "_ref");
            }
        }
        if (modelData.hasAPose()) {
            MdlDataTypes.AnimFrameData aPoseFrame = modelData.getAPoseFrameData();
            if (aPoseFrame != null && !aPoseFrame.boneTransforms.isEmpty()) {
                return convertMdlFrameToAnimationData(aPoseFrame, animName + "_apose");
            }
        }
        return null;
    }

    /**
     * Convert MDL frame data to AnimationData format.
     */
    private static AnimationData convertMdlFrameToAnimationData(MdlDataTypes.AnimFrameData frame, String name) {
        AnimationData anim = new AnimationData(name, 30.0f, 1, true);
        for (MdlDataTypes.AnimFrameBone fb : frame.boneTransforms) {
            AnimationData.AnimationTrack track = new AnimationData.AnimationTrack(fb.boneName);
            track.addKeyFrame(new AnimationData.KeyFrame(
                0, fb.pos, fb.quat, fb.scale
            ));
            anim.addTrack(track);
        }
        return anim;
    }

    /**
     * Apply animation delta on top of bind pose.
     * Animation data from VMD is typically relative to bind pose.
     */
    private static void applyAnimationDelta(LivingEntity entity, AnimationData anim, 
                                             SourceModelData modelData, float[][] localTransforms, float partialTicks) {
        float elapsedSec = (entity.tickCount + partialTicks) / 20.0f;
        int currentFrame = anim.frameCount > 0 ? (int) (elapsedSec * anim.fps) % anim.frameCount : 0;

        for (AnimationData.AnimationTrack track : anim.tracks) {
            // Map VMD bone name to MDL bone name
            String mdlBoneName = mapVmdBoneNameToMdl(track.boneName, modelData);
            int boneIndex = findBoneIndex(modelData, mdlBoneName);
            if (boneIndex < 0) continue;

            AnimationData.KeyFrame kf = findKeyFrame(track.keyFrames, currentFrame);
            if (kf == null) continue;

            // Create animation delta matrix
            org.joml.Matrix4f deltaMat = new org.joml.Matrix4f();
            deltaMat.identity();
            if (kf.translation != null) {
                deltaMat.translate(kf.translation[0], kf.translation[1], kf.translation[2]);
            }
            if (kf.rotation != null) {
                float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, kf.rotation[3]))));
                float s = (float) Math.sqrt(1.0 - kf.rotation[3] * kf.rotation[3]);
                if (s > 0.001f) {
                    float invS = 1.0f / s;
                    deltaMat.rotate(angle, kf.rotation[0] * invS, kf.rotation[1] * invS, kf.rotation[2] * invS);
                }
            }
            if (kf.scale != null) {
                deltaMat.scale(kf.scale[0], kf.scale[1], kf.scale[2]);
            }

            // Multiply bind pose * animation delta
            org.joml.Matrix4f bindPose = new org.joml.Matrix4f();
            bindPose.set(localTransforms[boneIndex]);
            bindPose.mul(deltaMat);
            bindPose.get(localTransforms[boneIndex]);
        }
    }

    /**
     * Map VMD bone names (e.g., "Bip01 Head") to MDL bone names (e.g., "ValveBiped.Bip01_Head").
     * Uses fuzzy matching with common Source Engine naming patterns.
     */
    private static String mapVmdBoneNameToMdl(String vmdName, SourceModelData modelData) {
        if (modelData == null || modelData.bones.isEmpty()) return vmdName;

        // First try exact match
        for (SourceModelData.BoneInfo bone : modelData.bones) {
            if (bone.name.equalsIgnoreCase(vmdName)) {
                return bone.name;
            }
        }

        // Try common VMD -> MDL name mappings
        String normalized = vmdName.toLowerCase();
        
        // Common patterns: "Bip01 X" -> "ValveBiped.Bip01_X" or "Bip01_X"
        String[] patterns = {
            vmdName.replace(" ", "_"),           // "Bip01 Head" -> "Bip01_Head"
            "ValveBiped." + vmdName.replace(" ", "_"),
            "Bip01_" + vmdName.substring(vmdName.indexOf(' ') + 1).replace(" ", "_"),
        };

        for (String pattern : patterns) {
            for (SourceModelData.BoneInfo bone : modelData.bones) {
                if (bone.name.equalsIgnoreCase(pattern)) {
                    return bone.name;
                }
            }
        }

        // Fuzzy match: check if MDL bone name contains VMD bone name parts
        String[] vmdParts = vmdName.toLowerCase().split("[ _]");
        for (SourceModelData.BoneInfo bone : modelData.bones) {
            String mdlLower = bone.name.toLowerCase();
            int matches = 0;
            for (String part : vmdParts) {
                if (part.length() > 2 && mdlLower.contains(part)) {
                    matches++;
                }
            }
            if (matches >= 2) { // At least 2 parts match
                return bone.name;
            }
        }

        return vmdName; // Fallback to original
    }

    private static int findBoneIndex(SourceModelData modelData, String boneName) {
        for (int i = 0; i < modelData.bones.size(); i++) {
            if (modelData.bones.get(i).name.equalsIgnoreCase(boneName)) {
                return i;
            }
        }
        return -1;
    }

    private static AnimationData.KeyFrame findKeyFrame(List<AnimationData.KeyFrame> keyFrames, int frame) {
        if (keyFrames == null || keyFrames.isEmpty()) return null;
        AnimationData.KeyFrame closest = null;
        int minDiff = Integer.MAX_VALUE;
        for (AnimationData.KeyFrame kf : keyFrames) {
            int diff = Math.abs(kf.frame - frame);
            if (diff < minDiff) {
                minDiff = diff;
                closest = kf;
            }
        }
        return closest;
    }

    public static void setCurrentMorph(String morphName) {
        currentMorph = morphName;
    }

    public static String getCurrentMorph() {
        return currentMorph;
    }

    public static void clearMorph() {
        currentMorph = null;
    }

    public static void cleanup() {
        animationRegistry.clear();
        defaultAnimRegistry.clear();
        customAnimRegistry.clear();
        currentMorph = null;
        LOGGER.info("[AnimationProcessor] Cleared all animations");
    }
}