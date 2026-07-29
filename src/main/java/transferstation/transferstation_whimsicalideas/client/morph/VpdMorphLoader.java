package transferstation.transferstation_whimsicalideas.client.morph;

import com.mojang.logging.LogUtils;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VpdMorphLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static class VpdBoneData {
        public String boneName;
        public Vector3f translation;
        public float[] rotation;  // quaternion x, y, z, w

        public VpdBoneData(String boneName, Vector3f translation, float rx, float ry, float rz) {
            this.boneName = boneName;
            this.translation = translation;
            this.rotation = eulerToQuaternion(rx, ry, rz);
        }

        private static float[] eulerToQuaternion(float rx, float ry, float rz) {
            float cx = (float) Math.cos(rx * 0.5f);
            float sx = (float) Math.sin(rx * 0.5f);
            float cy = (float) Math.cos(ry * 0.5f);
            float sy = (float) Math.sin(ry * 0.5f);
            float cz = (float) Math.cos(rz * 0.5f);
            float sz = (float) Math.sin(rz * 0.5f);
            return new float[]{
                sx * cy * cz - cx * sy * sz,
                cx * sy * cz + sx * cy * sz,
                cx * cy * sz - sx * sy * cz,
                cx * cy * cz + sx * sy * sz
            };
        }
    }

    public static class VpdMorphData {
        public String name;
        public List<VpdBoneData> boneDataList = new ArrayList<>();

        public VpdMorphData(String name) {
            this.name = name;
        }
    }

    public static VpdMorphData loadFromFile(Path filePath) throws IOException {
        byte[] data = Files.readAllBytes(filePath);
        String name = filePath.getFileName().toString();
        if (name.toLowerCase().endsWith(".vpd")) {
            name = name.substring(0, name.length() - 4);
        }
        return parseVpd(data, name);
    }

    public static VpdMorphData parseVpd(byte[] data, String morphName) {
        String content = new String(data, StandardCharsets.UTF_8).trim();
        VpdMorphData morph = new VpdMorphData(morphName);

        String[] lines = content.split("\\r?\\n");

        Pattern bonePattern = Pattern.compile("(\\S+)\\s*\\{\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*\\}\\s*\\{\\s*(-?[\\d.]+)\\s+(-?[\\d.]+)\\s+(-?[\\d.]+)\\s*\\}");
        Pattern boneNamePattern = Pattern.compile("^(\\S+)\\s*\\{");

        int expectedBoneCount = 0;
        int bonesRead = 0;
        boolean inBoneCount = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("//")) continue;
            if (line.startsWith("Vocaloid Pose Data file") || line.startsWith("VPD")) continue;
            if (line.matches("^-?\\d+(\\.\\d+)?(E[+-]?\\d+)?$") && i < 3) continue;

            Matcher countMatcher = Pattern.compile("^(\\d+)\\s*\\{").matcher(line);
            if (countMatcher.find()) {
                expectedBoneCount = Integer.parseInt(countMatcher.group(1));
                continue;
            }

            if (line.contains("{") && !line.contains("}")) {
                Matcher nameMatcher = boneNamePattern.matcher(line);
                if (nameMatcher.find()) {
                    String boneName = nameMatcher.group(1);
                    StringBuilder boneBlock = new StringBuilder(line);
                    while (i + 1 < lines.length) {
                        i++;
                        boneBlock.append("\n").append(lines[i]);
                        if (lines[i].contains("}") && lines[i].contains("}")) break;
                        if (lines[i].contains("}") && i + 1 < lines.length) {
                            String next = lines[i + 1].trim();
                            if (next.contains("}") || next.isEmpty() || next.matches("^\\d+\\s*\\{") || boneNamePattern.matcher(next).find()) {
                                break;
                            }
                            if (next.contains("{") && !next.contains("}")) {
                                break;
                            }
                        }
                    }

                    String block = boneBlock.toString();
                    Matcher matcher = bonePattern.matcher(block);
                    if (matcher.find()) {
                        try {
                            float tx = Float.parseFloat(matcher.group(2));
                            float ty = Float.parseFloat(matcher.group(3));
                            float tz = Float.parseFloat(matcher.group(4));
                            float rx = Float.parseFloat(matcher.group(5));
                            float ry = Float.parseFloat(matcher.group(6));
                            float rz = Float.parseFloat(matcher.group(7));
                            morph.boneDataList.add(new VpdBoneData(boneName, new Vector3f(tx, ty, tz), rx, ry, rz));
                            bonesRead++;
                        } catch (NumberFormatException e) {
                            LOGGER.debug("[VpdMorphLoader] Failed to parse bone data for '{}'", boneName);
                        }
                    }
                }
                continue;
            }

            if (line.contains("}") && line.contains("}")) {
                continue;
            }
        }

        LOGGER.info("[VpdMorphLoader] Loaded morph '{}' with {} bones", morphName, morph.boneDataList.size());
        return morph;
    }
}
