# AI 对话系统 — 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 给 NPC 添加专用聊天界面、AI 驱动的手势动画、多 Provider 支持、AI 自主控制 NPC 行为。

**架构：**
- **聊天界面：** 新建 `NpcChatScreen`（客户端 GUI），通过 SimpleChannel 网络包与服务器通信
- **手势系统：** AI 返回结构化 JSON `{reply, emotion, gesture, action}`，NpcChatHandler 解析后驱动动画和 NPC 行为
- **多 Provider：** AiConfigScreen 增加 Provider 下拉选择，NpcChatHandler 按 Provider 拼装不同 API 请求体
- **AI 动作：** 扩展 AINpcAgent，支持 guard/fetch 等新行为

**技术栈：** Java 17, Minecraft Forge (1.20.1), SimpleChannel (网络), HttpClient (AI API)

---

## 文件清单

### 新建文件

| # | 文件路径 | 职责 |
|---|---------|------|
| 1 | `src/main/java/transferstation/transferstation_whimsicalideas/network/NpcChatNetwork.java` | SimpleChannel 注册 + 包定义 |
| 2 | `src/main/java/transferstation/transferstation_whimsicalideas/network/ChatC2SPacket.java` | 客户端→服务端：NPC UUID + 消息文本 |
| 3 | `src/main/java/transferstation/transferstation_whimsicalideas/network/ChatS2CPacket.java` | 服务端→客户端：NPC UUID + 回复文本 + 情绪 + 手势 |
| 4 | `src/main/java/transferstation/transferstation_whimsicalideas/client/NpcChatScreen.java` | NPC 聊天 GUI 屏幕 |

### 修改文件

| # | 文件 | 变更 |
|---|------|------|
| 5 | `NpcChatHandler.java` | 重构：解析结构化 JSON、多 Provider 请求体拼装、AI 动作驱动 |
| 6 | `AiConfigScreen.java` | 增加 Provider 下拉选择器 + model 字段 |
| 7 | `NpcEntity.java` | 空手右键打开聊天界面（客户端）+ 移除旧的硬编码 "Hello!" |
| 8 | `AINpcAgent.java` | 增加 GuardGoal、FetchGoal |
| 9 | `Transferstation_whimsicalideas.java` | 注册网络通道 |
| 10 | `zh_cn.json` | 新增聊天界面/手势/Provider 的翻译 |
| 11 | `en_us.json` | 新增聊天界面/手势/Provider 的翻译 |

---

## 任务分解

---

### 任务 1：网络层 — SimpleChannel

**文件：**
- 创建：`network/NpcChatNetwork.java`
- 创建：`network/ChatC2SPacket.java`
- 创建：`network/ChatS2CPacket.java`
- 修改：`Transferstation_whimsicalideas.java`

- [ ] **步骤 1：创建网络包类**

```java
// ChatC2SPacket.java
package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public class ChatC2SPacket {
    public final UUID npcUuid;
    public final String message;

    public ChatC2SPacket(UUID npcUuid, String message) {
        this.npcUuid = npcUuid;
        this.message = message;
    }

    public static ChatC2SPacket decode(FriendlyByteBuf buf) {
        return new ChatC2SPacket(buf.readUUID(), buf.readUtf(256));
    }

    public static void encode(ChatC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.npcUuid);
        buf.writeUtf(packet.message, 256);
    }

    public static void handle(ChatC2SPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender == null) return;
            var level = sender.level();
            var entity = level.getEntity(packet.npcUuid);
            if (entity instanceof transferstation.transferstation_whimsicalideas.client.model.NpcEntity npc) {
                // Process on server thread
                npc.handleChatMessage(sender, packet.message);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
```

```java
// ChatS2CPacket.java
package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public class ChatS2CPacket {
    public final UUID npcUuid;
    public final String reply;
    public final String emotion;   // happy/angry/sad/neutral/scared
    public final String gesture;   // wave/nod/shake/point/idle

    public ChatS2CPacket(UUID npcUuid, String reply, String emotion, String gesture) {
        this.npcUuid = npcUuid;
        this.reply = reply;
        this.emotion = emotion;
        this.gesture = gesture;
    }

    public static ChatS2CPacket decode(FriendlyByteBuf buf) {
        return new ChatS2CPacket(
            buf.readUUID(),
            buf.readUtf(512),
            buf.readUtf(32),
            buf.readUtf(32)
        );
    }

    public static void encode(ChatS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.npcUuid);
        buf.writeUtf(packet.reply, 512);
        buf.writeUtf(packet.emotion, 32);
        buf.writeUtf(packet.gesture, 32);
    }

    public static void handle(ChatS2CPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return;
            var level = mc.player.level();
            var entity = level.getEntity(packet.npcUuid);
            if (entity instanceof transferstation.transferstation_whimsicalideas.client.model.NpcEntity npc) {
                // Forward to active chat screen
                if (mc.screen instanceof transferstation.transferstation_whimsicalideas.client.NpcChatScreen screen) {
                    screen.onNpcReply(packet.reply, packet.emotion, packet.gesture);
                }
                // Apply emotion/gesture on NPC
                npc.handleGesture(packet.emotion, packet.gesture);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
```

- [ ] **步骤 2：创建网络通道注册类**

```java
// NpcChatNetwork.java
package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;

public class NpcChatNetwork {
    private static final String PROTOCOL_VERSION = "1.0";
    public static final SimpleChannel CHANNEL = ChannelBuilder.named(
            new ResourceLocation(Transferstation_whimsicalideas.MODID, "npc_chat"))
        .networkProtocolVersion(1)
        .simpleChannel();

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(ChatC2SPacket.class, id++)
            .encoder(ChatC2SPacket::encode)
            .decoder(ChatC2SPacket::decode)
            .consumerNetworkThread(ChatC2SPacket::handle)
            .add();
        CHANNEL.messageBuilder(ChatS2CPacket.class, id++)
            .encoder(ChatS2CPacket::encode)
            .decoder(ChatS2CPacket::decode)
            .consumerNetworkThread(ChatS2CPacket::handle)
            .add();
    }
}
```

- [ ] **步骤 3：在主类中注册**

在 `Transferstation_whimsicalideas.java` 的构造函数中（或构造函数末尾）添加：

```java
// 注册网络通道
transferstation.transferstation_whimsicalideas.network.NpcChatNetwork.register();
```

- [ ] **步骤 4：commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/network/
git add src/main/java/transferstation/transferstation_whimsicalideas/Transferstation_whimsicalideas.java
git commit -m "feat(network): add SimpleChannel for NPC chat packets"
```

---

### 任务 2：NpcChatScreen — 聊天界面 GUI

**文件：**
- 创建：`client/NpcChatScreen.java`

- [ ] **步骤 1：编写 NpcChatScreen 骨架**

```java
package transferstation.transferstation_whimsicalideas.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;
import transferstation.transferstation_whimsicalideas.network.ChatC2SPacket;
import transferstation.transferstation_whimsicalideas.network.NpcChatNetwork;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NpcChatScreen extends Screen {

    private static final int MAX_MESSAGES = 100;
    private static final int INPUT_MAX_LENGTH = 256;

    private final UUID npcUuid;
    private final String npcName;
    private EditBox inputField;
    private final List<ChatMessage> messages = new ArrayList<>();
    private int scrollOffset;
    private long lastSendTime = 0;
    private boolean awaitingReply = false;

    // Typewriter effect state
    private String pendingReply = "";
    private String displayReply = "";
    private int typewriterIndex = 0;
    private int typewriterTimer = 0;

    public NpcChatScreen(NpcEntity npc) {
        super(Component.translatable("gui.transferstation_whimsicalideas.npc_chat.title", npc.getDisplayName().getString()));
        this.npcUuid = npc.getUUID();
        this.npcName = npc.getDisplayName().getString();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int chatHeight = Math.min(height - 80, 300);

        // Input field at bottom
        inputField = new EditBox(font, cx - 140, height - 30, 260, 18,
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.input_hint"));
        inputField.setMaxLength(INPUT_MAX_LENGTH);
        inputField.setResponder(this::onInputChanged);
        addWidget(inputField);

        // Send button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.npc_chat.send"),
            btn -> sendMessage()
        ).bounds(cx + 125, height - 30, 40, 18).build());

        // Close button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.back"),
            btn -> onClose()
        ).bounds(10, 10, 50, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);

        int cx = width / 2;
        int chatTop = 40;
        int chatHeight = Math.min(height - 80, 300);
        int chatBottom = chatTop + chatHeight;
        int lineHeight = font.lineHeight + 4;

        // Title
        graphics.drawCenteredString(font, getTitle(), cx, 18, 0xF3EFE0);

        // Chat area clipping
        RenderSystem.enableScissor(
            (int) ((cx - 150) * getMinecraft().getWindow().getGuiScale()),
            (int) ((height - chatBottom - 10) * getMinecraft().getWindow().getGuiScale()),
            (int) (300 * getMinecraft().getWindow().getGuiScale()),
            (int) ((chatHeight + 10) * getMinecraft().getWindow().getGuiScale())
        );

        int y = chatBottom - 8;
        int totalContentHeight = messages.size() * lineHeight;
        int visibleHeight = chatHeight;
        int maxScroll = Math.max(0, totalContentHeight - visibleHeight);

        // Clamp scroll
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        // Draw messages from bottom
        int pixelOffset = scrollOffset;
        for (int i = messages.size() - 1; i >= 0 && y > chatTop; i--) {
            ChatMessage msg = messages.get(i);
            String text = msg.isPlayer ? ("§e你§r: " + msg.text) : ("§b" + npcName + "§r: " + msg.text);
            int textWidth = font.width(text);
            int maxWidth = 290;
            if (textWidth > maxWidth) {
                // Wrap long text
                var lines = font.split(Component.literal(text), maxWidth);
                for (int li = lines.size() - 1; li >= 0 && y > chatTop; li--) {
                    y -= lineHeight;
                    if (y >= chatTop - 5) {
                        graphics.drawString(font, lines.get(li), cx - 145, y, 0xFFFFFF);
                    }
                }
            } else {
                y -= lineHeight;
                if (y >= chatTop - 5) {
                    graphics.drawString(font, text, cx - 145, y, 0xFFFFFF);
                }
            }
        }

        // Typewriter effect pending reply
        if (!pendingReply.isEmpty()) {
            String display = "§b" + npcName + "§r: " + displayReply;
            y -= lineHeight;
            if (y >= chatTop - 5) {
                graphics.drawString(font, display, cx - 145, y, 0xFFFFFF);
            }
        }

        RenderSystem.disableScissor();

        // Input field label
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.npc_chat.input_label"), cx - 140, height - 46, 0x888888);

        if (awaitingReply) {
            graphics.drawCenteredString(font, Component.translatable("gui.transferstation_whimsicalideas.npc_chat.typing"), cx, chatBottom + 5, 0x888888);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        scrollOffset -= (int) (deltaY * 10);
        return true;
    }

    public void sendMessage() {
        String text = inputField.getValue().trim();
        if (text.isEmpty() || awaitingReply) return;

        // Cooldown check (1 second)
        long now = System.currentTimeMillis();
        if (now - lastSendTime < 1000) return;
        lastSendTime = now;

        // Add player message locally
        messages.add(new ChatMessage(text, true));
        inputField.setValue("");
        awaitingReply = true;

        // Send to server
        NpcChatNetwork.CHANNEL.send(new ChatC2SPacket(npcUuid, text));
    }

    public void onNpcReply(String reply, String emotion, String gesture) {
        pendingReply = reply;
        displayReply = "";
        typewriterIndex = 0;
        typewriterTimer = 0;
        awaitingReply = false;
    }

    @Override
    public void tick() {
        inputField.tick();

        // Typewriter effect
        if (!pendingReply.isEmpty()) {
            typewriterTimer++;
            if (typewriterTimer >= 2) { // 2 ticks per character ≈ 10 chars/sec
                typewriterTimer = 0;
                typewriterIndex++;
                if (typewriterIndex <= pendingReply.length()) {
                    displayReply = pendingReply.substring(0, typewriterIndex);
                }
                if (typewriterIndex > pendingReply.length()) {
                    // Done: add to message history
                    messages.add(new ChatMessage(pendingReply, false));
                    pendingReply = "";
                    displayReply = "";
                    typewriterIndex = 0;
                    // Auto scroll to bottom
                    scrollOffset = 0;
                }
            }
        }

        // Trim message history
        while (messages.size() > MAX_MESSAGES) {
            messages.remove(0);
        }
    }

    private void onInputChanged(String text) {
        // No-op for now
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            onClose();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // Enter
            sendMessage();
            return true;
        }
        if (inputField.isFocused()) {
            return inputField.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (inputField.isFocused()) {
            return inputField.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (inputField.mouseClicked(mouseX, mouseY, button)) {
            setFocused(inputField);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        getMinecraft().setScreen(null);
    }

    private static class ChatMessage {
        final String text;
        final boolean isPlayer;
        ChatMessage(String text, boolean isPlayer) {
            this.text = text;
            this.isPlayer = isPlayer;
        }
    }
}
```

- [ ] **步骤 2：commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/NpcChatScreen.java
git commit -m "feat(ui): add NpcChatScreen with chat history and typewriter effect"
```

---

### 任务 3：重构 NpcChatHandler — 结构化 JSON + 多 Provider + AI 动作

**文件：**
- 修改：`client/model/NpcChatHandler.java`

- [ ] **步骤 1：添加 Provider 枚举和配置字段**

在 `NpcChatHandler.java` 类开头添加：

```java
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

public static void setProvider(AiProvider p) {
    provider = p;
    if (p != AiProvider.CUSTOM && (modelName.equals("gmod-npc") || modelName.isEmpty())) {
        modelName = p.defaultModel;
    }
}

public static AiProvider getProvider() { return provider; }

public static void setModelName(String model) { modelName = model; }
public static String getModelName() { return modelName; }
```

- [ ] **步骤 2：重构 sendMessage 支持多 Provider**

替换 `sendMessage` 方法，根据 provider 拼装不同请求体：

```java
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

                // Process structured response (NPC state changes + S2C packet)
                // Must run on the main thread of the entity's level.
                var level = npc.level();
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    // Server side: use server executor
                    serverLevel.getServer().execute(() -> processStructuredResponse(npc, rawReply, player));
                } else {
                    // Client / integrated server fallback
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null) {
                        mc.execute(() -> processStructuredResponse(npc, rawReply, player));
                    }
                }

                // Extract plain text reply for display
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
```

- [ ] **步骤 3：添加 buildRequest 方法按 Provider 拼装**

```java
private static HttpRequest buildRequest(String systemPrompt, String history, String message) {
    JsonObject body = new JsonObject();

    switch (provider) {
        case OPENAI:
        case DEEPSEEK:
            body.addProperty("model", modelName);
            var messages = new com.google.gson.JsonArray();

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

        case CUSTOM: // Legacy gmod-npc format
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

    // Ollama doesn't need auth header
    if (provider != AiProvider.OLLAMA && !apiKey.isEmpty()) {
        builder.header("Authorization", "Bearer " + apiKey);
    }

    return builder.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
}
```

- [ ] **步骤 4：添加 parseResponse 解析不同 Provider 响应**

```java
private static String parseResponse(String responseBody) {
    try {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

        switch (provider) {
            case OPENAI:
            case DEEPSEEK:
                if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
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
    // Fallback: return raw response body as plain text
    return responseBody;
}
```

- [ ] **步骤 5：添加 processStructuredResponse 替代旧的 processActions**

```java
private static void processStructuredResponse(NpcEntity npc, String rawReply, Player player) {
    NpcData data = npc.getNpcData();
    if (data == null) return;

    String emotion = "neutral";
    String gesture = "idle";
    String cleanReply = rawReply;

    // Try to parse as structured JSON
    try {
        String trimmed = rawReply.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();

            if (json.has("emotion")) {
                emotion = json.get("emotion").getAsString();
            }
            if (json.has("gesture")) {
                gesture = json.get("gesture").getAsString();
            }
            if (json.has("reply")) {
                cleanReply = json.get("reply").getAsString();
            }

            // AI-driven actions
            if (json.has("action") && json.get("action").isJsonObject()) {
                JsonObject action = json.getAsJsonObject("action");
                String actionType = action.get("type").getAsString();
                executeAiAction(npc, actionType, action);
            }
        }
    } catch (Exception e) {
        // Not JSON or parse error — fall back to keyword matching
        String lower = rawReply.toLowerCase();
        if (lower.contains("happy") || lower.contains("glad") || lower.contains("love")) {
            emotion = "happy";
            gesture = "wave";
        } else if (lower.contains("angry") || lower.contains("hate") || lower.contains("furious")) {
            emotion = "angry";
        } else if (lower.contains("scared") || lower.contains("afraid") || lower.contains("frightened")) {
            emotion = "scared";
        }
        cleanReply = rawReply;
    }

    // Apply emotion to NPC data
    switch (emotion) {
        case "happy" -> data.setCurrentMood("happy");
        case "angry" -> data.setCurrentMood("angry");
        case "scared" -> data.setCurrentMood("scared");
        case "sad" -> data.setCurrentMood("sad");
        default -> data.setCurrentMood("neutral");
    }

    // Apply gesture animation
    npc.setAnimation(gesture);

    // Apply NPC state (server authoritative — will sync via entity data)
    npc.setAnimation(gesture);
    npc.handleGesture(emotion, gesture);

    // Send reply + emotion + gesture back to the client
    if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
        transferstation.transferstation_whimsicalideas.network.NpcChatNetwork.CHANNEL.send(
            new transferstation.transferstation_whimsicalideas.network.ChatS2CPacket(
                npc.getUUID(), cleanReply, emotion, gesture),
            net.minecraftforge.network.PacketDistributor.PLAYER.with(sp)
        );
    }
}
```

- [ ] **步骤 6：添加 executeAiAction 方法**

```java
private static void executeAiAction(NpcEntity npc, String actionType, JsonObject actionParams) {
    if (npc.level().isClientSide()) return;

    switch (actionType) {
        case "chop_wood":
            npc.aiAgent.clearOrders();
            npc.aiAgent.orderChopWood();
            break;
        case "follow":
            // Find nearest player to follow
            var nearestPlayer = npc.level().getNearestPlayer(npc, 16);
            if (nearestPlayer != null) {
                npc.aiAgent.clearOrders();
                npc.aiAgent.orderFollowPlayer(nearestPlayer);
            }
            break;
        case "stop":
            npc.aiAgent.clearOrders();
            break;
        case "guard":
            npc.aiAgent.clearOrders();
            // GuardGoal takes current position
            npc.aiAgent.orderGuard(npc.blockPosition());
            break;
        case "emote":
            if (actionParams.has("animation")) {
                npc.setAnimation(actionParams.get("animation").getAsString());
            }
            break;
        default:
            LOGGER.debug("[NpcChat] Unknown AI action: {}", actionType);
    }
}
```

- [ ] **步骤 7：更新 buildSystemPrompt 告知 AI 结构化格式**

在 `buildSystemPrompt` 末尾添加：

```java
sb.append("\\n");
sb.append("IMPORTANT: When responding, you may optionally return a JSON object ");
sb.append("with the format: {\\"reply\\": \\"...\\", \\"emotion\\": \\"happy|angry|sad|neutral|scared\\", ");
sb.append("\\"gesture\\": \\"wave|nod|shake|point|idle\\", ");
sb.append("\\"action\\": {\\"type\\": \\"chop_wood|follow|stop|guard|emote\\"}");
sb.append("} to control my expressions and actions. ");
sb.append("The 'action' field is optional. If you don't return JSON, I'll just use plain text.");
```

- [ ] **步骤 8：添加 extractPlainReply 辅助方法**

```java
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
```

- [ ] **步骤 9：删除旧的 processActions 方法**

删除 `processActions(NpcEntity npc, String reply)` 方法及其所有调用，它已被 `processStructuredResponse` 取代。

- [ ] **步骤 10：commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandler.java
git commit -m "feat(chat): refactor NpcChatHandler with structured JSON, multi-provider, and AI actions"
```

---

### 任务 4：改造 AiConfigScreen — 添加 Provider 选择器

**文件：**
- 修改：`client/AiConfigScreen.java`

- [ ] **步骤 1：添加 Provider 下拉选择器和 Model 字段**

在 `AiConfigScreen.init()` 方法中，在 API Key 和 Endpoint 字段之后添加：

```java
// Provider selector
y += 28;
providerDropdown = addRenderableWidget(Button.builder(
    Component.literal("Provider: " + NpcChatHandler.getProvider().id),
    btn -> {
        // Cycle through providers
        var providers = NpcChatHandler.AiProvider.values();
        int next = (java.util.Arrays.asList(providers).indexOf(NpcChatHandler.getProvider()) + 1) % providers.length;
        NpcChatHandler.setProvider(providers[next]);
        btn.setMessage(Component.literal("Provider: " + providers[next].id));
        // Update endpoint hint
        updateEndpointForProvider(providers[next]);
    }
).pos(cx - 50, y).size(280, 18).build());

y += 28;
modelField = new EditBox(font, cx - 50, y, 280, 16, Component.translatable("gui.transferstation_whimsicalideas.model"));
modelField.setValue(NpcChatHandler.getModelName());
modelField.setMaxLength(64);
modelField.setTextColor(0xF3EFE0);
addWidget(modelField);
```

在类中添加字段：
```java
private Button providerDropdown;
private EditBox modelField;
```

添加 `updateEndpointForProvider` 方法：
```java
private void updateEndpointForProvider(NpcChatHandler.AiProvider provider) {
    switch (provider) {
        case OPENAI -> {
            endpointField.setValue("https://api.openai.com/v1/chat/completions");
            modelField.setValue("gpt-3.5-turbo");
        }
        case DEEPSEEK -> {
            endpointField.setValue("https://api.deepseek.com/v1/chat/completions");
            modelField.setValue("deepseek-chat");
        }
        case OLLAMA -> {
            endpointField.setValue("http://localhost:11434/api/chat");
            modelField.setValue("llama3");
        }
        case CUSTOM -> {
            endpointField.setValue("https://api.player2.game/v1/chat");
            modelField.setValue("gmod-npc");
        }
    }
}
```

更新 `saveConfig()` 保存新字段：
```java
props.setProperty("provider", NpcChatHandler.getProvider().id);
props.setProperty("model", modelField.getValue());
```

更新 `loadConfig()` 读取新字段：
```java
NpcChatHandler.setProvider(NpcChatHandler.AiProvider.fromId(props.getProperty("provider", "custom")));
NpcChatHandler.setModelName(props.getProperty("model", "gmod-npc"));
```

- [ ] **步骤 2：commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/AiConfigScreen.java
git commit -m "feat(config): add provider selector and model field to AiConfigScreen"
```

---

### 任务 5：修改 NpcEntity — 打开聊天界面 + 手势处理

**文件：**
- 修改：`client/model/NpcEntity.java`

- [ ] **步骤 1：添加 handleChatMessage 和 handleGesture 方法**

在 `NpcEntity.java` 的类末尾（`setAnimation` 方法附近）添加：

```java
/**
 * Called server-side when a chat packet arrives for this NPC.
 */
public void handleChatMessage(Player player, String message) {
    if (npcData == null) npcData = new NpcData();
    npcData.onInteract();

    NpcChatHandler.sendMessage(this, player, message).thenAccept(reply -> {
        // Reply is already sent via S2CPacket in processStructuredResponse
        // This runs on the server worker thread, nothing extra needed.
    });
}

/**
 * Called client-side when a gesture packet arrives.
 */
public void handleGesture(String emotion, String gesture) {
    if (npcData != null) {
        switch (emotion) {
            case "happy" -> npcData.setCurrentMood("happy");
            case "angry" -> npcData.setCurrentMood("angry");
            case "scared" -> npcData.setCurrentMood("scared");
            case "sad" -> npcData.setCurrentMood("sad");
            default -> npcData.setCurrentMood("neutral");
        }
    }
    setAnimation(gesture);
}
```

- [ ] **步骤 2：修改 mobInteract 空手逻辑**

在 `mobInteract` 方法中找到空手分支（`if (itemStack.isEmpty())`），改为：

```java
if (itemStack.isEmpty()) {
    if (level().isClientSide()) {
        // Client: open chat screen
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new transferstation.transferstation_whimsicalideas.client.NpcChatScreen(this));
    }
    // Server side: don't send "Hello!" anymore — chat screen sends messages via packets
    npcData.onInteract();
    return InteractionResult.sidedSuccess(level().isClientSide());
}
```

移除旧的空手分支中发送 "Hello!" 和成书消息的代码块（成书交互可以保留，但改为也是通过 chat screen 或保持原有方式）。

注意：成书交互（`WRITABLE_BOOK`）可以保留原样作为备用输入方式。

- [ ] **步骤 3：commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcEntity.java
git commit -m "feat(npc): open chat screen on right-click, add gesture handling"
```

---

### 任务 6：扩展 AINpcAgent — 新增 GuardGoal

**文件：**
- 修改：`npc/ai/AINpcAgent.java`

- [ ] **步骤 1：添加 orderGuard 方法和 GuardGoal 内部类**

在 `AINpcAgent.java` 类中添加：

```java
private GuardGoal guardGoal;

public void orderGuard(BlockPos position) {
    clearGoals();
    this.mode = "guard";
    this.guardGoal = new GuardGoal(npc, position);
    npc.goalSelector.addGoal(2, guardGoal);
}
```

在 `clearGoals()` 中添加：
```java
if (guardGoal != null) {
    npc.goalSelector.removeGoal(guardGoal);
    guardGoal = null;
}
```

在文件末尾（`FollowOwnerGoal` 之后）添加：

```java
/**
 * Guards a specific position: NPC patrols within 3 blocks of the target position
 * and attacks any hostile mob that comes within range.
 */
private static class GuardGoal extends Goal {
    private final Mob npc;
    private final BlockPos guardPos;
    private final double radius = 3.0;
    private int cooldown = 0;

    GuardGoal(Mob npc, BlockPos guardPos) {
        this.npc = npc;
        this.guardPos = guardPos;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return true;
    }

    @Override
    public void tick() {
        double dist = npc.distanceToSqr(
            guardPos.getX() + 0.5, guardPos.getY() + 0.5, guardPos.getZ() + 0.5);
        
        if (dist > radius * radius) {
            npc.getNavigation().moveTo(guardPos.getX() + 0.5, guardPos.getY(), guardPos.getZ() + 0.5, 1.0);
        }

        // Look for hostile mobs nearby
        if (cooldown <= 0) {
            var hostiles = npc.level().getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class,
                npc.getBoundingBox().inflate(10));
            if (!hostiles.isEmpty()) {
                npc.setTarget(hostiles.get(0));
            }
            cooldown = 20; // re-check every second
        }
        cooldown--;
    }
}
```

- [ ] **步骤 2：commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/npc/ai/AINpcAgent.java
git commit -m "feat(npc): add GuardGoal for AI-driven guard behavior"
```

---

### 任务 7：更新语言文件

**文件：**
- 修改：`assets/transferstation_whimsicalideas/lang/zh_cn.json`
- 修改：`assets/transferstation_whimsicalideas/lang/en_us.json`

- [ ] **步骤 1：中文语言文件添加新条目**

在 `zh_cn.json` 末尾（最后一个条目后，注意 JSON 逗号规则）添加：

```json
,

"gui.transferstation_whimsicalideas.npc_chat.title": "与 %s 对话",
"gui.transferstation_whimsicalideas.npc_chat.input_hint": "输入消息...",
"gui.transferstation_whimsicalideas.npc_chat.input_label": "消息：",
"gui.transferstation_whimsicalideas.npc_chat.send": "发送",
"gui.transferstation_whimsicalideas.npc_chat.typing": "%s 正在输入...",
"gui.transferstation_whimsicalideas.provider": "提供商",
"gui.transferstation_whimsicalideas.model": "模型名称"
```

- [ ] **步骤 2：英文语言文件添加新条目**

在 `en_us.json` 末尾添加：

```json
,

"gui.transferstation_whimsicalideas.npc_chat.title": "Chat with %s",
"gui.transferstation_whimsicalideas.npc_chat.input_hint": "Type a message...",
"gui.transferstation_whimsicalideas.npc_chat.input_label": "Message:",
"gui.transferstation_whimsicalideas.npc_chat.send": "Send",
"gui.transferstation_whimsicalideas.npc_chat.typing": "%s is typing...",
"gui.transferstation_whimsicalideas.provider": "Provider",
"gui.transferstation_whimsicalideas.model": "Model Name"
```

- [ ] **步骤 3：commit**

```bash
git add src/main/resources/assets/transferstation_whimsicalideas/lang/
git commit -m "feat(i18n): add chat UI and provider translations"
```

---

## 验证方法

1. **构建：** `gradlew build` 通过
2. **单人测试：** 进入世界，`/npc spawn metrocop`，右键 NPC → 弹出聊天界面
3. **AI 对话：** 在 AI Config 中配置 OpenAI/DeepSeek/Ollama，发送消息，NPC 回复带打字机效果
4. **手势：** NPC 在回复时播放对应动画（wave/nod/happy/angry 等）
5. **动作：** AI 返回 `{..., "action": {"type": "chop_wood"}}` → NPC 开始砍树
6. **Provider 切换：** 在配置界面切换 Provider，端点/模型自动更新
