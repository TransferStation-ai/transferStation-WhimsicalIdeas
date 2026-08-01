package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SmdParser {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static class SmdBone {
        public int id;
        public String name;
        public int parent;

        public SmdBone(int id, String name, int parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
        }
    }

    public static class SmdVertex {
        public float x, y, z;
        public float nx, ny, nz;
        public float u, v;
        public int[] boneIds;
        public float[] boneWeights;

        public SmdVertex(float x, float y, float z, float nx, float ny, float nz, float u, float v,
                         int[] boneIds, float[] boneWeights) {
            this.x = x; this.y = y; this.z = z;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.u = u; this.v = v;
            this.boneIds = boneIds;
            this.boneWeights = boneWeights;
        }

        public int numBones() {
            return boneIds != null ? boneIds.length : 1;
        }

        public int primaryBone() {
            if (boneIds != null && boneIds.length > 0) return boneIds[0];
            return 0;
        }
    }

    public static class SmdMesh {
        public String materialName;
        public List<SmdVertex> vertices;

        public SmdMesh(String materialName, List<SmdVertex> vertices) {
            this.materialName = materialName;
            this.vertices = vertices;
        }
    }

    public static class ParsedSmd {
        public List<SmdBone> bones;
        public List<SmdMesh> meshes;

        public ParsedSmd() {
            this.bones = new ArrayList<>();
            this.meshes = new ArrayList<>();
        }
    }

    private enum Section {
        NONE, NODES, SKELETON, TRIANGLES
    }

    public static ParsedSmd parse(byte[] data) throws IOException {
        String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        return parseContent(content);
    }

    public static ParsedSmd parse(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        return parse(data);
    }

    private static ParsedSmd parseContent(String content) throws IOException {
        ParsedSmd result = new ParsedSmd();
        Section currentSection = Section.NONE;
        List<SmdVertex> currentTriVerts = null;
        String currentMaterial = null;

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();

                if (line.isEmpty() || line.startsWith("//")) continue;

                if (line.equals("version 1")) continue;

                switch (line.toLowerCase()) {
                    case "nodes":
                        currentSection = Section.NODES;
                        continue;
                    case "skeleton":
                        currentSection = Section.SKELETON;
                        continue;
                    case "triangles":
                        currentSection = Section.TRIANGLES;
                        continue;
                    case "end":
                        currentSection = Section.NONE;
                        continue;
                }

                switch (currentSection) {
                    case NODES:
                        parseNodeLine(line, result);
                        break;
                    case TRIANGLES:
                        parseTriangleLine(line, result, lineNum);
                        break;
                }
            }
        }

        LOGGER.info("[SmdParser] Parsed SMD: {} bones, {} meshes", result.bones.size(), result.meshes.size());
        for (int i = 0; i < result.meshes.size(); i++) {
            SmdMesh mesh = result.meshes.get(i);
            LOGGER.debug("[SmdParser]   Mesh[{}]: material='{}' vertices={} ({} triangles)",
                i, mesh.materialName, mesh.vertices.size(), mesh.vertices.size() / 3);
        }

        return result;
    }

    private static void parseNodeLine(String line, ParsedSmd result) {
        String[] parts = splitLine(line);
        if (parts.length < 3) return;
        try {
            int id = Integer.parseInt(parts[0]);
            String name = parts[1];
            int parent = Integer.parseInt(parts[2]);
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1);
            } else {
                int firstQuote = line.indexOf('"');
                int lastQuote = line.lastIndexOf('"');
                if (firstQuote >= 0 && lastQuote > firstQuote) {
                    name = line.substring(firstQuote + 1, lastQuote);
                }
            }
            result.bones.add(new SmdBone(id, name, parent));
        } catch (NumberFormatException e) {
            LOGGER.debug("[SmdParser] Failed to parse node line: {}", line);
        }
    }

    private static void parseTriangleLine(String line, ParsedSmd result, int lineNum) {
        String[] parts = splitLine(line);

        if (isMaterialLine(parts, line)) {
            if (result.meshes.isEmpty() || !result.meshes.get(result.meshes.size() - 1).vertices.isEmpty()) {
                String materialName = line;
                if (materialName.startsWith("\"") && materialName.endsWith("\"")) {
                    materialName = materialName.substring(1, materialName.length() - 1);
                }
                result.meshes.add(new SmdMesh(materialName, new ArrayList<>()));
            }
            return;
        }

        if (result.meshes.isEmpty()) return;
        SmdMesh currentMesh = result.meshes.get(result.meshes.size() - 1);

        SmdVertex vertex = parseVertexLine(parts);
        if (vertex != null) {
            currentMesh.vertices.add(vertex);
        }
    }

    private static boolean isMaterialLine(String[] parts, String rawLine) {
        if (parts.length <= 1) return true;
        char firstChar = parts[0].charAt(0);
        if (firstChar == '-' || Character.isDigit(firstChar)) return false;
        float test;
        try {
            test = Float.parseFloat(parts[0]);
            if (test == (int) test || isFloat(parts[0])) {
                return false;
            }
        } catch (NumberFormatException e) {
            return true;
        }
        String lower = rawLine.toLowerCase();
        return !lower.startsWith("//") && !lower.equals("end");
    }

    private static boolean isFloat(String s) {
        return s.contains(".") || s.contains("e") || s.contains("E");
    }

    private static SmdVertex parseVertexLine(String[] parts) {
        int idx = 0;
        if (parts.length < 8) return null;

        int[] boneIds;
        float[] boneWeights;

        int firstVal;
        try {
            firstVal = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }

        float x, y, z, nx, ny, nz, u, v;

        if (firstVal < 0) {
            idx = 1;
            boneIds = new int[]{0};
            boneWeights = new float[]{1.0f};
        } else if (firstVal > 3) {
            idx = 1;
            boneIds = new int[]{firstVal};
            boneWeights = new float[]{1.0f};
        } else {
            try {
                if (firstVal < 1) {
                    boneIds = new int[]{0};
                    boneWeights = new float[]{1.0f};
                    idx = 1;
                } else {
                    boneIds = new int[firstVal];
                    boneWeights = new float[firstVal];
                    int boneIdx = 0;
                    while (boneIdx < firstVal && idx + 1 + boneIdx * 2 + 1 < parts.length) {
                        boneIds[boneIdx] = Integer.parseInt(parts[idx + 1 + boneIdx * 2]);
                        boneWeights[boneIdx] = Float.parseFloat(parts[idx + 1 + boneIdx * 2 + 1]);
                        boneIdx++;
                    }
                    if (boneIdx < firstVal) return null;
                    idx = 1 + firstVal * 2;
                }
            } catch (NumberFormatException e) {
                boneIds = new int[]{firstVal};
                boneWeights = new float[]{1.0f};
                idx = 1;
            }
        }

        if (idx + 8 > parts.length) return null;
        try {
            x = Float.parseFloat(parts[idx]);
            y = Float.parseFloat(parts[idx + 1]);
            z = Float.parseFloat(parts[idx + 2]);
            nx = Float.parseFloat(parts[idx + 3]);
            ny = Float.parseFloat(parts[idx + 4]);
            nz = Float.parseFloat(parts[idx + 5]);
            u = Float.parseFloat(parts[idx + 6]);
            v = Float.parseFloat(parts[idx + 7]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }

        return new SmdVertex(x, y, z, nx, ny, nz, u, v, boneIds, boneWeights);
    }

    private static String[] splitLine(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts.toArray(new String[0]);
    }
}
