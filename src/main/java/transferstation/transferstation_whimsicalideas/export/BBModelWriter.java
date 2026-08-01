package transferstation.transferstation_whimsicalideas.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BBModelWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void write(SourceModelData model, List<ModelExporter.TextureEntry> textures, Path outputDir) throws IOException {
        String modelName = sanitizeName(model.name.isEmpty() ? "model" : model.name);
        Path bbFile = outputDir.resolve(modelName + ".bbmodel");

        JsonObject root = new JsonObject();

        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
        root.add("meta", meta);
        root.addProperty("name", modelName);

        JsonArray texArray = new JsonArray();
        for (int i = 0; i < textures.size(); i++) {
            ModelExporter.TextureEntry entry = textures.get(i);
            JsonObject tex = new JsonObject();
            tex.addProperty("id", i);
            tex.addProperty("name", entry.name());
            byte[] pngBytes = Files.readAllBytes(entry.pngPath());
            String b64 = Base64.getEncoder().encodeToString(pngBytes);
            tex.addProperty("source", "data:image/png;base64," + b64);
            texArray.add(tex);
        }
        root.add("textures", texArray);

        JsonArray elements = getJsonElements(model, textures);
        root.add("elements", elements);

        JsonArray outliner = new JsonArray();
        if (!model.bones.isEmpty()) {
            List<List<Integer>> children = new ArrayList<>();
            for (int i = 0; i < model.bones.size(); i++) children.add(new ArrayList<>());
            for (int i = 0; i < model.bones.size(); i++) {
                int p = model.bones.get(i).parent();
                if (p >= 0 && p < model.bones.size()) children.get(p).add(i);
            }
            int[] added = new int[model.bones.size()];
            for (int i = 0; i < model.bones.size(); i++) {
                if (added[i] == 0 && isRootBone(i, model.bones)) {
                    outliner.add(buildBoneTree(i, model, children, added));
                }
            }
        }
        root.add("outliner", outliner);

        JsonArray boneGroups = getJsonElements(model);
        root.add("bone_groups", boneGroups);

        Files.writeString(bbFile, GSON.toJson(root));
    }

    private static @NotNull JsonArray getJsonElements(SourceModelData model) {
        JsonArray boneGroups = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) {
            SourceModelData.BoneInfo bone = model.bones.get(i);
            JsonObject bg = new JsonObject();
            bg.addProperty("name", bone.name());
            bg.addProperty("parent", bone.parent());
            JsonArray pos = new JsonArray();
            if (bone.pos() != null) {
                pos.add((double) bone.pos()[0]);
                pos.add((double) bone.pos()[1]);
                pos.add((double) bone.pos()[2]);
            } else {
                pos.add(0); pos.add(0); pos.add(0);
            }
            bg.add("position", pos);
            boneGroups.add(bg);
        }
        return boneGroups;
    }

    private static @NotNull JsonArray getJsonElements(SourceModelData model, List<ModelExporter.TextureEntry> textures) {
        JsonArray elements = new JsonArray();
        for (int m = 0; m < model.meshes.size(); m++) {
            SourceModelData.MeshData mesh = model.meshes.get(m);
            if (mesh.vertices == null || mesh.vertices.length < 8) continue;

            JsonObject elem = new JsonObject();
            elem.addProperty("name", "mesh_" + m);
            elem.addProperty("type", "mesh");

            JsonArray verts = new JsonArray();
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                JsonArray v = new JsonArray();
                v.add((double) mesh.vertices[i]);
                v.add((double) mesh.vertices[i + 1]);
                v.add((double) mesh.vertices[i + 2]);
                verts.add(v);
            }
            elem.add("vertices", verts);

            JsonArray faces = new JsonArray();
            for (int i = 0; i < mesh.indices.length; i += 3) {
                JsonArray face = new JsonArray();
                face.add(mesh.indices[i]);
                face.add(mesh.indices[i + 1]);
                face.add(mesh.indices[i + 2]);
                faces.add(face);
            }
            elem.add("faces", faces);

            JsonArray uvs = new JsonArray();
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                JsonArray uv = new JsonArray();
                uv.add((double) mesh.vertices[i + 6]);
                uv.add((double) mesh.vertices[i + 7]);
                uvs.add(uv);
            }
            elem.add("uvs", uvs);

            JsonArray normals = new JsonArray();
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                JsonArray n = new JsonArray();
                n.add((double) mesh.vertices[i + 3]);
                n.add((double) mesh.vertices[i + 4]);
                n.add((double) mesh.vertices[i + 5]);
                normals.add(n);
            }
            elem.add("normals", normals);

            JsonArray faceMats = getJsonElements(textures, mesh);
            elem.add("faces_materials", faceMats);

            elements.add(elem);
        }
        return elements;
    }

    private static @NotNull JsonArray getJsonElements(List<ModelExporter.TextureEntry> textures, SourceModelData.MeshData mesh) {
        JsonArray faceMats = new JsonArray();
        int texId = 0;
        if (mesh.texture != null) {
            String path = mesh.texture.getPath().toLowerCase();
            for (int t = 0; t < textures.size(); t++) {
                String tName = textures.get(t).name().toLowerCase().replaceAll("\\.png$", "").replace('/', '_');
                if (path.contains(tName)) {
                    texId = t;
                    break;
                }
            }
        }
        int faceCount = mesh.indices.length / 3;
        for (int i = 0; i < faceCount; i++) {
            faceMats.add(texId);
        }
        return faceMats;
    }

    private static boolean isRootBone(int idx, List<SourceModelData.BoneInfo> bones) {
        int p = bones.get(idx).parent();
        return p < 0 || p >= bones.size();
    }

    private static JsonObject buildBoneTree(int idx, SourceModelData model,
                                             List<List<Integer>> children, int[] added) {
        added[idx] = 1;
        JsonObject node = new JsonObject();
        node.addProperty("name", model.bones.get(idx).name());
        JsonArray ch = new JsonArray();
        for (int child : children.get(idx)) {
            ch.add(buildBoneTree(child, model, children, added));
        }
        node.add("children", ch);
        return node;
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
