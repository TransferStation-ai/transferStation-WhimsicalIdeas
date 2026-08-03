// AnimationProcessor.java - core animation processing class for managing models
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.GmodModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.MdlDataTypes;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;
import transferstation.transferstation_whimsicalideas.client.morph.MorphManager;

import java.nio.file.Path;
import java.util.*;

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
     * <p> 
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

        // 4. Sample base layer deltas (with frame interpolation)
        Map<Integer, float[]> deltas = sampleAnimationDeltas(entity, anim, modelData, partialTicks);

        // 4b. Overlay layer: blend gesture deltas on top (per-bone weight = layerWeight * boneMask)
        AnimationLayers.tickFades(entity, (entity.tickCount + partialTicks) / 20.0f);
        AnimationLayers.LayerState overlay = AnimationLayers.getActiveOverlay(entity, AnimationLayers.OVERLAY);
        if (overlay != null) {
            float layerWeight = AnimationLayers.fadeWeight(overlay) * overlay.weight;
            Map<Integer, float[]> overlayDeltas = sampleAnimationDeltas(entity, overlay.anim, modelData, partialTicks);
            for (Map.Entry<Integer, float[]> entry : overlayDeltas.entrySet()) {
                int boneIdx = entry.getKey();
                String boneName = modelData.bones.get(boneIdx).name();
                if (AnimationLayers.isMaskedOut(entity, AnimationLayers.OVERLAY, boneName)) continue;
                deltas.compute(boneIdx, (k, base) -> blendDeltas(Objects.requireNonNullElseGet(base, AnimationProcessor::toIdentityDelta), entry.getValue(), layerWeight));
            }
        }

        // 4c. Apply mixed deltas on top of bind pose
        for (Map.Entry<Integer, float[]> entry : deltas.entrySet()) {
            org.joml.Matrix4f bindPose = new org.joml.Matrix4f();
            bindPose.set(localTransforms[entry.getKey()]);
            bindPose.mul(deltaToMatrix(entry.getValue()));
            bindPose.get(localTransforms[entry.getKey()]);
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

                Matrix4f morphMat = getMatrix4f(entry);

                org.joml.Matrix4f existing = new org.joml.Matrix4f();
                existing.set(localTransforms[boneIdx]);
                existing.mul(morphMat);
                existing.get(localTransforms[boneIdx]);
            }
        }

        return localTransforms;
    }

    private static @NotNull Matrix4f getMatrix4f(Map.Entry<String, float[]> entry) {
        float[] t = entry.getValue();
        Matrix4f morphMat = new Matrix4f();
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
        return morphMat;
    }

    /**
     * Compute the world-space transform for bone {@code index}, recursively
     * ensuring its parent is computed first. {@code computed} guards against
     * cycles so there is no infinite recursion.
     */
    private static void computeWorldBone(int index, SourceModelData modelData,
                                         float[][] localTransforms, Set<Integer> computed) {
        if (index < 0 || index >= modelData.bones.size() || computed.contains(index)) return;
        int parent = modelData.bones.get(index).parent();
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
                Matrix4f mSrc = getMatrix4f(bt);
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
            Matrix4f mSrc = getMatrix4f(modelData, i);
            org.joml.Matrix4f mat = new org.joml.Matrix4f(S).mul(mSrc).mul(S.transpose());
            mat.get(localTransforms[i]);
        }
    }

    private static @NotNull Matrix4f getMatrix4f(MdlDataTypes.SrcBoneTransform bt) {
        Matrix4f mSrc = new Matrix4f();
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
        return mSrc;
    }

    private static @NotNull Matrix4f getMatrix4f(SourceModelData modelData, int i) {
        SourceModelData.BoneInfo bone = modelData.bones.get(i);
        Matrix4f mSrc = new Matrix4f();
        mSrc.identity();
        if (bone.pos() != null) {
            mSrc.translate(bone.pos()[0], bone.pos()[1], bone.pos()[2]);
        }
        if (bone.quat() != null) {
            float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, bone.quat()[3]))));
            float s = (float) Math.sqrt(1.0 - bone.quat()[3] * bone.quat()[3]);
            if (s > 0.001f) {
                float invS = 1.0f / s;
                mSrc.rotate(angle, bone.quat()[0] * invS, bone.quat()[1] * invS, bone.quat()[2] * invS);
            }
        }
        if (bone.rot() != null) {
            // Euler rotation fallback (radians)
            mSrc.rotateXYZ(bone.rot()[0], bone.rot()[1], bone.rot()[2]);
        }
        return mSrc;
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

    /** delta 分量布局：{tx,ty,tz, qx,qy,qz,qw, sx,sy,sz} */
    static final int DELTA_LEN = 10;

    /**
     * 按浮点帧号采样轨道，相邻两 keyframe 间插值：
     * 平移/缩放 lerp，旋转 nlerp。f 越界时 clamp 到首尾帧。
     * 返回 DELTA_LEN 数组；无关键帧返回 null。
     */
    static float[] sampleTrackAtTime(AnimationData.AnimationTrack track, float frameFloat) {
        if (track == null || track.keyFrames == null || track.keyFrames.isEmpty()) return null;
        List<AnimationData.KeyFrame> kfs = track.keyFrames;
        // 关键帧按 frame 升序（VMD 解析保证，防御性拷贝排序避免改坏原始数据）
        if (kfs.size() > 1 && kfs.get(0).frame > kfs.get(1).frame) {
            kfs = new ArrayList<>(kfs);
            kfs.sort(Comparator.comparingInt(a -> a.frame));
        }

        AnimationData.KeyFrame first = kfs.get(0);
        AnimationData.KeyFrame last = kfs.get(kfs.size() - 1);
        if (frameFloat <= first.frame) return toDelta(first);
        if (frameFloat >= last.frame) return toDelta(last);

        for (int i = 0; i < kfs.size() - 1; i++) {
            AnimationData.KeyFrame a = kfs.get(i);
            AnimationData.KeyFrame b = kfs.get(i + 1);
            if (frameFloat >= a.frame && frameFloat <= b.frame) {
                float t = (b.frame > a.frame) ? (frameFloat - a.frame) / (b.frame - a.frame) : 0;
                float[] da = toDelta(a);
                float[] db = toDelta(b);
                return blendDeltas(da, db, t);
            }
        }
        return toDelta(last);
    }

    private static float[] toDelta(AnimationData.KeyFrame kf) {
        float[] d = new float[DELTA_LEN];
        if (kf.translation != null) {
            d[0] = kf.translation[0]; d[1] = kf.translation[1]; d[2] = kf.translation[2];
        }
        if (kf.rotation != null) {
            d[3] = kf.rotation[0]; d[4] = kf.rotation[1]; d[5] = kf.rotation[2]; d[6] = kf.rotation[3];
        } else {
            d[6] = 1.0f; // 无旋转 = 恒等四元数
        }
        if (kf.scale != null) {
            d[7] = kf.scale[0]; d[8] = kf.scale[1]; d[9] = kf.scale[2];
        } else {
            d[7] = 1.0f; d[8] = 1.0f; d[9] = 1.0f;
        }
        return d;
    }

    /** 四元数 nlerp（自动处理相反方向），返回归一化结果。 */
    static float[] nlerpQuat(float[] a, float[] b, float t) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float[] b2 = (dot < 0) ? new float[]{-b[0], -b[1], -b[2], -b[3]} : b;
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = a[i] + (b2[i] - a[i]) * t;
        }
        float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
        if (len > 1e-6f) {
            for (int i = 0; i < 4; i++) out[i] /= len;
        } else {
            out[3] = 1.0f;
        }
        return out;
    }

    /**
     * 按权重混合 base/overlay delta：平移/缩放 lerp，旋转 nlerp。
     * weight clamp 到 [0,1]。
     */
    static float[] blendDeltas(float[] base, float[] overlay, float weight) {
        float t = Math.max(0.0f, Math.min(1.0f, weight));
        float[] out = new float[DELTA_LEN];
        for (int i = 0; i < 3; i++) out[i] = base[i] + (overlay[i] - base[i]) * t;   // 平移
        float[] rot = nlerpQuat(new float[]{base[3], base[4], base[5], base[6]},
                                new float[]{overlay[3], overlay[4], overlay[5], overlay[6]}, t);
        out[3] = rot[0]; out[4] = rot[1]; out[5] = rot[2]; out[6] = rot[3];
        for (int i = 7; i < DELTA_LEN; i++) out[i] = base[i] + (overlay[i] - base[i]) * t; // 缩放
        return out;
    }

    /**
     * 采样单层动画，返回 boneIndex → delta 分量 {tx,ty,tz,qx,qy,qz,qw,sx,sy,sz}。
     * 动画 delta 相对 bind pose（VMD 惯例）。
     */
    private static Map<Integer, float[]> sampleAnimationDeltas(LivingEntity entity, AnimationData anim,
                                                                SourceModelData modelData, float partialTicks) {
        Map<Integer, float[]> deltas = new HashMap<>();
        if (anim == null || anim.tracks.isEmpty()) return deltas;

        float elapsedSec = (entity.tickCount + partialTicks) / 20.0f;
        float frameFloat = elapsedSec * anim.fps;
        if (anim.loop && anim.frameCount > 0) {
            frameFloat = frameFloat % anim.frameCount;
        } else if (anim.frameCount > 0) {
            frameFloat = Math.min(frameFloat, anim.frameCount - 1);
        }

        for (AnimationData.AnimationTrack track : anim.tracks) {
            String mdlBoneName = mapVmdBoneNameToMdl(track.boneName, modelData);
            int boneIndex = findBoneIndex(modelData, mdlBoneName);
            if (boneIndex < 0) continue;
            float[] d = sampleTrackAtTime(track, frameFloat);
            if (d != null) deltas.put(boneIndex, d);
        }
        return deltas;
    }

    /** delta 分量 → 变换矩阵（T * R * S）。 */
    private static org.joml.Matrix4f deltaToMatrix(float[] d) {
        org.joml.Matrix4f m = new org.joml.Matrix4f();
        m.identity();
        m.translate(d[0], d[1], d[2]);
        float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, d[6]))));
        float s = (float) Math.sqrt(Math.max(0.0f, 1.0f - d[6] * d[6]));
        if (s > 0.001f) {
            float invS = 1.0f / s;
            m.rotate(angle, d[3] * invS, d[4] * invS, d[5] * invS);
        }
        m.scale(d[7], d[8], d[9]);
        return m;
    }

    private static float[] toIdentityDelta() {
        return new float[]{0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
    }

    /**
     * Map VMD bone names (e.g., "Bip01 Head") to MDL bone names (e.g., "ValveBiped.Bip01_Head").
     * Uses fuzzy matching with common Source Engine naming patterns.
     */
    private static String mapVmdBoneNameToMdl(String vmdName, SourceModelData modelData) {
        if (modelData == null || modelData.bones.isEmpty()) return vmdName;

        // First try exact match
        for (SourceModelData.BoneInfo bone : modelData.bones) {
            if (bone.name().equalsIgnoreCase(vmdName)) {
                return bone.name();
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
                if (bone.name().equalsIgnoreCase(pattern)) {
                    return bone.name();
                }
            }
        }

        // Fuzzy match: check if MDL bone name contains VMD bone name parts
        String[] vmdParts = vmdName.toLowerCase().split("[ _]");
        for (SourceModelData.BoneInfo bone : modelData.bones) {
            String mdlLower = bone.name().toLowerCase();
            int matches = 0;
            for (String part : vmdParts) {
                if (part.length() > 2 && mdlLower.contains(part)) {
                    matches++;
                }
            }
            if (matches >= 2) { // At least 2 parts match
                return bone.name();
            }
        }

        return vmdName; // Fallback to original
    }

    private static int findBoneIndex(SourceModelData modelData, String boneName) {
        for (int i = 0; i < modelData.bones.size(); i++) {
            if (modelData.bones.get(i).name().equalsIgnoreCase(boneName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Compute world-space bone matrices for a neutral stance (reference pose →
     * A-pose → bind pose fallback) without requiring a living entity. Used by the
     * model debug screen to show the model in its rest pose.
     */
    public static float[][] getReferencePoseBoneTransforms(SourceModelData modelData) {
        if (modelData == null || modelData.bones.isEmpty()) return null;
        int boneCount = modelData.bones.size();
        float[][] localTransforms = new float[boneCount][16];

        // 1. Bind pose init (prefers reference pose from srcBoneTransforms, else bone pos/quat)
        initializeBindPose(modelData, localTransforms);

        // 2. No reference pose source (srcBoneTransforms empty) but A-pose frame data
        // exists: apply the A-pose as the neutral stance, matching the entity path.
        if (modelData.srcBoneTransforms.isEmpty() && modelData.hasAPose()) {
            MdlDataTypes.AnimFrameData aPose = modelData.getAPoseFrameData();
            if (aPose != null && !aPose.boneTransforms.isEmpty()) {
                for (MdlDataTypes.AnimFrameBone fb : aPose.boneTransforms) {
                    int idx = findBoneIndex(modelData, fb.boneName);
                    if (idx < 0 || idx >= boneCount) continue;
                    org.joml.Matrix4f bind = new org.joml.Matrix4f();
                    bind.set(localTransforms[idx]);
                    bind.mul(deltaToMatrix(toFrameDelta(fb)));
                    bind.get(localTransforms[idx]);
                }
            }
        }

        // 3. Convert local → world space by walking the hierarchy
        Set<Integer> computed = new HashSet<>();
        for (int i = 0; i < boneCount; i++) {
            computeWorldBone(i, modelData, localTransforms, computed);
        }
        return localTransforms;
    }

    /** {@code AnimFrameBone} pos/quat/scale → delta 分量（与 KeyFrame delta 布局一致）。 */
    private static float[] toFrameDelta(MdlDataTypes.AnimFrameBone fb) {
        float[] d = new float[DELTA_LEN];
        if (fb.pos != null) {
            d[0] = fb.pos[0]; d[1] = fb.pos[1]; d[2] = fb.pos[2];
        }
        if (fb.quat != null) {
            d[3] = fb.quat[0]; d[4] = fb.quat[1]; d[5] = fb.quat[2]; d[6] = fb.quat[3];
        } else {
            d[6] = 1.0f;
        }
        if (fb.scale != null) {
            d[7] = fb.scale[0]; d[8] = fb.scale[1]; d[9] = fb.scale[2];
        } else {
            d[7] = 1.0f; d[8] = 1.0f; d[9] = 1.0f;
        }
        return d;
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