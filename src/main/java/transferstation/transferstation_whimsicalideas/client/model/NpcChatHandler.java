package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class NpcChatHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final Map<String, String> conversationHistory = new ConcurrentHashMap<>();
    private static String apiKey = "";
    private static String apiEndpoint = "https://api.player2.game/v1/chat";
    private static boolean enabled = false;
    private static int maxHistoryLength = 10;
    private static boolean closed = false;

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
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", "gmod-npc");
                requestBody.addProperty("message", message);
                requestBody.addProperty("system", systemPrompt);
                requestBody.addProperty("context", history);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiEndpoint))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                    String reply;
                    if (jsonResponse.isJsonObject()
                            && jsonResponse.get("reply") instanceof JsonPrimitive
                            && ((JsonPrimitive) jsonResponse.get("reply")).isString()) {
                        reply = jsonResponse.get("reply").getAsString();
                    } else {
                        reply = Component.translatable("npc.transferstation_whimsicalideas.chat.fallback").getString();
                    }

                    updateHistory(npcId, playerName, message, reply);
                    // processActions mutates entity/render state that is read on the
                    // client main thread, so dispatch it there instead of this worker.
                    // On a dedicated server net.minecraft.client.Minecraft does not
                    // exist, so guard the call so it is a no-op there.
                    final String finalReply = reply;
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null) {
                        mc.execute(() -> processActions(npc, finalReply));
                    }

                    return reply;
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

    private static void processActions(NpcEntity npc, String reply) {
        String lower = reply.toLowerCase();
        NpcData data = npc.getNpcData();
        if (data == null) return;

        if (lower.contains("happy") || lower.contains("glad") || lower.contains("love")) {
            data.setCurrentMood("happy");
            npc.setAnimation("happy");
        } else if (lower.contains("angry") || lower.contains("hate") || lower.contains("furious")) {
            data.setCurrentMood("angry");
            npc.setAnimation("angry");
        } else if (lower.contains("scared") || lower.contains("afraid") || lower.contains("frightened")) {
            data.setCurrentMood("scared");
            npc.setAnimation("scared");
        } else if (lower.contains("wave") || lower.contains("hello") || lower.contains("hi")) {
            npc.setAnimation("wave");
        } else {
            npc.setAnimation("idle");
        }
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
