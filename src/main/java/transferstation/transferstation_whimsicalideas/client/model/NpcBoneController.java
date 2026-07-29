package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NpcBoneController {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, BoneState> boneStates = new ConcurrentHashMap<>();
    private static final Map<String, List<BoneKeyframe>> animationQueues = new ConcurrentHashMap<>();

    // Source engine bone name patterns for animation targeting
    private static final Map<String, List<String>> BONE_NAME_PATTERNS = new LinkedHashMap<>();
    static {
        BONE_NAME_PATTERNS.put("head", Arrays.asList("head", "Head", "HEAD", "bip01 head", "Bip01 Head", "Bip01_Head", "ValveBiped.Bip01_Head"));
        BONE_NAME_PATTERNS.put("right_arm", Arrays.asList("r_upperarm", "R_UpperArm", "right arm", "RightArm", "r arm", "r_arm", "ValveBiped.Bip01_R_UpperArm", "Bip01_R_UpperArm", "R UpperArm"));
        BONE_NAME_PATTERNS.put("left_arm", Arrays.asList("l_upperarm", "L_UpperArm", "left arm", "LeftArm", "l arm", "l_arm", "ValveBiped.Bip01_L_UpperArm", "Bip01_L_UpperArm", "L UpperArm"));
        BONE_NAME_PATTERNS.put("right_hand", Arrays.asList("r_hand", "R_Hand", "right hand", "RightHand", "ValveBiped.Bip01_R_Hand", "Bip01_R_Hand"));
        BONE_NAME_PATTERNS.put("left_hand", Arrays.asList("l_hand", "L_Hand", "left hand", "LeftHand", "ValveBiped.Bip01_L_Hand", "Bip01_L_Hand"));
        BONE_NAME_PATTERNS.put("right_leg", Arrays.asList("r_thigh", "R_Thigh", "right leg", "RightLeg", "r_leg", "ValveBiped.Bip01_R_Thigh", "Bip01_R_Thigh"));
        BONE_NAME_PATTERNS.put("left_leg", Arrays.asList("l_thigh", "L_Thigh", "left leg", "LeftLeg", "l_leg", "ValveBiped.Bip01_L_Thigh", "Bip01_L_Thigh"));
        BONE_NAME_PATTERNS.put("right_foot", Arrays.asList("r_foot", "R_Foot", "right foot", "RightFoot", "ValveBiped.Bip01_R_Foot", "Bip01_R_Foot"));
        BONE_NAME_PATTERNS.put("left_foot", Arrays.asList("l_foot", "L_Foot", "left foot", "LeftFoot", "ValveBiped.Bip01_L_Foot", "Bip01_L_Foot"));
        BONE_NAME_PATTERNS.put("body", Arrays.asList("spine", "Spine", "SPINE", "bip01 spine", "Bip01 Spine", "Bip01_Spine", "ValveBiped.Bip01_Spine", "Pelvis", "pelvis", "ValveBiped.Bip01_Pelvis"));
        BONE_NAME_PATTERNS.put("pelvis", Arrays.asList("pelvis", "Pelvis", "PELVIS", "bip01 pelvis", "ValveBiped.Bip01_Pelvis", "Bip01_Pelvis"));
    }

    /**
     * Resolve a friendly bone name (e.g. "head", "right_arm") to the actual bone name
     * used by the model by fuzzy-matching against the model's bone list.
     */
    public static String resolveBoneName(String friendlyName, List<SourceModelData.BoneInfo> modelBones) {
        if (modelBones == null || modelBones.isEmpty()) return friendlyName;

        // Check if friendlyName already exists in the model's bones
        for (SourceModelData.BoneInfo bone : modelBones) {
            if (bone.name.equalsIgnoreCase(friendlyName)) {
                return bone.name;
            }
        }

        // Try pattern matching
        List<String> patterns = BONE_NAME_PATTERNS.get(friendlyName);
        if (patterns != null) {
            for (String pattern : patterns) {
                for (SourceModelData.BoneInfo bone : modelBones) {
                    if (bone.name.toLowerCase().contains(pattern.toLowerCase())) {
                        return bone.name;
                    }
                }
            }
        }

        // Fallback: try substring match
        String lower = friendlyName.toLowerCase();
        for (SourceModelData.BoneInfo bone : modelBones) {
            if (bone.name.toLowerCase().contains(lower)) {
                return bone.name;
            }
        }

        return friendlyName;
    }

    public static class BoneState {
        public Vector3f position = new Vector3f(0, 0, 0);
        public Vector3f rotation = new Vector3f(0, 0, 0);
        public Vector3f scale = new Vector3f(1, 1, 1);
        public Vector3f targetPosition = null;
        public Vector3f targetRotation = null;
        public float interpolationSpeed = 0.15f;

        public Matrix4f toMatrix() {
            Matrix4f matrix = new Matrix4f();
            matrix.identity();
            matrix.translate(position);
            matrix.rotateXYZ(rotation);
            matrix.scale(scale);
            return matrix;
        }
    }

    public static class BoneKeyframe {
        public final int tick;
        public final Vector3f position;
        public final Vector3f rotation;
        public final Vector3f scale;

        public BoneKeyframe(int tick, Vector3f position, Vector3f rotation, Vector3f scale) {
            this.tick = tick;
            this.position = position != null ? position : new Vector3f(0, 0, 0);
            this.rotation = rotation != null ? rotation : new Vector3f(0, 0, 0);
            this.scale = scale != null ? scale : new Vector3f(1, 1, 1);
        }
    }

    public static void setBonePosition(String entityId, String boneName, Vector3f position) {
        String key = entityId + ":" + boneName;
        BoneState state = boneStates.computeIfAbsent(key, k -> new BoneState());
        state.targetPosition = new Vector3f(position);
    }

    public static void setBoneRotation(String entityId, String boneName, Vector3f rotation) {
        String key = entityId + ":" + boneName;
        BoneState state = boneStates.computeIfAbsent(key, k -> new BoneState());
        state.targetRotation = new Vector3f(rotation);
    }

    public static void setBoneScale(String entityId, String boneName, Vector3f scale) {
        String key = entityId + ":" + boneName;
        BoneState state = boneStates.computeIfAbsent(key, k -> new BoneState());
        state.scale = new Vector3f(scale);
    }

    public static void resetBone(String entityId, String boneName) {
        String key = entityId + ":" + boneName;
        boneStates.remove(key);
    }

    public static void resetAllBones(String entityId) {
        clearEntity(entityId);
    }

    /**
     * Removes all bone state and animation queue entries for the given entity.
     * Keys are namespaced as {@code entityId + ":" + boneName}, so evicting by
     * prefix prevents the static maps from growing unbounded after an entity is
     * removed from the level.
     */
    public static void clearEntity(String entityId) {
        String prefix = entityId + ":";
        boneStates.keySet().removeIf(k -> k.startsWith(prefix));
        animationQueues.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Convenience overload for entities identified by UUID.
     */
    public static void clearEntity(java.util.UUID entityId) {
        clearEntity(entityId.toString());
    }

    public static Matrix4f getBoneTransform(String entityId, String boneName) {
        String key = entityId + ":" + boneName;
        BoneState state = boneStates.get(key);
        if (state == null) {
            return new Matrix4f().identity();
        }
        return state.toMatrix();
    }

    public static void queueAnimation(String entityId, List<BoneKeyframe> keyframes) {
        animationQueues.put(entityId, new ArrayList<>(keyframes));
    }

    public static void playWaveAnimation(String entityId, List<SourceModelData.BoneInfo> modelBones) {
        String boneName = resolveBoneName("right_arm", modelBones);
        List<BoneKeyframe> keyframes = new ArrayList<>();
        for (int t = 0; t < 40; t++) {
            float angle = (float) Math.sin(t * 0.3) * 0.8f;
            keyframes.add(new BoneKeyframe(t,
                    null,
                    new Vector3f(0, 0, angle),
                    null));
        }
        queueAnimation(entityId + ":" + boneName, keyframes);
    }

    public static void playNodAnimation(String entityId, List<SourceModelData.BoneInfo> modelBones) {
        String boneName = resolveBoneName("head", modelBones);
        List<BoneKeyframe> keyframes = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            float angle = (float) Math.sin(t * 0.5) * 0.3f;
            keyframes.add(new BoneKeyframe(t,
                    null,
                    new Vector3f(angle, 0, 0),
                    null));
        }
        queueAnimation(entityId + ":" + boneName, keyframes);
    }

    public static void playShakeAnimation(String entityId, List<SourceModelData.BoneInfo> modelBones) {
        String boneName = resolveBoneName("head", modelBones);
        List<BoneKeyframe> keyframes = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            float angle = (float) Math.sin(t * 0.8) * 0.2f;
            keyframes.add(new BoneKeyframe(t,
                    null,
                    new Vector3f(0, angle, 0),
                    null));
        }
        queueAnimation(entityId + ":" + boneName, keyframes);
    }

    public static void playDanceAnimation(String entityId, List<SourceModelData.BoneInfo> modelBones) {
        String boneName = resolveBoneName("body", modelBones);
        List<BoneKeyframe> keyframes = new ArrayList<>();
        for (int t = 0; t < 60; t++) {
            float bodyY = (float) Math.sin(t * 0.2) * 0.1f;
            keyframes.add(new BoneKeyframe(t,
                    new Vector3f(0, bodyY, 0),
                    new Vector3f(0, 0, 0),
                    null));
        }
        queueAnimation(entityId + ":" + boneName, keyframes);
    }

    public static void updateBones(long currentTick) {
        for (Map.Entry<String, BoneState> entry : boneStates.entrySet()) {
            BoneState state = entry.getValue();
            if (state.targetPosition != null) {
                state.position.lerp(state.targetPosition, state.interpolationSpeed);
                if (state.position.distance(state.targetPosition) < 0.001f) {
                    state.position.set(state.targetPosition);
                    state.targetPosition = null;
                }
            }
            if (state.targetRotation != null) {
                state.rotation.lerp(state.targetRotation, state.interpolationSpeed);
                if (state.rotation.distance(state.targetRotation) < 0.001f) {
                    state.rotation.set(state.targetRotation);
                    state.targetRotation = null;
                }
            }
        }

        for (Map.Entry<String, List<BoneKeyframe>> entry : animationQueues.entrySet()) {
            String boneKey = entry.getKey();
            List<BoneKeyframe> keyframes = entry.getValue();
            if (keyframes.isEmpty()) continue;

            int animTick = (int) (currentTick % keyframes.size());
            BoneKeyframe kf = keyframes.get(animTick);

            String[] parts = boneKey.split(":");
            if (parts.length == 2) {
                String entityId = parts[0];
                String boneName = parts[1];
                setBonePosition(entityId, boneName, kf.position);
                setBoneRotation(entityId, boneName, kf.rotation);
                setBoneScale(entityId, boneName, kf.scale);
            }
        }
    }

    public static void clearAll() {
        boneStates.clear();
        animationQueues.clear();
    }
}
