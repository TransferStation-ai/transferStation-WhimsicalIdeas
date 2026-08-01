package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.network.ChatS2CPacket;
import transferstation.transferstation_whimsicalideas.network.NpcChatNetwork;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NpcChatHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<String, String> conversationHistory = new ConcurrentHashMap<>();
    private static String apiKey = "";
    private static String apiEndpoint = "https://api.player2.game/v1/chat";
    private static boolean enabled = false;
    private static final int maxHistoryLength = 10;
    private static boolean closed = false;

    /** AI pose 单次指令骨骼上限 */
    static final int MAX_POSE_BONES = 8;

    /** 解析 AI 回复 JSON 的 pose.bones（骨骼名 → [rx, ry, rz]），非法条目忽略，超限截断。 */
    static Map<String, float[]> parsePoseBones(JsonObject bonesObj) {
        Map<String, float[]> out = new HashMap<>();
        if (bonesObj == null) return out;
        for (var entry : bonesObj.entrySet()) {
            if (out.size() >= MAX_POSE_BONES) break;
            try {
                if (!entry.getValue().isJsonArray()) continue;
                var arr = entry.getValue().getAsJsonArray();
                if (arr.size() < 3) continue;
                float[] r = new float[3];
                boolean ok = true;
                for (int i = 0; i < 3; i++) {
                    if (!arr.get(i).isJsonPrimitive() || !arr.get(i).getAsJsonPrimitive().isNumber()) {
                        ok = false;
                        break;
                    }
                    r[i] = arr.get(i).getAsFloat();
                }
                if (ok) out.put(entry.getKey(), r);
            } catch (Exception e) {
                LOGGER.warn("[NpcChat] Skipping malformed pose bone '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return out;
    }

    public enum AiProvider {
        CUSTOM("custom", "gmod-npc"),
        OPENAI("openai", "gpt-3.5-turbo"),
        DEEPSEEK("deepseek", "deepseek-chat"),
        OLLAMA("ollama", "llama3");

        public final String id;
        public final String defaultModel;
        AiProvider(String id, String defaultModel) {
            this.id = id;
            this.defaultModel = defaultModel;
        }

        public static AiProvider fromId(String id) {
            for (AiProvider p : values()) {
                if (p.id.equals(id)) return p;
            }
            return CUSTOM;
        }
    }

    private static AiProvider provider = AiProvider.CUSTOM;
    private static String modelName = "gmod-npc";

    public static void setProvider(AiProvider p) { provider = p; }
    public static AiProvider getProvider() { return provider; }
    public static void setModelName(String model) { modelName = model; }
    public static String getModelName() { return modelName; }

    public static void setApiKey(String key) {
        apiKey = key;
    }

    public static String getApiKey() {
        return apiKey;
    }

    public static void setApiEndpoint(String endpoint) {
        apiEndpoint = endpoint;
    }

    public static String getApiEndpoint() {
        return apiEndpoint;
    }

    public static void setEnabled(boolean enable) {
        enabled = enable;
    }

    public static boolean isEnabled() {
        return enabled && !apiKey.isEmpty();
    }

    public static CompletableFuture<String> sendMessage(NpcEntity npc, Player player, String message) {
        if (!isEnabled()) {
            return CompletableFuture.completedFuture(
                Component.translatable("npc.transferstation_whimsicalideas.chat.disabled").getString());
        }

        String npcId = npc.getStringUUID();
        String playerName = player.getName().getString();
        String systemPrompt = buildSystemPrompt(npc, player);
        String history = conversationHistory.getOrDefault(npcId, "");

        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = buildRequest(systemPrompt, history, message);
                HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String rawReply = response.body();
                    String reply = parseResponse(rawReply);
                    updateHistory(npcId, playerName, message, reply);

                    var level = npc.level();
                    if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                        serverLevel.getServer().execute(() -> processStructuredResponse(npc, rawReply, player));
                    } else {
                        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                        mc.execute(() -> processStructuredResponse(npc, rawReply, player));
                    }

                    return extractPlainReply(reply);
                } else {
                    LOGGER.warn("[NpcChat] API returned status {}: {}", response.statusCode(), response.body());
                    return Component.translatable("npc.transferstation_whimsicalideas.chat.error").getString();
                }
            } catch (Exception e) {
                LOGGER.error("[NpcChat] Failed to send message to AI", e);
                return Component.translatable("npc.transferstation_whimsicalideas.chat.foggy").getString();
            }
        });
    }

    private static String buildSystemPrompt(NpcEntity npc, Player player) {
        NpcData data = npc.getNpcData();
        String personality = data != null ? data.getAiPersonality() : "friendly";
        String mood = data != null ? data.getCurrentMood() : "neutral";
        float affection = data != null ? data.getAffection() : 50;
        float loyalty = data != null ? data.getLoyalty() : 50;

        StringBuilder sb = new StringBuilder();
        sb.append("You are an NPC in Minecraft named '").append(npc.getModelName()).append("'. ");
        sb.append("Your personality is ").append(personality).append(". ");
        sb.append("Your current mood is ").append(mood).append(". ");
        sb.append("Your affection toward the player is ").append(String.format("%.0f", affection)).append("/100. ");
        sb.append("Your loyalty is ").append(String.format("%.0f", loyalty)).append("/100. ");

        if (affection > 70) {
            sb.append("You really like the player and want to help them. ");
        } else if (affection < 30) {
            sb.append("You don't trust the player much. ");
        }

        if (loyalty > 80) {
            sb.append("You are very loyal to your owner. ");
        } else if (loyalty < 20) {
            sb.append("You might betray your owner if the opportunity arises. ");
        }

        sb.append("Respond in character, keep responses short (1-2 sentences). ");
        sb.append("You can express emotions and react to what the player says. ");
        sb.append("You know about Minecraft and the world around you.");
        sb.append("\n");
        sb.append("IMPORTANT: When responding, you may optionally return a JSON object ");
        sb.append("with the format: {\"reply\": \"...\", \"emotion\": \"happy|angry|sad|neutral|scared\", ");
        sb.append("\"gesture\": \"wave|nod|shake|point|idle\", ");
        sb.append("\"action\": {\"type\": \"chop_wood|follow|stop|guard|emote\"}, ");
        sb.append("\"pose\": {\"bones\": {\"ValveBiped.Bip01_Head\": [0, 0.5, 0]}, \"duration\": 2.0}} ");
        sb.append("to control my expressions, actions and bones. ");
        sb.append("The 'action' and 'pose' fields are optional. ");
        sb.append("For 'pose', bones accepts Source engine bone names (e.g. \"ValveBiped.Bip01_Head\", \"ValveBiped.Bip01_R_UpperArm\", \"ValveBiped.Bip01_L_Hand\") or VMD-style names (\"Bip01 Head\"); ");
        sb.append("angles are in radians as [rx, ry, rz]; 'duration' is in seconds (0.5-10). ");
        sb.append("If you don't return JSON, I'll just use plain text.");

        return sb.toString();
    }

    private static void updateHistory(String npcId, String playerName, String message, String reply) {
        String entry = playerName + ": " + message + "\nNPC: " + reply + "\n";
        conversationHistory.merge(npcId, entry, (existing, newEntry) -> {
            String current = existing + newEntry;
            String[] lines = current.split("\n");
            if (lines.length > maxHistoryLength * 2) {
                StringBuilder trimmed = new StringBuilder();
                for (int i = lines.length - maxHistoryLength * 2; i < lines.length; i++) {
                    trimmed.append(lines[i]).append("\n");
                }
                current = trimmed.toString();
            }
            return current;
        });
    }

    private static HttpRequest buildRequest(String systemPrompt, String history, String message) {
        JsonObject body = new JsonObject();

        switch (provider) {
            case OPENAI:
            case DEEPSEEK:
                body.addProperty("model", modelName);
                var messages = getJsonElements(systemPrompt, history, message);

                body.add("messages", messages);
                body.addProperty("temperature", 0.7);
                body.addProperty("max_tokens", 256);
                break;

            case OLLAMA:
                body.addProperty("model", modelName);
                body.addProperty("system", systemPrompt);
                body.addProperty("prompt", message);
                body.addProperty("stream", false);
                break;

            case CUSTOM:
            default:
                body.addProperty("model", modelName);
                body.addProperty("message", message);
                body.addProperty("system", systemPrompt);
                body.addProperty("context", history);
                break;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(apiEndpoint))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15));

        if (provider != AiProvider.OLLAMA && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }

    private static @NotNull JsonArray getJsonElements(String systemPrompt, String history, String message) {
        var messages = new JsonArray();

        var sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        if (!history.isEmpty()) {
            var histMsg = new JsonObject();
            histMsg.addProperty("role", "assistant");
            histMsg.addProperty("content", history);
            messages.add(histMsg);
        }

        var userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message);
        messages.add(userMsg);
        return messages;
    }

    private static String parseResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            switch (provider) {
                case OPENAI:
                case DEEPSEEK:
                    if (json.has("choices") && !json.getAsJsonArray("choices").isEmpty()) {
                        return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                            .get("message").getAsJsonObject().get("content").getAsString();
                    }
                    break;
                case OLLAMA:
                    if (json.has("message")) {
                        return json.get("message").getAsJsonObject().get("content").getAsString();
                    }
                    if (json.has("response")) {
                        return json.get("response").getAsString();
                    }
                    break;
                case CUSTOM:
                default:
                    if (json.has("reply") && json.get("reply").isJsonPrimitive()) {
                        return json.get("reply").getAsString();
                    }
                    break;
            }
        } catch (Exception e) {
            LOGGER.warn("[NpcChat] Failed to parse response JSON, treating as plain text");
        }
        return responseBody;
    }

    private static void processStructuredResponse(NpcEntity npc, String rawReply, Player player) {
        NpcData data = npc.getNpcData();
        if (data == null) return;

        String emotion = "neutral";
        String gesture = "idle";
        String cleanReply = rawReply;
        Map<String, float[]> poseBones = null;
        float poseDuration = 2.0f;

        try {
            String trimmed = rawReply.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();

                if (json.has("emotion")) emotion = json.get("emotion").getAsString();
                if (json.has("gesture")) gesture = json.get("gesture").getAsString();
                if (json.has("reply")) cleanReply = json.get("reply").getAsString();

                if (json.has("pose") && json.get("pose").isJsonObject()) {
                    JsonObject pose = json.getAsJsonObject("pose");
                    if (pose.has("bones") && pose.get("bones").isJsonObject()) {
                        poseBones = parsePoseBones(pose.getAsJsonObject("bones"));
                    }
                    if (pose.has("duration") && pose.get("duration").isJsonPrimitive()) {
                        poseDuration = pose.get("duration").getAsFloat();
                    }
                }

                if (json.has("action") && json.get("action").isJsonObject()) {
                    JsonObject action = json.getAsJsonObject("action");
                    String actionType = action.get("type").getAsString();
                    executeAiAction(npc, actionType, action);
                }
            }
        } catch (Exception e) {
            String lower = rawReply.toLowerCase();
            if (lower.contains("happy") || lower.contains("glad") || lower.contains("love")) {
                emotion = "happy"; gesture = "wave";
            } else if (lower.contains("angry") || lower.contains("hate") || lower.contains("furious")) {
                emotion = "angry";
            } else if (lower.contains("scared") || lower.contains("afraid") || lower.contains("frightened")) {
                emotion = "scared";
            }
            cleanReply = rawReply;
        }

        switch (emotion) {
            case "happy" -> data.setCurrentMood("happy");
            case "angry" -> data.setCurrentMood("angry");
            case "scared" -> data.setCurrentMood("scared");
            case "sad" -> data.setCurrentMood("sad");
            default -> data.setCurrentMood("neutral");
        }

        npc.handleGesture(emotion, gesture);
        if (poseBones != null && !poseBones.isEmpty()) {
            float clampedDuration = Math.max(0.5f, Math.min(10.0f, poseDuration));
            npc.applyBonePose(poseBones, clampedDuration);
            poseDuration = clampedDuration;
        }

        // Send S2C packet to the player
        if (player instanceof ServerPlayer sp) {
            NpcChatNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new ChatS2CPacket(npc.getUUID(), cleanReply, emotion, gesture, poseBones, poseDuration)
            );
        }
    }

    private static void executeAiAction(NpcEntity npc, String actionType, JsonObject actionParams) {
        if (npc.level().isClientSide()) return;

        switch (actionType) {
            case "chop_wood" -> {
                npc.aiAgent.clearOrders();
                npc.aiAgent.orderChopWood();
            }
            case "follow" -> {
                var nearestPlayer = npc.level().getNearestPlayer(npc, 16);
                if (nearestPlayer != null) {
                    npc.aiAgent.clearOrders();
                    npc.aiAgent.orderFollowPlayer(nearestPlayer);
                }
            }
            case "stop" -> npc.aiAgent.clearOrders();
            case "guard" -> {
                npc.aiAgent.clearOrders();
                npc.aiAgent.orderGuard(npc.blockPosition());
            }
            case "emote" -> {
                if (actionParams.has("animation")) {
                    npc.setAnimation(actionParams.get("animation").getAsString());
                }
            }
            default -> LOGGER.debug("[NpcChat] Unknown AI action: {}", actionType);
        }
    }

    private static String extractPlainReply(String rawReply) {
        try {
            String trimmed = rawReply.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();
                if (json.has("reply") && json.get("reply").isJsonPrimitive()) {
                    return json.get("reply").getAsString();
                }
            }
        } catch (Exception ignored) {}
        return rawReply;
    }

    public static void clearHistory(String npcId) {
        conversationHistory.remove(npcId);
    }

    public static void clearAllHistory() {
        conversationHistory.clear();
    }

    public static void shutdown() {
        if (!closed) {
            closed = true;
            conversationHistory.clear();
            // HTTP_CLIENT is a static singleton shared for the JVM lifetime;
            // it will be cleaned up when the classloader is unloaded.
            // java.net.http.HttpClient.close() is available only on Java 21+.
        }
    }
}
