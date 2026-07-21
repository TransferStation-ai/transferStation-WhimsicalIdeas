package transferstation.transferstation_whimsicalideas.client.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import transferstation.transferstation_whimsicalideas.client.GmodModelConfig;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists model-editor overrides (bone transforms + material tweaks) to a JSON
 * file placed next to the model package: {@code <packageDir>/editor_overrides.json}.
 */
public final class ModelEditorIO {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelEditorIO.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModelEditorIO() {}

    public static Path saveModelOverrides(String modelName, SourceModelData model, ModelViewport viewport) {
        Path pkgDir = resolvePackageDir(modelName);
        if (pkgDir == null) return null;
        Path out = pkgDir.resolve("editor_overrides.json");

        JsonObject root = new JsonObject();
        root.addProperty("model", modelName);

        JsonArray bones = new JsonArray();
        for (var entry : viewport.getBoneOverrides().entrySet()) {
            int idx = entry.getKey();
            ModelViewport.BoneOverride ov = entry.getValue();
            if (idx < 0 || idx >= model.bones.size()) continue;
            JsonObject b = new JsonObject();
            b.addProperty("bone", model.bones.get(idx).name);
            JsonArray pos = new JsonArray();
            pos.add(ov.pos[0]); pos.add(ov.pos[1]); pos.add(ov.pos[2]);
            b.add("pos", pos);
            JsonArray rot = new JsonArray();
            rot.add(ov.rot[0]); rot.add(ov.rot[1]); rot.add(ov.rot[2]);
            b.add("rot", rot);
            bones.add(b);
        }
        root.add("bones", bones);

        JsonArray mats = new JsonArray();
        for (int i = 0; i < model.meshes.size(); i++) {
            SourceModelData.MeshData mesh = model.meshes.get(i);
            if (mesh.colorTint == null) continue;
            JsonObject m = new JsonObject();
            m.addProperty("mesh", i);
            JsonArray tint = new JsonArray();
            for (float v : mesh.colorTint) tint.add(v);
            m.add("tint", tint);
            mats.add(m);
        }
        root.add("materials", mats);

        try {
            Files.writeString(out, GSON.toJson(root));
            return out;
        } catch (Exception e) {
            LOGGER.warn("[ModelEditorIO] Failed to save model overrides for '{}'", modelName, e);
            return null;
        }
    }

    public static void loadModelOverrides(String modelName, SourceModelData model, ModelViewport viewport) {
        Path pkgDir = resolvePackageDir(modelName);
        if (pkgDir == null) return;
        Path in = pkgDir.resolve("editor_overrides.json");
        if (!Files.exists(in)) return;
        try {
            JsonObject root = GSON.fromJson(Files.readString(in), JsonObject.class);
            if (root == null) return;
            JsonArray bones = root.getAsJsonArray("bones");
            if (bones != null) {
                for (int i = 0; i < bones.size(); i++) {
                    JsonObject b = bones.get(i).getAsJsonObject();
                    String boneName = b.get("bone").getAsString();
                    int idx = -1;
                    for (int j = 0; j < model.bones.size(); j++) {
                        if (model.bones.get(j).name.equals(boneName)) { idx = j; break; }
                    }
                    if (idx < 0) continue;
                    float[] pos = arr(b.getAsJsonArray("pos"), 3);
                    float[] rot = arr(b.getAsJsonArray("rot"), 3);
                    if (pos != null && rot != null) {
                        viewport.setBoneOverride(idx, new ModelViewport.BoneOverride(pos, rot));
                    }
                }
            }
            JsonArray mats = root.getAsJsonArray("materials");
            if (mats != null) {
                for (int i = 0; i < mats.size(); i++) {
                    JsonObject m = mats.get(i).getAsJsonObject();
                    int mesh = m.get("mesh").getAsInt();
                    if (mesh < 0 || mesh >= model.meshes.size()) continue;
                    float[] tint = arr(m.getAsJsonArray("tint"), 4);
                    if (tint != null) {
                        model.meshes.get(mesh).colorTint = tint;
                        model.meshes.get(mesh).alpha = tint.length >= 4 ? tint[3] : 1f;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[ModelEditorIO] Failed to load model overrides for '{}'", modelName, e);
        }
    }

    private static float[] arr(JsonArray a, int n) {
        if (a == null || a.size() < n) return null;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static Path resolvePackageDir(String name) {
        for (var pkg : GmodModelConfig.scanModelPackages()) {
            if (pkg.getName().equals(name)) return pkg.getPackageDir();
        }
        return null;
    }
}
