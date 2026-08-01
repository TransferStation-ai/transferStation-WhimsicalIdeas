package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
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

    public static SourceModelData parse(Path bbmodelPath, Path packageDir) throws IOException {
        String jsonStr = Files.readString(bbmodelPath);
        JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

        SourceModelData result = new SourceModelData();

        JsonObject meta = root.getAsJsonObject("meta");
        String modelFormat = "free";
        if (meta != null && meta.has("model_format")) {
            modelFormat = meta.get("model_format").getAsString();
        }

        result.name = root.has("name") ? root.get("name").getAsString()
            : bbmodelPath.getFileName().toString().replace(".bbmodel", "");

        Map<Integer, ResourceLocation> textureMap = parseTextures(root, packageDir);

        JsonArray elements = root.getAsJsonArray("elements");
        if (elements != null) {
            for (JsonElement elemEl : elements) {
                JsonObject elem = elemEl.getAsJsonObject();
                String type = elem.has("type") ? elem.get("type").getAsString() : "cube";

                SourceModelData.MeshData mesh;
                if ("mesh".equals(type)) {
                    mesh = parseMeshElement(elem, textureMap);
                } else {
                    mesh = parseCubeElement(elem, textureMap);
                }
                if (mesh != null) result.meshes.add(mesh);
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

        LOGGER.info("[BBModelParser] Loaded '{}' (format={}): {} meshes, {} bones, {} textures",
            result.name, modelFormat, result.meshes.size(), result.bones.size(), textureMap.size());

        return result;
    }

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
        ModelLoadManager.getColorResolver().markComplete(regKey, loc, extractCenterPixelColor(image), false, false, false, nativeImage);

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

    private static SourceModelData.MeshData parseMeshElement(JsonObject elem, Map<Integer, ResourceLocation> textureMap) {
        JsonArray verts = elem.getAsJsonArray("vertices");
        JsonArray faces = elem.getAsJsonArray("faces");
        if (verts == null || verts.size() < 3 || faces == null || faces.isEmpty()) return null;

        List<Float> vertexList = new ArrayList<>();
        List<Integer> indexList = new ArrayList<>();

        for (int i = 0; i < verts.size(); i++) {
            JsonArray v = verts.get(i).getAsJsonArray();
            vertexList.add(v.get(0).getAsFloat());
            vertexList.add(v.get(1).getAsFloat());
            vertexList.add(v.get(2).getAsFloat());
            vertexList.add(0f);
            vertexList.add(0f);
            vertexList.add(0f);
            vertexList.add(0f);
            vertexList.add(0f);
        }

        for (JsonElement faceEl : faces) {
            JsonArray face = faceEl.getAsJsonArray();
            indexList.add(face.get(0).getAsInt());
            indexList.add(face.get(1).getAsInt());
            indexList.add(face.get(2).getAsInt());
        }

        JsonArray uvs = elem.getAsJsonArray("uvs");
        if (uvs != null && uvs.size() == verts.size()) {
            for (int i = 0; i < uvs.size(); i++) {
                JsonArray uv = uvs.get(i).getAsJsonArray();
                int offset = i * 8 + 6;
                vertexList.set(offset, uv.get(0).getAsFloat());
                vertexList.set(offset + 1, 1.0f - uv.get(1).getAsFloat());
            }
        }

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
            if (i0 < 0 || i0 >= vertCount || i1 < 0 || i1 >= vertCount || i2 < 0 || i2 >= vertCount) continue;
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

        JsonObject faces = elem.getAsJsonObject("faces");
        if (faces == null) return null;

        List<Float> vertList = new ArrayList<>();
        List<Integer> idxList = new ArrayList<>();

        String[] faceNames = {"north", "east", "south", "west", "up", "down"};
        int[][] faceVerts = {
            {0, 1, 5, 4},
            {4, 5, 6, 7},
            {2, 3, 7, 6},
            {0, 4, 7, 3},
            {1, 2, 6, 5},
            {0, 3, 2, 1},
        };
        float[][] faceNormals = {
            {0, 0, -1},
            {1, 0, 0},
            {0, 0, 1},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
        };

        float[][] corners = {
            {fx, fy, fz}, {fx, fy, tz}, {tx, fy, tz}, {tx, fy, fz},
            {fx, ty, fz}, {fx, ty, tz}, {tx, ty, tz}, {tx, ty, fz},
        };

        if (rx != 0 || ry != 0 || rz != 0) {
            for (int i = 0; i < 8; i++) {
                float px = corners[i][0] - ox;
                float py = corners[i][1] - oy;
                float pz = corners[i][2] - oz;
                if (ry != 0) {
                    float cos = (float) Math.cos(ry);
                    float sin = (float) Math.sin(ry);
                    float nx = px * cos - pz * sin;
                    float nz = px * sin + pz * cos;
                    px = nx; pz = nz;
                }
                if (rx != 0) {
                    float cos = (float) Math.cos(rx);
                    float sin = (float) Math.sin(rx);
                    float ny = py * cos - pz * sin;
                    float nz = py * sin + pz * cos;
                    py = ny; pz = nz;
                }
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
            if (face.has("texture") && !face.get("texture").isJsonNull()) {
                texId = face.get("texture").getAsInt();
            }

            JsonArray uv = face.getAsJsonArray("uv");
            float u1 = 0, v1 = 0, u2 = 16, v2 = 16;
            if (uv != null && uv.size() == 4) {
                u1 = uv.get(0).getAsFloat();
                v1 = uv.get(1).getAsFloat();
                u2 = uv.get(2).getAsFloat();
                v2 = uv.get(3).getAsFloat();
            }

            int[] fv = faceVerts[fi];
            float[] fn = faceNormals[fi];

            float[][] faceUv = {
                {u1 / 16f, v2 / 16f},
                {u1 / 16f, v1 / 16f},
                {u2 / 16f, v1 / 16f},
                {u2 / 16f, v2 / 16f},
            };

            for (int ci = 0; ci < 4; ci++) {
                int ci0 = fv[ci];
                vertList.add(corners[ci0][0]);
                vertList.add(corners[ci0][1]);
                vertList.add(corners[ci0][2]);
                vertList.add(fn[0]);
                vertList.add(fn[1]);
                vertList.add(fn[2]);
                vertList.add(faceUv[ci][0]);
                vertList.add(faceUv[ci][1]);
            }

            int base = fi * 4;
            idxList.add(base); idxList.add(base + 1); idxList.add(base + 2);
            idxList.add(base); idxList.add(base + 2); idxList.add(base + 3);
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

    private static void parseOutliner(JsonArray outliner, SourceModelData result) {
        List<String> boneNames = new ArrayList<>();
        List<float[]> bonePositions = new ArrayList<>();
        List<Integer> boneParents = new ArrayList<>();
        Map<String, Integer> nameToIndex = new HashMap<>();

        for (JsonElement el : outliner) {
            parseOutlinerNode(el, -1, boneNames, bonePositions, boneParents, nameToIndex);
        }

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
        if (!el.isJsonObject()) return;
        JsonObject node = el.getAsJsonObject();
        String name = node.has("name") ? node.get("name").getAsString() : "bone";
        float[] origin = new float[]{0, 0, 0};
        if (node.has("origin")) {
            JsonArray o = node.getAsJsonArray("origin");
            origin[0] = o.get(0).getAsFloat();
            origin[1] = o.get(1).getAsFloat();
            origin[2] = o.get(2).getAsFloat();
        }
        String uniqueName = name;
        int dedup = 0;
        while (nameToIndex.containsKey(uniqueName)) {
            dedup++;
            uniqueName = name + "_" + dedup;
        }
        int idx = names.size();
        names.add(uniqueName);
        positions.add(origin);
        parents.add(parentIdx);
        nameToIndex.put(uniqueName, idx);

        if (node.has("children")) {
            JsonArray children = node.getAsJsonArray("children");
            for (JsonElement child : children) {
                parseOutlinerNode(child, idx, names, positions, parents, nameToIndex);
            }
        }
    }

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

                int boneIdx = -1;
                for (int i = 0; i < modelData.bones.size(); i++) {
                    if (modelData.bones.get(i).name().equals(boneName)) {
                        boneIdx = i;
                        break;
                    }
                }
                if (boneIdx < 0) continue;

                var track = new transferstation.transferstation_whimsicalideas.client.animation.AnimationData.AnimationTrack("bone" + boneIdx);

                JsonObject posCh = channels.getAsJsonObject("position");
                if (posCh != null) {
                    for (String frameStr : posCh.keySet()) {
                        int frame = Integer.parseInt(frameStr);
                        JsonArray val = posCh.get(frameStr).getAsJsonArray();
                        float[] pos = {val.get(0).getAsFloat(), val.get(1).getAsFloat(), val.get(2).getAsFloat()};
                        float[] rot = {0, 0, 0};
                        track.addKeyFrame(new transferstation.transferstation_whimsicalideas.client.animation.AnimationData.KeyFrame(frame, pos, rot, new float[]{1, 1, 1}));
                        if (frame + 1 > frameCount) frameCount = frame + 1;
                    }
                }

                JsonObject rotCh = channels.getAsJsonObject("rotation");
                if (rotCh != null) {
                    for (String frameStr : rotCh.keySet()) {
                        int frame = Integer.parseInt(frameStr);
                        JsonArray val = rotCh.get(frameStr).getAsJsonArray();
                        float[] rot = {(float) Math.toRadians(val.get(0).getAsFloat()),
                                       (float) Math.toRadians(val.get(1).getAsFloat()),
                                       (float) Math.toRadians(val.get(2).getAsFloat())};
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
}
