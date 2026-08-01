package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class DefaultAnimationExtractor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MARKER_FILE = ".animations_extracted";

    private static final String[] DEFAULT_ANIMATIONS = {
        "idle.vmd", "walk.vmd", "sprint.vmd", "sneak.vmd",
        "swim.vmd", "crawl.vmd", "sleep.vmd", "die.vmd",
        "elytraFly.vmd", "onClimbable.vmd", "onClimbableUp.vmd", "onClimbableDown.vmd",
        "ride.vmd", "onHorse.vmd", "lieDown.vmd",
        "swingLeft.vmd", "swingRight.vmd",
        "wave.vmd", "dance.vmd", "nod.vmd", "shake.vmd",
        "happy.vmd", "sad.vmd", "angry.vmd", "excited.vmd",
        "itemActive_eat.vmd", "itemActive_block.vmd", "itemActive_bow.vmd"
    };

    private static final String[] DEFAULT_MORPHS = {
        "blink.vpd", "smile.vpd", "angry.vpd", "sad.vpd",
        "surprise.vpd", "wink.vpd", "kiss.vpd", "catMouth.vpd"
    };

    public static void extractIfNeeded(Path configDir) {
        Path defaultAnimDir = configDir.resolve("DefaultAnim");
        Path defaultMorphDir = configDir.resolve("DefaultMorph");
        Path markerFile = configDir.resolve(MARKER_FILE);

        if (Files.exists(markerFile)) {
            LOGGER.info("[DefaultAnimationExtractor] Animations already extracted, skipping");
            return;
        }

        try {
            Files.createDirectories(defaultAnimDir);
            Files.createDirectories(defaultMorphDir);

            LOGGER.info("[DefaultAnimationExtractor] Extracting default animations and morphs...");

            for (String anim : DEFAULT_ANIMATIONS) {
                extractResource("/assets/transferstation_whimsicalideas/default_anim/" + anim, defaultAnimDir.resolve(anim));
            }

            for (String morph : DEFAULT_MORPHS) {
                extractResource("/assets/transferstation_whimsicalideas/default_morph/" + morph, defaultMorphDir.resolve(morph));
            }

            Files.createFile(markerFile);
            LOGGER.info("[DefaultAnimationExtractor] Successfully extracted {} animations and {} morphs",
                DEFAULT_ANIMATIONS.length, DEFAULT_MORPHS.length);

        } catch (IOException e) {
            LOGGER.error("[DefaultAnimationExtractor] Failed to extract default animations", e);
        }
    }

    private static void extractResource(String resourcePath, Path targetPath) {
        try (InputStream in = DefaultAnimationExtractor.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                Files.createDirectories(targetPath.getParent());
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.debug("[DefaultAnimationExtractor] Extracted: {} -> {}", resourcePath, targetPath);
            } else {
                LOGGER.warn("[DefaultAnimationExtractor] Resource not found: {}", resourcePath);
                generatePlaceholderAnimation(targetPath);
            }
        } catch (IOException e) {
            LOGGER.warn("[DefaultAnimationExtractor] Failed to extract {}: {}", resourcePath, e.getMessage());
            generatePlaceholderAnimation(targetPath);
        }
    }

    private static void generatePlaceholderAnimation(Path targetPath) {
        try {
            Files.createDirectories(targetPath.getParent());
            String animName = targetPath.getFileName().toString();
            boolean isVmd = animName.toLowerCase().endsWith(".vmd");

            if (isVmd) {
                generateVmdPlaceholder(targetPath, animName);
            } else {
                generateVpdPlaceholder(targetPath, animName);
            }
            LOGGER.debug("[DefaultAnimationExtractor] Generated placeholder: {}", targetPath);
        } catch (IOException e) {
            LOGGER.warn("[DefaultAnimationExtractor] Failed to generate placeholder for {}", targetPath);
        }
    }

    private static void generateVmdPlaceholder(Path targetPath, String name) throws IOException {
        byte[] vmdData = createMinimalVmd(name);
        Files.write(targetPath, vmdData);
    }

    private static void generateVpdPlaceholder(Path targetPath, String name) throws IOException {
        String morphName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String vpdContent = "Vocaloid Pose Data file\n1.0\nmodel\n1{\n" + morphName + "{\n0.000000 0.000000 0.000000\n0.000000 0.000000 0.000000}\n}\n";
        Files.writeString(targetPath, vpdContent);
    }

    private static byte[] createMinimalVmd(String animationName) {
        // Must match VmdAnimationLoader.parseVMD layout exactly:
        //   offset 0-29: 30-byte header (signature, skipped by parser)
        //   offset 30: 20-byte model name (null-padded)
        //   offset 50: 4-byte bone frame count
        //   offset 54: bone frame data (68 bytes each)
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(256);
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);

        // Bytes 0-29: VMD header (signature "Vocaloid Motion Data 0002" etc.);
        // parser skips it and starts reading the model name at offset 30.
        byte[] header = "Vocaloid Motion Data 0002".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        buf.put(header);
        if (header.length < 30) {
            buf.put(new byte[30 - header.length]);
        } else {
            buf.position(30);
        }

        // Bytes 30-49: Model name (20 bytes, null-padded)
        byte[] nameBytes = animationName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int nameLen = Math.min(nameBytes.length, 20);
        buf.put(nameBytes, 0, nameLen);
        if (nameLen < 20) {
            buf.put(new byte[20 - nameLen]);
        }

        // Bytes 50-53: Bone frame count = 1 (so parser doesn't treat as empty)
        buf.putInt(1);

        // Bone frame 1 (68 bytes starting at offset 54)
        //   Frame number = 0
        buf.putInt(0);
        //   Bone name = "root" padded to 32 bytes
        byte[] rootBone = "root".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        buf.put(rootBone);
        if (rootBone.length < 32) {
            buf.put(new byte[32 - rootBone.length]);
        }
        //   Translation = {0, 0, 0}
        buf.putFloat(0.0f);
        buf.putFloat(0.0f);
        buf.putFloat(0.0f);
        //   Rotation = {0, 0, 0, 1} (identity quaternion)
        buf.putFloat(0.0f);
        buf.putFloat(0.0f);
        buf.putFloat(0.0f);
        buf.putFloat(1.0f);
        //   Bezier data (4 bytes)
        buf.putInt(0);

        // Morph frame count = 0
        buf.putInt(0);
        // Camera frame count = 0
        buf.putInt(0);
        // Light frame count = 0
        buf.putInt(0);
        // Self shadow frame count = 0
        buf.putInt(0);
        // IK frame count = 0
        buf.putInt(0);

        // Return only the bytes that were actually written
        byte[] result = new byte[buf.position()];
        buf.flip();
        buf.get(result);
        return result;
    }
}
