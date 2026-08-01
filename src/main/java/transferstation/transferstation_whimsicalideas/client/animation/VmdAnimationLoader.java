// VmdAnimationLoader.java - Loads animation data from standard VMD format
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class VmdAnimationLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float[] IDENTITY_QUATERNION = {0.0f, 0.0f, 0.0f, 1.0f};

    public static AnimationData loadFromVMD(Path filePath) throws IOException {
        byte[] data = Files.readAllBytes(filePath);
        return parseVMD(data, filePath.toString());
    }

    public static AnimationData loadFromVMD(byte[] data, String source) {
        return parseVMD(data, source);
    }

    /**
     * Parse a standard VMD (Vocaloid Motion Data) file.
     * <p>
     * Standard VMD layout:
     *   Offset 0: 30-byte header ("Vocaloid Motion Data 0002" + padding)
     *   Offset 30: 20-byte model name (Shift-JIS, null-padded)
     *   Offset 50: 4-byte bone frame count
     *   Offset 54: bone frame data (68 bytes each)
     *   Offset 54+N*68: morph/camera/light/shadow/IK frame data
     */
    private static AnimationData parseVMD(byte[] data, String source) {
        if (data.length < 54) {
            throw new IllegalArgumentException("Invalid VMD file: too small");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Read model name at standard VMD offset 30
        buffer.position(30);
        String modelName = readString(buffer, 20);

        // Read bone frame count at standard VMD offset 50
        buffer.position(50);
        int boneFrameCount = buffer.getInt();

        String animName = extractAnimName(source);
        int frameCount = 30; // default, will be updated from actual bone frames

        AnimationData animation = new AnimationData(animName, 30.0f, frameCount, true);
        List<AnimationData.AnimationTrack> trackList = new ArrayList<>();

        // Parse bone frames if present (standard format, each frame is 68 bytes).
        // Bound by the actual remaining buffer so a large but valid VMD is not dropped,
        // and a corrupt count cannot read past the buffer.
        int maxBoneFrames = buffer.remaining() / 68;
        if (boneFrameCount > 0 && boneFrameCount <= maxBoneFrames) {
            parseBoneFrames(buffer, trackList, boneFrameCount);
        } else if (boneFrameCount > maxBoneFrames) {
            LOGGER.warn("[VmdAnimationLoader] Declared boneFrameCount {} exceeds buffer capacity {}; clamping",
                boneFrameCount, maxBoneFrames);
            if (maxBoneFrames > 0) parseBoneFrames(buffer, trackList, maxBoneFrames);
        }

        // If no bone data found, generate procedural animation as fallback
        if (trackList.isEmpty()) {
            generateProceduralTracks(trackList, animName, 30);
        } else {
            // Derive total frame count from the highest frame number found
            int maxFrame = 0;
            for (AnimationData.AnimationTrack track : trackList) {
                for (AnimationData.KeyFrame kf : track.keyFrames) {
                    if (kf.frame > maxFrame) maxFrame = kf.frame;
                }
            }
            frameCount = Math.max(maxFrame + 1, 30);
            animation.frameCount = frameCount;
        }

        for (AnimationData.AnimationTrack track : trackList) {
            animation.addTrack(track);
        }

        return animation;
    }

    /** Extract animation name from a file path string. */
    private static String extractAnimName(String source) {
        String name = source.replace('\\', '/');
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        if (name.endsWith(".vmd")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /**
     * Parse bone frames from a standard VMD file.
     * Bone frames start at offset 54 (after 30-byte header + 20-byte model name + 4-byte count),
     * each 68 bytes: frameNum(4) + boneName(32) + translation(12) + rotation(16) + bezier(4).
     */
    private static void parseBoneFrames(ByteBuffer buffer, List<AnimationData.AnimationTrack> trackList, int boneFrameCount) {
        buffer.position(54);
        for (int i = 0; i < boneFrameCount && buffer.remaining() >= 68; i++) {
            int frame = buffer.getInt();
            String boneName = readString(buffer, 32);

            float[] translation = new float[]{
                buffer.getFloat(), buffer.getFloat(), buffer.getFloat()
            };
            float[] rotation = new float[]{
                buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat()
            };
            float[] scale = new float[]{1.0f, 1.0f, 1.0f};

            buffer.position(buffer.position() + 4); // skip bezier curve data

            // Find or create track for this bone
            AnimationData.AnimationTrack track = null;
            for (AnimationData.AnimationTrack t : trackList) {
                if (t.boneName.equals(boneName)) {
                    track = t;
                    break;
                }
            }
            if (track == null) {
                track = new AnimationData.AnimationTrack(boneName);
                trackList.add(track);
            }
            track.addKeyFrame(new AnimationData.KeyFrame(frame, translation, rotation, scale));
        }
    }

    /**
     * Generate procedural animation tracks when the VMD file has no bone frame data.
     * Uses common Source Engine (BIP01) bone naming conventions.
     * Only bones that exist in the model will be affected.
     */
    private static void generateProceduralTracks(List<AnimationData.AnimationTrack> trackList, String animName, int frameCount) {
        if (frameCount <= 0 || frameCount > 500) frameCount = 30;
        String lower = animName.toLowerCase();

        if (lower.equals("idle")) {
            generateRotTrack(trackList, "Bip01 Spine1", 0, 0.025, 2.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Spine2", 0, 0.02, 2.0, 0.5, frameCount);
            generateRotTrack(trackList, "Bip01 Head", 1, 0.015, 1.2, Math.PI, frameCount);
            generateRotTrack(trackList, "Bip01 Head", 0, 0.01, 2.4, 0, frameCount);
        } else if (lower.contains("walk")) {
            double speed = lower.contains("sprint") ? 8.0 : 5.0;
            double amp = lower.contains("sprint") ? 0.5 : 0.35;
            generateRotTrack(trackList, "Bip01 L Thigh", 0, amp, speed, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R Thigh", 0, amp, speed, Math.PI, frameCount);
            generateRotTrack(trackList, "Bip01 L Calf", 0, amp * 0.35, speed, Math.PI * 0.5, frameCount);
            generateRotTrack(trackList, "Bip01 R Calf", 0, amp * 0.35, speed, Math.PI * 1.5, frameCount);
            generateRotTrack(trackList, "Bip01 L UpperArm", 0, amp * 0.5, speed, Math.PI, frameCount);
            generateRotTrack(trackList, "Bip01 R UpperArm", 0, amp * 0.5, speed, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Spine", 0, 0.03, speed, 0, frameCount);
        } else if (lower.equals("happy") || lower.equals("excited")) {
            double bounce = lower.equals("excited") ? 4.0 : 2.5;
            generateTransTrack(trackList, bounce, 4.0, frameCount);
            generateRotTrack(trackList, "Bip01 Head", 2, 0.1, 4.0, Math.PI, frameCount);
        } else if (lower.equals("wave")) {
            generateFixedRotTrack(trackList, "Bip01 R UpperArm", 0, -1.0, frameCount);
            generateFixedRotTrack(trackList, "Bip01 R UpperArm", 2, 0.3, frameCount);
            generateFixedRotTrack(trackList, "Bip01 R Forearm", 0, -0.8, frameCount);
            generateRotTrack(trackList, "Bip01 R Hand", 0, 0.3, 4.0, 0, frameCount);
        } else if (lower.contains("swing")) {
            boolean isRight = lower.contains("right");
            String side = isRight ? "R" : "L";
            generateRotTrack(trackList, "Bip01 " + side + " UpperArm", 0, 0.8, 6.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 " + side + " Forearm", 0, 0.3, 6.0, Math.PI * 0.5, frameCount);
            generateRotTrack(trackList, "Bip01 Spine", 1, 0.1, 6.0, isRight ? Math.PI : 0, frameCount);
        } else if (lower.equals("swim")) {
            generateRotTrack(trackList, "Bip01 L UpperArm", 2, 0.6, 3.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R UpperArm", 2, 0.6, 3.0, Math.PI, frameCount);
            generateRotTrack(trackList, "Bip01 Spine", 1, 0.15, 3.0, 0, frameCount);
        } else if (lower.equals("sneak")) {
            generateTransTrack(trackList, -3.0, 1.0, frameCount);
            generateRotTrack(trackList, "Bip01 L Thigh", 0, 0.2, 3.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R Thigh", 0, 0.2, 3.0, Math.PI, frameCount);
        } else if (lower.equals("crawl")) {
            generateTransTrack(trackList, -5.0, 1.0, frameCount);
            generateRotTrack(trackList, "Bip01 Spine", 0, 0.5, 1.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 L UpperArm", 0, 0.4, 3.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R UpperArm", 0, 0.4, 3.0, Math.PI, frameCount);
        } else if (lower.equals("die")) {
            generateRotTrack(trackList, "Bip01 Spine", 0, 0.5, 0.5, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Spine1", 0, 0.4, 0.5, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Spine2", 0, 0.3, 0.5, 0, frameCount);
        } else if (lower.equals("sleep")) {
            generateRotTrack(trackList, "Bip01 Spine", 0, 1.3, 0.3, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Spine1", 0, 0.5, 0.3, 0, frameCount);
            generateRotTrack(trackList, "Bip01 L Thigh", 2, 0.4, 0.3, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R Thigh", 2, 0.4, 0.3, 0, frameCount);
        } else if (lower.equals("angry")) {
            generateFixedRotTrack(trackList, "Bip01 L UpperArm", 0, -0.5, frameCount);
            generateFixedRotTrack(trackList, "Bip01 R UpperArm", 0, -0.5, frameCount);
            generateRotTrack(trackList, "Bip01 Spine", 0, 0.15, 2.0, 0, frameCount);
        } else if (lower.equals("sad")) {
            generateRotTrack(trackList, "Bip01 Spine", 0, 0.3, 1.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Head", 0, 0.3, 1.0, 0, frameCount);
        } else if (lower.equals("dance")) {
            generateRotTrack(trackList, "Bip01 Pelvis", 1, 0.2, 4.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 L UpperArm", 0, 0.4, 4.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R UpperArm", 0, 0.4, 4.0, Math.PI, frameCount);
            generateTransTrack(trackList, 1.5, 4.0, frameCount);
        } else if (lower.equals("nod")) {
            generateRotTrack(trackList, "Bip01 Head", 0, 0.3, 3.0, 0, frameCount);
        } else if (lower.equals("shake")) {
            generateRotTrack(trackList, "Bip01 Head", 1, 0.3, 5.0, 0, frameCount);
        } else if (lower.contains("climb")) {
            generateRotTrack(trackList, "Bip01 L UpperArm", 0, 0.5, 4.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 R UpperArm", 0, 0.5, 4.0, Math.PI, frameCount);
            generateTransTrack(trackList, 0.8, 4.0, frameCount);
        } else if (lower.equals("ride") || lower.equals("onhorse")) {
            generateFixedRotTrack(trackList, "Bip01 L Thigh", 0, 0.5, frameCount);
            generateFixedRotTrack(trackList, "Bip01 R Thigh", 0, 0.5, frameCount);
            generateTransTrack(trackList, 1.0, 3.0, frameCount);
        } else if (lower.contains("fall") || lower.equals("elytrafly")) {
            if (lower.equals("elytrafly")) {
                generateFixedRotTrack(trackList, "Bip01 L UpperArm", 2, 0.8, frameCount);
                generateFixedRotTrack(trackList, "Bip01 R UpperArm", 2, -0.8, frameCount);
                generateFixedRotTrack(trackList, "Bip01 L UpperArm", 0, -0.3, frameCount);
                generateFixedRotTrack(trackList, "Bip01 R UpperArm", 0, -0.3, frameCount);
            } else {
                generateRotTrack(trackList, "Bip01 L UpperArm", 0, 0.6, 8.0, 0, frameCount);
                generateRotTrack(trackList, "Bip01 R UpperArm", 0, 0.6, 8.0, Math.PI, frameCount);
                generateRotTrack(trackList, "Bip01 L Thigh", 0, 0.3, 8.0, 0, frameCount);
                generateRotTrack(trackList, "Bip01 R Thigh", 0, 0.3, 8.0, Math.PI, frameCount);
            }
        } else if (lower.contains("itemactive")) {
            generateFixedRotTrack(trackList, "Bip01 R UpperArm", 0, -0.8, frameCount);
            generateFixedRotTrack(trackList, "Bip01 R Forearm", 0, -0.5, frameCount);
        } else {
            generateRotTrack(trackList, "Bip01 Spine1", 0, 0.02, 2.0, 0, frameCount);
            generateRotTrack(trackList, "Bip01 Head", 1, 0.02, 1.5, Math.PI, frameCount);
        }
    }

    /** Add a track with sinusoidal rotation on a single axis. */
    private static void generateRotTrack(List<AnimationData.AnimationTrack> trackList, String boneName,
                                          int axis, double amplitude, double frequency, double phase, int frameCount) {
        if (frameCount <= 0) return;
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack(boneName);
        for (int f = 0; f < frameCount; f++) {
            double t = (f / (double) frameCount) * frequency * 2.0 * Math.PI + phase;
            float angle = (float)(amplitude * Math.sin(t));
            float[] quat = axisAngleToQuat(axis, angle);
            track.addKeyFrame(new AnimationData.KeyFrame(f, new float[]{0, 0, 0}, quat, new float[]{1, 1, 1}));
        }
        trackList.add(track);
    }

    /** Add a track with sinusoidal translation on a single axis. */
    private static void generateTransTrack(List<AnimationData.AnimationTrack> trackList,
                                           double amplitude, double frequency, int frameCount) {
        if (frameCount <= 0) return;
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("Bip01");
        for (int f = 0; f < frameCount; f++) {
            double t = (f / (double) frameCount) * frequency * 2.0 * Math.PI + (double) 0;
            float value = (float)(amplitude * Math.sin(t));
            float[] trans = new float[]{0, 0, 0};
            trans[1] = value;
            track.addKeyFrame(new AnimationData.KeyFrame(f, trans, IDENTITY_QUATERNION, new float[]{1, 1, 1}));
        }
        trackList.add(track);
    }

    /** Add a track with a fixed (non-oscillating) rotation. */
    private static void generateFixedRotTrack(List<AnimationData.AnimationTrack> trackList, String boneName,
                                                int axis, double angle, int frameCount) {
        if (frameCount <= 0) return;
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack(boneName);
        float[] quat = axisAngleToQuat(axis, (float)angle);
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{0, 0, 0}, quat, new float[]{1, 1, 1}));
        trackList.add(track);
    }

    /** Convert an axis-index and angle to a quaternion [x, y, z, w]. */
    private static float[] axisAngleToQuat(int axis, float angle) {
        float[] quat = new float[4];
        float half = angle * 0.5f;
        float sinHalf = (float) Math.sin(half);
        float cosHalf = (float) Math.cos(half);
        quat[0] = (axis == 0) ? sinHalf : 0;
        quat[1] = (axis == 1) ? sinHalf : 0;
        quat[2] = (axis == 2) ? sinHalf : 0;
        quat[3] = cosHalf;
        return quat;
    }

    /** Read a fixed-length null-terminated string from the buffer. */
    private static String readString(ByteBuffer buffer, int maxLength) {
        byte[] bytes = new byte[maxLength];
        buffer.get(bytes);
        int nullPos = 0;
        while (nullPos < maxLength && bytes[nullPos] != 0) {
            nullPos++;
        }
        String result = new String(bytes, 0, nullPos);
        if (result.isEmpty()) {
            result = "default";
        }
        return result;
    }

    public static AnimationData createDefaultVMDAnimation(String name, int frameCount) {
        AnimationData animation = new AnimationData(name, 30.0f, frameCount, true);
        AnimationData.AnimationTrack rootTrack = new AnimationData.AnimationTrack("root");
        rootTrack.addKeyFrame(new AnimationData.KeyFrame(
            0, new float[]{0, 0, 0}, IDENTITY_QUATERNION, new float[]{1, 1, 1}
        ));
        animation.addTrack(rootTrack);
        return animation;
    }
}