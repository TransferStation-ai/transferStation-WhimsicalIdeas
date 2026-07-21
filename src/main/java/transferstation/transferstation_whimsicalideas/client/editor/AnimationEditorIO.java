package transferstation.transferstation_whimsicalideas.client.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serializes an {@link AnimationEditorScreen.EditableAnimation} to a custom
 * animation JSON that {@code AnimationProcessor} can load, and back.
 *
 * Format (per-bone tracks with keyframes, plus a morph track):
 * {
 *   "name": "...",
 *   "fps": 20,
 *   "frameCount": 60,
 *   "loop": true,
 *   "bones": [ { "bone": "bip01", "keys": [ { "frame":0, "pos":[..], "rot":[..] }, ... ] }, ... ],
 *   "morphs": [ { "morph": "smile", "keys": [ { "frame":0, "weight":1.0 } ] } ]
 * }
 */
public final class AnimationEditorIO {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationEditorIO.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AnimationEditorIO() {}

    public static Path save(AnimationEditorScreen.EditableAnimation anim, String modelName, Path packageDir) {
        Path out = packageDir.resolve("CustomAnim").resolve(modelName + "_edit.json");
        try {
            Files.createDirectories(out.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("name", modelName + "_edit");
            root.addProperty("fps", 20);
            root.addProperty("frameCount", anim.frameCount);
            root.addProperty("loop", anim.loop);

            JsonArray bones = new JsonArray();
            // Group bone keys by bone name.
            java.util.Map<String, JsonArray> boneMap = new java.util.LinkedHashMap<>();
            for (var e : anim.boneKeys.entrySet()) {
                AnimationEditorScreen.BoneKey k = e.getKey();
                AnimationEditorScreen.EditableAnimation.BoneKeyframe kf = e.getValue();
                String boneName = "bone" + k.bone; // placeholder; resolved by index at load
                JsonObject jo = new JsonObject();
                jo.addProperty("frame", k.frame);
                jo.add("pos", arr(kf.pos));
                jo.add("rot", arr(kf.rot));
                boneMap.computeIfAbsent(boneName, x -> new JsonArray()).add(jo);
            }
            for (var e : boneMap.entrySet()) {
                JsonObject b = new JsonObject();
                b.addProperty("boneRef", e.getKey());
                b.add("keys", e.getValue());
                bones.add(b);
            }
            root.add("bones", bones);

            JsonArray morphs = new JsonArray();
            java.util.Map<String, JsonArray> morphMap = new java.util.LinkedHashMap<>();
            for (var e : anim.morphKeys.entrySet()) {
                AnimationEditorScreen.MorphKey k = e.getKey();
                JsonObject jo = new JsonObject();
                jo.addProperty("frame", k.frame);
                jo.addProperty("weight", e.getValue());
                morphMap.computeIfAbsent(k.morph, x -> new JsonArray()).add(jo);
            }
            for (var e : morphMap.entrySet()) {
                JsonObject m = new JsonObject();
                m.addProperty("morph", e.getKey());
                m.add("keys", e.getValue());
                morphs.add(m);
            }
            root.add("morphs", morphs);

            Files.writeString(out, GSON.toJson(root));
            return out;
        } catch (Exception e) {
            LOGGER.warn("[AnimationEditorIO] Failed to save animation '{}'", modelName, e);
            return null;
        }
    }

    /** Load a saved editable animation back into the editor document. */
    public static AnimationEditorScreen.EditableAnimation load(Path file) {
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) return null;
            AnimationEditorScreen.EditableAnimation anim = new AnimationEditorScreen.EditableAnimation();
            if (root.has("frameCount")) anim.frameCount = root.get("frameCount").getAsInt();
            if (root.has("loop")) anim.loop = root.get("loop").getAsBoolean();

            JsonArray bones = root.getAsJsonArray("bones");
            if (bones != null) {
                for (int i = 0; i < bones.size(); i++) {
                    JsonObject b = bones.get(i).getAsJsonObject();
                    String ref = b.get("boneRef").getAsString();
                    int boneIdx = Integer.parseInt(ref.replace("bone", ""));
                    JsonArray keys = b.getAsJsonArray("keys");
                    for (int j = 0; j < keys.size(); j++) {
                        JsonObject k = keys.get(j).getAsJsonObject();
                        int frame = k.get("frame").getAsInt();
                        float[] pos = arr(k.getAsJsonArray("pos"), 3);
                        float[] rot = arr(k.getAsJsonArray("rot"), 3);
                        if (pos != null && rot != null) {
                            anim.boneKeys.put(new AnimationEditorScreen.BoneKey(boneIdx, frame),
                                    new AnimationEditorScreen.EditableAnimation.BoneKeyframe(pos, rot));
                        }
                    }
                }
            }
            JsonArray morphs = root.getAsJsonArray("morphs");
            if (morphs != null) {
                for (int i = 0; i < morphs.size(); i++) {
                    JsonObject m = morphs.get(i).getAsJsonObject();
                    String morph = m.get("morph").getAsString();
                    JsonArray keys = m.getAsJsonArray("keys");
                    for (int j = 0; j < keys.size(); j++) {
                        JsonObject k = keys.get(j).getAsJsonObject();
                        int frame = k.get("frame").getAsInt();
                        float weight = k.get("weight").getAsFloat();
                        anim.morphKeys.put(new AnimationEditorScreen.MorphKey(morph, frame), weight);
                    }
                }
            }
            return anim;
        } catch (Exception e) {
            LOGGER.warn("[AnimationEditorIO] Failed to load animation from '{}'", file, e);
            return null;
        }
    }

    /** Convert an editable animation into the runtime {@link AnimationData} form. */
    public static AnimationData toRuntime(AnimationEditorScreen.EditableAnimation anim, String name) {
        AnimationData data = new AnimationData(name, 20f, anim.frameCount, anim.loop);
        java.util.Map<Integer, AnimationData.AnimationTrack> tracks = new java.util.LinkedHashMap<>();
        for (var e : anim.boneKeys.entrySet()) {
            AnimationEditorScreen.BoneKey k = e.getKey();
            AnimationData.AnimationTrack track = tracks.computeIfAbsent(k.bone,
                    bi -> new AnimationData.AnimationTrack("bone" + bi));
            track.addKeyFrame(new AnimationData.KeyFrame(k.frame, e.getValue().pos, e.getValue().rot, new float[]{1,1,1}));
        }
        data.tracks.addAll(tracks.values());
        return data;
    }

    private static JsonArray arr(float[] v) {
        JsonArray a = new JsonArray();
        for (float x : v) a.add(x);
        return a;
    }

    private static float[] arr(JsonArray a, int n) {
        if (a == null || a.size() < n) return null;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }
}
