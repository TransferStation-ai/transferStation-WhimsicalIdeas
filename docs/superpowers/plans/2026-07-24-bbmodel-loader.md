# BBModel 加载器实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为项目添加 Blockbench (.bbmodel) 文件加载能力，使其能直接作为角色/NPC 模型使用

**架构：** 新增 `BBModelParser` 将 `.bbmodel` JSON 解析为 `SourceModelData`；在 `ModelLoadManager.loadFromDirectory()` 中添加 BBModel 检测分支；在 4 个 `hasAnyModelFile()` 方法中添加 `.bbmodel` 文件类型检测

**技术栈：** Java 17, Minecraft Forge 1.20.1, Gson (已有依赖), BufferedImage, DynamicTexture

---

### 任务 1：创建 `BBModelParser.java`

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/BBModelParser.java`

- [ ] **步骤 1：编写 BBModelParser 基础结构**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BBModelParser {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public static SourceModelData parse(Path bbmodelPath, Path packageDir) throws IOException {
        String jsonStr = Files.readString(bbmodelPath);
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

        SourceModelData result = new SourceModelData();

        JsonObject meta = root.getAsJsonObject("meta");
        String modelFormat = "free";
        if (meta != null && meta.has("model_format")) {
            modelFormat = meta.get("model_format").getAsString();
        }

        result.name = root.has("name") ? root.get("name").getAsString() : bbmodelPath.getFileName().toString().replace(".bbmodel", "");

        Map<Integer, ResourceLocation> textureMap = parseTextures(root, packageDir);

        JsonArray elements = root.getAsJsonArray("elements");
        if (elements != null) {
            for (JsonElement elemEl : elements) {
                JsonObject elem = elemEl.getAsJsonObject();
                String type = elem.has("type") ? elem.get("type").getAsString() : "cube";

                if ("mesh".equals(type)) {
                    SourceModelData.MeshData mesh = parseMeshElement(elem, textureMap);
                    if (mesh != null) result.meshes.add(mesh);
                } else {
                    SourceModelData.MeshData mesh = parseCubeElement(elem, textureMap);
                    if (mesh != null) result.meshes.add(mesh);
                }
            }
        }

        JsonArray outliner = root.getAsJsonArray("outliner");
        if (outliner != null && !outliner.isEmpty()) {
            parseOutliner(outliner, result);
        }

        computeBounds(result);

        JsonArray animations = root.getAsJsonArray("animations");
        if (animations != null && !result.bones.isEmpty()) {
            parseAnimations(animations, result);
        }

        return result;
    }
```

- [ ] **步骤 2：实现 parseTextures — 解码 base64 嵌入纹理**

```java
    private static Map<Integer, ResourceLocation> parseTextures(JsonObject root, Path packageDir) {
        Map<Integer, ResourceLocation> textureMap = new HashMap<>();
        JsonArray textures = root.getAsJsonArray("textures");
        if (textures == null) return textureMap;

        for (JsonElement texEl : textures) {
            JsonObject tex = texEl.getAsJsonObject();
            int id = tex.get("id").getAsInt();
            String name = tex.has("name") ? tex.get("name").getAsString() : "tex_" + id;
            String source = tex.has("source") ? tex.get("source").getAsString() : "";

            if (!source.startsWith("data:image/png;base64,")) {
                LOGGER.warn("[BBModelParser] Unsupported texture source for '{}', skipping", name);
                continue;
            }

            try {
                String b64 = source.substring("data:image/png;base64,".length());
                byte[] pngBytes = Base64.getDecoder().decode(b64);
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(pngBytes));
                if (img != null) {
                    ResourceLocation loc = registerBbTexture(name, img);
                    textureMap.put(id, loc);
                }
            } catch (Exception e) {
                LOGGER.warn("[BBModelParser] Failed to decode texture '{}': {}", name, e.getMessage());
            }
        }
        return textureMap;
    }

    private static ResourceLocation registerBbTexture(String name, BufferedImage image) {
        String regKey = "bbmodel_" + name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT)
            + "_" + Integer.toHexString(name.hashCode());
        ResourceLocation loc = ResourceLocation.parse("transferstation_whimsicalideas:textures/generated/" + regKey);

        NativeImage nativeImage = TextureColorResolver.bufferedImageToNativeImage(image);
        ModelLoadManager.getColorResolver().applyNativeImage(loc, nativeImage);
        ModelLoadManager.getColorResolver().markComplete(regKey, loc,
            extractCenterPixelColor(image), false, false, false, nativeImage);

        return loc;
    }

    private static int extractCenterPixelColor(BufferedImage image) {
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int pixel = image.getRGB(cx, cy);
        int a = (pixel >> 24) & 0xFF;
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        if (a == 0) a = 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
```

- [ ] **步骤 3：实现 parseMeshElement — 处理 "free" 格式网格**

```java
    private static SourceModelData.MeshData parseMeshElement(JsonObject elem, Map<Integer, ResourceLocation> textureMap) {
        JsonArray verts = elem.getAsJsonArray("vertices");
        JsonArray faces = elem.getAsJsonArray("faces");
        if (verts == null || verts.size() < 3 || faces == null || faces.size() < 1) return null;

        List<Float> vertexList = new ArrayList<>();
        List<Integer> indexList = new ArrayList<>();

        for (int i = 0; i < verts.size(); i++) {
            JsonArray v = verts.get(i).getAsJsonArray();
            float x = v.get(0).getAsFloat();
            float y = v.get(1).getAsFloat();
            float z = v.get(2).getAsFloat();
            vertexList.add(x);
            vertexList.add(y);
            vertexList.add(z);
            vertexList.add(0f); // nx placeholder
            vertexList.add(0f); // ny placeholder
            vertexList.add(0f); // nz placeholder
            vertexList.add(0f); // u placeholder
            vertexList.add(0f); // v placeholder
        }

        for (JsonElement faceEl : faces) {
            JsonArray face = faceEl.getAsJsonArray();
            indexList.add(face.get(0).getAsInt());
            indexList.add(face.get(1).getAsInt());
            indexList.add(face.get(2).getAsInt());
        }

        // Parse UVs if available
        JsonArray uvs = elem.getAsJsonArray("uvs");
        if (uvs != null && uvs.size() == verts.size()) {
            for (int i = 0; i < uvs.size(); i++) {
                JsonArray uv = uvs.get(i).getAsJsonArray();
                int offset = i * 8 + 6;
                vertexList.set(offset, uv.get(0).getAsFloat());
                vertexList.set(offset + 1, 1.0f - uv.get(1).getAsFloat());
            }
        }

        // Parse normals if available
        JsonArray normals = elem.getAsJsonArray("normals");
        if (normals != null && normals.size() == verts.size()) {
            for (int i = 0; i < normals.size(); i++) {
                JsonArray n = normals.get(i).getAsJsonArray();
                int offset = i * 8 + 3;
                vertexList.set(offset, n.get(0).getAsFloat());
                vertexList.set(offset + 1, n.get(1).getAsFloat());
                vertexList.set(offset + 2, n.get(2).getAsFloat());
            }
        } else {
            computeNormals(vertexList, indexList);
        }

        float[] vertArray = new float[vertexList.size()];
        for (int i = 0; i < vertexList.size(); i++) vertArray[i] = vertexList.get(i);
        int[] idxArray = new int[indexList.size()];
        for (int i = 0; i < indexList.size(); i++) idxArray[i] = indexList.get(i);

        ResourceLocation tex = resolveElementTexture(elem, textureMap);

        return new SourceModelData.MeshData.Builder()
            .vertices(vertArray).indices(idxArray)
            .texture(tex)
            .bodyPartIndex(0).modelIndex(0).materialIndex(0)
            .build();
    }

    private static void computeNormals(List<Float> vertices, List<Integer> indices) {
        int vertCount = vertices.size() / 8;
        float[] nx = new float[vertCount];
        float[] ny = new float[vertCount];
        float[] nz = new float[vertCount];
        for (int i = 0; i + 2 < indices.size(); i += 3) {
            int i0 = indices.get(i);
            int i1 = indices.get(i + 1);
            int i2 = indices.get(i + 2);
            float x0 = vertices.get(i0 * 8), y0 = vertices.get(i0 * 8 + 1), z0 = vertices.get(i0 * 8 + 2);
            float x1 = vertices.get(i1 * 8), y1 = vertices.get(i1 * 8 + 1), z1 = vertices.get(i1 * 8 + 2);
            float x2 = vertices.get(i2 * 8), y2 = vertices.get(i2 * 8 + 1), z2 = vertices.get(i2 * 8 + 2);
            float ax = x1 - x0, ay = y1 - y0, az = z1 - z0;
            float bx = x2 - x0, by = y2 - y0, bz = z2 - z0;
            float fnx = ay * bz - az * by;
            float fny = az * bx - ax * bz;
            float fnz = ax * by - ay * bx;
            float len = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (len > 0.0001f) { fnx /= len; fny /= len; fnz /= len; }
            nx[i0] += fnx; ny[i0] += fny; nz[i0] += fnz;
            nx[i1] += fnx; ny[i1] += fny; nz[i1] += fnz;
            nx[i2] += fnx; ny[i2] += fny; nz[i2] += fnz;
        }
        for (int i = 0; i < vertCount; i++) {
            float len = (float) Math.sqrt(nx[i] * nx[i] + ny[i] * ny[i] + nz[i] * nz[i]);
            if (len > 0.0001f) { nx[i] /= len; ny[i] /= len; nz[i] /= len; }
            vertices.set(i * 8 + 3, nx[i]);
            vertices.set(i * 8 + 4, ny[i]);
            vertices.set(i * 8 + 5, nz[i]);
        }
    }
```

- [ ] **步骤 4：实现 parseCubeElement — 处理 "java_block" 格式的 box**

```java
    private static SourceModelData.MeshData parseCubeElement(JsonObject elem, Map<Integer, ResourceLocation> textureMap) {
        JsonArray from = elem.getAsJsonArray("from");
        JsonArray to = elem.getAsJsonArray("to");
        if (from == null || to == null || from.size() < 3 || to.size() < 3) return null;

        float fx = from.get(0).getAsFloat();
        float fy = from.get(1).getAsFloat();
        float fz = from.get(2).getAsFloat();
        float tx = to.get(0).getAsFloat();
        float ty = to.get(1).getAsFloat();
        float tz = to.get(2).getAsFloat();

        float ox = 0, oy = 0, oz = 0;
        float rx = 0, ry = 0, rz = 0;
        if (elem.has("origin")) {
            JsonArray origin = elem.getAsJsonArray("origin");
            ox = origin.get(0).getAsFloat();
            oy = origin.get(1).getAsFloat();
            oz = origin.get(2).getAsFloat();
        }
        if (elem.has("rotation")) {
            JsonArray rot = elem.getAsJsonArray("rotation");
            rx = (float) Math.toRadians(rot.get(0).getAsFloat());
            ry = (float) Math.toRadians(rot.get(1).getAsFloat());
            rz = (float) Math.toRadians(rot.get(2).getAsFloat());
        }

        // 6 faces: north, east, south, west, up, down
        // Each face: 4 corner positions + normal direction + UV mapping
        JsonObject faces = elem.getAsJsonObject("faces");
        if (faces == null) return null;

        List<Float> vertList = new ArrayList<>();
        List<Integer> idxList = new ArrayList<>();
        int baseVertex = 0;

        String[] faceNames = {"north", "east", "south", "west", "up", "down"};
        // Each face: [v0, v1, v2, v3] indices into the 8 cube corners, normal dir
        int[][] faceVerts = {
            {0, 1, 5, 4},  // north (z-)
            {4, 5, 6, 7},  // east (x+)
            {2, 3, 7, 6},  // south (z+)
            {0, 4, 7, 3},  // west (x-)
            {1, 2, 6, 5},  // up (y+)
            {0, 3, 2, 1},  // down (y-)
        };
        float[][] faceNormals = {
            {0, 0, -1},  // north
            {1, 0, 0},   // east
            {0, 0, 1},   // south
            {-1, 0, 0},  // west
            {0, 1, 0},   // up
            {0, -1, 0},  // down
        };

        // Precompute the 8 corners of the box
        float[][] corners = {
            {fx, fy, fz}, {fx, fy, tz}, {tx, fy, tz}, {tx, fy, fz},
            {fx, ty, fz}, {fx, ty, tz}, {tx, ty, tz}, {tx, ty, fz},
        };

        // Apply pivot rotation if any
        if (rx != 0 || ry != 0 || rz != 0) {
            for (int i = 0; i < 8; i++) {
                float px = corners[i][0] - ox;
                float py = corners[i][1] - oy;
                float pz = corners[i][2] - oz;
                // Rotate around Y
                if (ry != 0) {
                    float cos = (float) Math.cos(ry);
                    float sin = (float) Math.sin(ry);
                    float nx = px * cos - pz * sin;
                    float nz = px * sin + pz * cos;
                    px = nx; pz = nz;
                }
                // Rotate around X
                if (rx != 0) {
                    float cos = (float) Math.cos(rx);
                    float sin = (float) Math.sin(rx);
                    float ny = py * cos - pz * sin;
                    float nz = py * sin + pz * cos;
                    py = ny; pz = nz;
                }
                // Rotate around Z
                if (rz != 0) {
                    float cos = (float) Math.cos(rz);
                    float sin = (float) Math.sin(rz);
                    float nx = px * cos - py * sin;
                    float ny = px * sin + py * cos;
                    px = nx; py = ny;
                }
                corners[i][0] = px + ox;
                corners[i][1] = py + oy;
                corners[i][2] = pz + oz;
            }
        }

        for (int fi = 0; fi < faceNames.length; fi++) {
            JsonObject face = faces.getAsJsonObject(faceNames[fi]);
            if (face == null) continue;

            int texId = 0;
            if (face.has("texture")) {
                JsonElement texVal = face.get("texture");
                texId = texVal.isJsonNull() ? 0 : texVal.getAsInt();
            }

            JsonArray uv = face.getAsJsonArray("uv");
            float u1 = 0, v1 = 0, u2 = 16, v2 = 16;
            if (uv != null && uv.size() == 4) {
                u1 = uv.get(0).getAsFloat();
                v1 = uv.get(1).getAsFloat();
                u2 = uv.get(2).getAsFloat();
                v2 = uv.get(3).getAsFloat();
            }

            // Determine the texture size for UV normalization
            ResourceLocation tex = textureMap.get(texId);
            float texW = 16f, texH = 16f;
            if (tex != null) {
                var reg = ModelLoadManager.getColorResolver().getRegistered("bbmodel_" + texId);
                // Use 16x16 default for Java block models
            }

            int[] fv = faceVerts[fi];
            float[] fn = faceNormals[fi];

            // Map face corner to UV: v0→(u1,v2), v1→(u1,v1), v2→(u2,v1), v3→(u2,v2)
            float[][] faceUv = {
                {u1 / texW, v2 / texH},
                {u1 / texW, v1 / texH},
                {u2 / texW, v1 / texH},
                {u2 / texW, v2 / texH},
            };

            for (int ci = 0; ci < 4; ci++) {
                int ci0 = fv[ci];
                float cx = corners[ci0][0];
                float cy = corners[ci0][1];
                float cz = corners[ci0][2];
                vertList.add(cx); vertList.add(cy); vertList.add(cz);
                vertList.add(fn[0]); vertList.add(fn[1]); vertList.add(fn[2]);
                vertList.add(faceUv[ci][0]); vertList.add(faceUv[ci][1]);
            }

            int base = baseVertex + fi * 4;
            idxList.add(base); idxList.add(base + 1); idxList.add(base + 2);
            idxList.add(base); idxList.add(base + 2); idxList.add(base + 3);

            baseVertex += 4;
        }

        if (vertList.isEmpty()) return null;

        float[] vertArray = new float[vertList.size()];
        for (int i = 0; i < vertList.size(); i++) vertArray[i] = vertList.get(i);
        int[] idxArray = new int[idxList.size()];
        for (int i = 0; i < idxList.size(); i++) idxArray[i] = idxList.get(i);

        int texId0 = 0;
        JsonObject firstFace = faces.getAsJsonObject(faceNames[0]);
        if (firstFace != null && firstFace.has("texture") && !firstFace.get("texture").isJsonNull()) {
            texId0 = firstFace.get("texture").getAsInt();
        }
        ResourceLocation tex = textureMap.get(texId0);

        return new SourceModelData.MeshData.Builder()
            .vertices(vertArray).indices(idxArray)
            .texture(tex)
            .bodyPartIndex(0).modelIndex(0).materialIndex(0)
            .build();
    }

    private static ResourceLocation resolveElementTexture(JsonObject elem, Map<Integer, ResourceLocation> textureMap) {
        JsonObject faces = elem.getAsJsonObject("faces");
        if (faces == null) return textureMap.getOrDefault(0, null);
        for (String key : faces.keySet()) {
            JsonObject face = faces.getAsJsonObject(key);
            if (face != null && face.has("texture") && !face.get("texture").isJsonNull()) {
                int texId = face.get("texture").getAsInt();
                ResourceLocation tex = textureMap.get(texId);
                if (tex != null) return tex;
            }
        }
        return textureMap.getOrDefault(0, null);
    }
```

- [ ] **步骤 5：实现 parseOutliner — 骨骼层级**

```java
    private static void parseOutliner(JsonArray outliner, SourceModelData result) {
        List<String> boneNames = new ArrayList<>();
        List<float[]> bonePositions = new ArrayList<>();
        List<Integer> boneParents = new ArrayList<>();

        // First pass: collect all names from flat outliner reference
        Map<String, Integer> nameToIndex = new HashMap<>();

        // Parse element-level outliner references and group-level tree
        for (JsonElement el : outliner) {
            parseOutlinerNode(el, -1, boneNames, bonePositions, boneParents, nameToIndex);
        }

        // Create bone entries
        for (int i = 0; i < boneNames.size(); i++) {
            String name = boneNames.get(i);
            if (name == null || name.isEmpty()) name = "bone_" + i;
            float[] pos = bonePositions.get(i);
            int parent = boneParents.get(i);
            result.bones.add(new SourceModelData.BoneInfo(name, pos != null ? pos : new float[]{0, 0, 0}, parent));
        }
    }

    private static void parseOutlinerNode(JsonElement el, int parentIdx,
                                           List<String> names, List<float[]> positions,
                                           List<Integer> parents, Map<String, Integer> nameToIndex) {
        if (el.isJsonObject()) {
            JsonObject node = el.getAsJsonObject();
            String name = node.has("name") ? node.get("name").getAsString() : "bone";
            float[] origin = new float[]{0, 0, 0};
            if (node.has("origin")) {
                JsonArray o = node.getAsJsonArray("origin");
                origin[0] = o.get(0).getAsFloat();
                origin[1] = o.get(1).getAsFloat();
                origin[2] = o.get(2).getAsFloat();
            }
            int idx = names.size();
            // Use name for dedup
            String uniqueName = name;
            int dedup = 0;
            while (nameToIndex.containsKey(uniqueName)) {
                dedup++;
                uniqueName = name + "_" + dedup;
            }
            // Handle element reference: if this node references an element name
            if (node.has("children")) {
                names.add(uniqueName);
                positions.add(origin);
                parents.add(parentIdx);
                nameToIndex.put(uniqueName, idx);
                JsonArray children = node.getAsJsonArray("children");
                for (JsonElement child : children) {
                    parseOutlinerNode(child, idx, names, positions, parents, nameToIndex);
                }
            } else {
                // This is a leaf element reference; only create bone if it has origin or rotation
                names.add(uniqueName);
                positions.add(origin);
                parents.add(parentIdx);
                nameToIndex.put(uniqueName, idx);
            }
        } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            // Outliner can contain numeric element references (element index)
            // We skip these; bones are only created for named groups
        }
    }
```

- [ ] **步骤 6：实现 parseAnimations 和 computeBounds**

```java
    private static void parseAnimations(JsonArray animations, SourceModelData modelData) {
        for (JsonElement animEl : animations) {
            JsonObject anim = animEl.getAsJsonObject();
            String name = anim.has("name") ? anim.get("name").getAsString() : "animation";
            float fps = 20;
            if (anim.has("anim_time_update")) {
                fps = anim.get("anim_time_update").getAsFloat();
                if (fps <= 0) fps = 20;
            }
            int frameCount = 0;
            boolean loop = anim.has("loop") && anim.get("loop").getAsBoolean();

            JsonObject animators = anim.getAsJsonObject("animators");
            if (animators == null) continue;

            transferstation.transferstation_whimsicalideas.client.animation.AnimationData animData =
                new transferstation.transferstation_whimsicalideas.client.animation.AnimationData(
                    modelData.name + "_" + name, fps, frameCount, loop);

            for (String boneName : animators.keySet()) {
                JsonObject channels = animators.getAsJsonObject(boneName);
                if (channels == null) continue;

                // Find bone index by name
                int boneIdx = -1;
                for (int i = 0; i < modelData.bones.size(); i++) {
                    if (modelData.bones.get(i).name.equals(boneName)) {
                        boneIdx = i;
                        break;
                    }
                }
                if (boneIdx < 0) continue;

                var track = new transferstation.transferstation_whimsicalideas.client.animation.AnimationData.AnimationTrack("bone" + boneIdx);

                // Parse position keyframes
                JsonObject posCh = channels.getAsJsonObject("position");
                if (posCh != null) {
                    for (String frameStr : posCh.keySet()) {
                        int frame = Integer.parseInt(frameStr);
                        JsonArray val = posCh.get(frameStr).getAsJsonArray();
                        float[] pos = {val.get(0).getAsFloat(), val.get(1).getAsFloat(), val.get(2).getAsFloat()};
                        // Find or create matching rotation keyframe
                        float[] rot = {0, 0, 0};
                        track.addKeyFrame(new transferstation.transferstation_whimsicalideas.client.animation.AnimationData.KeyFrame(frame, pos, rot, new float[]{1, 1, 1}));
                        if (frame + 1 > frameCount) frameCount = frame + 1;
                    }
                }

                // Parse rotation keyframes
                JsonObject rotCh = channels.getAsJsonObject("rotation");
                if (rotCh != null) {
                    for (String frameStr : rotCh.keySet()) {
                        int frame = Integer.parseInt(frameStr);
                        JsonArray val = rotCh.get(frameStr).getAsJsonArray();
                        float[] rot = {(float) Math.toRadians(val.get(0).getAsFloat()),
                                       (float) Math.toRadians(val.get(1).getAsFloat()),
                                       (float) Math.toRadians(val.get(2).getAsFloat())};
                        // Try to merge with existing position keyframe
                        boolean merged = false;
                        for (var kf : track.keyFrames) {
                            if (kf.frame == frame) {
                                kf.rotation = rot;
                                merged = true;
                                break;
                            }
                        }
                        if (!merged) {
                            track.addKeyFrame(new transferstation.transferstation_whimsicalideas.client.animation.AnimationData.KeyFrame(frame, new float[]{0, 0, 0}, rot, new float[]{1, 1, 1}));
                        }
                        if (frame + 1 > frameCount) frameCount = frame + 1;
                    }
                }

                if (!track.keyFrames.isEmpty()) {
                    animData.tracks.add(track);
                }
            }

            animData.frameCount = frameCount;
            if (!animData.tracks.isEmpty()) {
                transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor.registerAnimation(animData);
                LOGGER.info("[BBModelParser] Registered animation '{}' with {} tracks ({} frames)", animData.name, animData.tracks.size(), frameCount);
            }
        }
    }

    private static void computeBounds(SourceModelData result) {
        for (SourceModelData.MeshData mesh : result.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                float x = mesh.vertices[i];
                float y = mesh.vertices[i + 1];
                float z = mesh.vertices[i + 2];
                if (x < result.minX) result.minX = x;
                if (x > result.maxX) result.maxX = x;
                if (y < result.minY) result.minY = y;
                if (y > result.maxY) result.maxY = y;
                if (z < result.minZ) result.minZ = z;
                if (z > result.maxZ) result.maxZ = z;
            }
        }
        if (result.minX < Float.MAX_VALUE) {
            float sizeX = result.maxX - result.minX;
            float sizeY = result.maxY - result.minY;
            float sizeZ = result.maxZ - result.minZ;
            float maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
            if (maxDim > 0.001f) {
                result.modelScale = 1.8f / maxDim;
            }
        }
    }
```

---

### 任务 2：更新所有 `hasAnyModelFile()` 方法

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/GmodModelConfig.java:110`
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/ModelSyncManager.java:213`
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MdlModelRenderer.java:187`
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java:2603`

- [ ] **步骤 1：更新 GmodModelConfig.java**

在 `hasAnyModelFile()` 方法的条件中添加 `name.endsWith(".bbmodel")`：

编辑文件：`src/main/java/transferstation/transferstation_whimsicalideas/client/GmodModelConfig.java`
```java
// 第 110 行：在 name.endsWith(".smd") 后添加
return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".lua") || name.endsWith(".smd") || name.endsWith(".bbmodel");
```

- [ ] **步骤 2：更新 ModelSyncManager.java**

编辑文件：`src/main/java/transferstation/transferstation_whimsicalideas/ModelSyncManager.java`
```java
// 第 213 行：添加 .bbmodel
return name.endsWith(".mdl") || name.endsWith(".smd")
    || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".bbmodel");
```

- [ ] **步骤 3：更新 MdlModelRenderer.java**

编辑文件：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MdlModelRenderer.java`
```java
// 第 187 行：添加 .bbmodel
return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd") || name.endsWith(".bbmodel");
```

- [ ] **步骤 4：更新 ModelLoadManager.java (findAnyModelFile)**

编辑文件：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java`
```java
// 第 1124 行：添加 .bbmodel
return name.endsWith(".mdl") || name.endsWith(".smd") || name.endsWith(".bbmodel");
```

- [ ] **步骤 5：更新 ModelLoadManager.java (hasAnyModelFile)**

编辑文件：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java`
```java
// 第 2603 行：添加 .bbmodel
return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd") || name.endsWith(".bbmodel");
```

- [ ] **步骤 6：更新 ModelLoadManager.java (loadFromDirectory 文件类型检测)**

编辑文件：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java`
```java
// 第 1475 行：添加 .bbmodel
if (name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd") || name.endsWith(".bbmodel")) {
```

---

### 任务 3：在 `loadFromDirectory()` 中添加 BBModel 加载分支

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java:1445`

- [ ] **步骤 1：在 loadFromDirectory 开头添加 BBModel 检测**

在 `loadFromDirectory()` 方法的 `dirFiles` 收集阶段之后，MDL 检测逻辑之前，添加 BBModel 优先检测：

```java
// 在以下代码之前插入 BBModel 检测：
// Path mdlPath = null, vvdPath = null, vtxPath = null, smdPath = null;

// 优先检测 .bbmodel 文件
Path bbmodelPath = null;
for (Map.Entry<Path, List<Path>> entry : dirFiles.entrySet()) {
    for (Path f : entry.getValue()) {
        String name = f.getFileName().toString().toLowerCase();
        if (name.endsWith(".bbmodel")) {
            bbmodelPath = f;
            break;
        }
    }
    if (bbmodelPath != null) break;
}

if (bbmodelPath != null) {
    LOGGER.info("[ModelLoadManager] Found Blockbench model file: {}", bbmodelPath);
    ModelLoadProgress.setPhase(ModelLoadProgress.Phase.PARSING);
    try {
        SourceModelData data = BBModelParser.parse(bbmodelPath, packageDir);
        if (data != null && !data.meshes.isEmpty()) {
            LOGGER.info("[ModelLoadManager] BBModel loaded: {} meshes, {} triangles, {} vertices",
                data.meshes.size(), data.totalTriangles(), data.totalVertices());
            return data;
        }
    } catch (Exception e) {
        LOGGER.warn("[ModelLoadManager] BBModel parse failed for {}, falling back: {}", bbmodelPath, e.getMessage());
    }
}
```

---

### 任务 4：编译验证

- [ ] **步骤 1：运行 Gradle 编译检查**

```bash
.\gradlew.bat compileJava
```

预期：BUILD SUCCESSFUL，无编译错误

- [ ] **步骤 2：检查 import 完整性**

确认 `BBModelParser.java` 的所有 import 都已导入（javax.imageio.ImageIO, java.util.Base64, java.awt.image.BufferedImage 等）
