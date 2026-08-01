// JsonAnimationLoader.java - Loads animation data from JSON format
package transferstation.transferstation_whimsicalideas.client.animation;

import com.google.gson.Gson;

public class JsonAnimationLoader {

    private static final Gson GSON = new Gson();

    public static AnimationData loadFromJson(String jsonContent) {
        return GSON.fromJson(jsonContent, AnimationData.class);
    }

    public static AnimationData loadFromFile(java.nio.file.Path filePath) throws java.io.IOException {
        String jsonContent = java.nio.file.Files.readString(filePath);
        return loadFromJson(jsonContent);
    }

    public static String saveToJson(AnimationData animation) {
        return GSON.toJson(animation);
    }

    public static AnimationData createDefaultAnimation(String name, int durationTicks) {
        return new AnimationData(name, 20.0f, durationTicks, true);
    }
}