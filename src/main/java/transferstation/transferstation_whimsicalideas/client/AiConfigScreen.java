package transferstation.transferstation_whimsicalideas.client;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.model.NpcChatHandler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class AiConfigScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditBox apiKeyField;
    private EditBox endpointField;
    private Button enableButton;
    private Button providerDropdown;
    private EditBox modelField;
    private boolean enabled;
    private String statusMessage = "";
    private int statusTimer = 0;
    private StatusType statusType = StatusType.INFO;
    private enum StatusType { INFO, SUCCESS, ERROR }
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final Path CONFIG_PATH = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve("transferstation_whimsicalideas").resolve("ai_provider.properties");

    protected AiConfigScreen() {
        super(Component.translatable("gui.transferstation_whimsicalideas.ai_provider_config"));
        loadConfig();
    }

    private void loadConfig() {
        Properties props = new Properties();
        if (Files.exists(CONFIG_PATH)) {
            try (var reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                LOGGER.warn("[AiConfig] Failed to load config", e);
            }
        }
        NpcChatHandler.setApiKey(props.getProperty("apiKey", ""));
        NpcChatHandler.setApiEndpoint(props.getProperty("endpoint", "https://api.player2.game/v1/chat"));
        NpcChatHandler.setEnabled(Boolean.parseBoolean(props.getProperty("enabled", "false")));
        NpcChatHandler.setProvider(NpcChatHandler.AiProvider.fromId(props.getProperty("provider", "custom")));
        NpcChatHandler.setModelName(props.getProperty("model", "gmod-npc"));
        this.enabled = NpcChatHandler.isEnabled();
    }

    private void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Properties props = new Properties();
            props.setProperty("apiKey", apiKeyField.getValue());
            props.setProperty("endpoint", endpointField.getValue());
            props.setProperty("enabled", String.valueOf(enabled));
            props.setProperty("provider", NpcChatHandler.getProvider().id);
            props.setProperty("model", modelField.getValue());
            try (var writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                props.store(writer, "AI Provider Configuration for TransferStation WhimsicalIdeas");
            }
            NpcChatHandler.setApiKey(apiKeyField.getValue());
            NpcChatHandler.setApiEndpoint(endpointField.getValue());
            NpcChatHandler.setEnabled(enabled);
            NpcChatHandler.setModelName(modelField.getValue());
            statusMessage = Component.translatable("gui.transferstation_whimsicalideas.saved_successfully").getString();
            statusTimer = 60;
            statusType = StatusType.SUCCESS;
        } catch (IOException e) {
            statusMessage = Component.translatable("gui.transferstation_whimsicalideas.save_failed", e.getMessage()).getString();
            statusTimer = 100;
            statusType = StatusType.ERROR;
        }
    }

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

    @Override
    protected void init() {
        int cx = width / 2;

        addRenderableWidget(new Button.Builder(
                Component.translatable("gui.transferstation_whimsicalideas.back"), btn -> onClose()
        ).pos(cx - 150, 10).size(50, 18).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.test_connection"),
                btn -> testConnection()
        ).pos(cx + 100, 10).size(80, 18).build());

        int y = 50;

        apiKeyField = new EditBox(font, cx - 50, y, 280, 16, Component.translatable("gui.transferstation_whimsicalideas.api_key"));
        apiKeyField.setValue(NpcChatHandler.getApiKey());
        apiKeyField.setMaxLength(512);
        apiKeyField.setTextColor(0xF3EFE0);
        addWidget(apiKeyField);

        y += 28;
        endpointField = new EditBox(font, cx - 50, y, 280, 16, Component.translatable("gui.transferstation_whimsicalideas.endpoint"));
        endpointField.setValue(NpcChatHandler.getApiEndpoint());
        endpointField.setMaxLength(256);
        endpointField.setTextColor(0xF3EFE0);
        addWidget(endpointField);

        y += 28;
        enableButton = addRenderableWidget(Button.builder(
                Component.translatable(enabled ? "gui.transferstation_whimsicalideas.ai_chat_enabled" : "gui.transferstation_whimsicalideas.ai_chat_disabled"),
                btn -> {
                    enabled = !enabled;
                    btn.setMessage(Component.translatable(enabled ? "gui.transferstation_whimsicalideas.ai_chat_enabled" : "gui.transferstation_whimsicalideas.ai_chat_disabled"));
                }
        ).pos(cx - 50, y).size(280, 18).build());

        // Provider selector
        y += 28;
        providerDropdown = addRenderableWidget(Button.builder(
            Component.literal("Provider: " + NpcChatHandler.getProvider().id),
            btn -> {
                var providers = NpcChatHandler.AiProvider.values();
                int next = (java.util.Arrays.asList(providers).indexOf(NpcChatHandler.getProvider()) + 1) % providers.length;
                NpcChatHandler.setProvider(providers[next]);
                btn.setMessage(Component.literal("Provider: " + providers[next].id));
                updateEndpointForProvider(providers[next]);
            }
        ).pos(cx - 50, y).size(280, 18).build());

        y += 28;
        modelField = new EditBox(font, cx - 50, y, 280, 16, Component.translatable("gui.transferstation_whimsicalideas.model"));
        modelField.setValue(NpcChatHandler.getModelName());
        modelField.setMaxLength(64);
        modelField.setTextColor(0xF3EFE0);
        addWidget(modelField);

        y += 28;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.save_config"),
                btn -> saveConfig()
        ).pos(cx - 50, y).size(280, 18).build());

        y += 28;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.clear_history"),
                btn -> {
                    NpcChatHandler.clearAllHistory();
                    statusMessage = Component.translatable("gui.transferstation_whimsicalideas.history_cleared").getString();
                    statusTimer = 60;
                    statusType = StatusType.SUCCESS;
                }
        ).pos(cx - 50, y).size(280, 18).build());
    }

    private void testConnection() {
        String key = apiKeyField.getValue();
        if (key.isEmpty()) {
            statusMessage = Component.translatable("gui.transferstation_whimsicalideas.test_key_first").getString();
            statusTimer = 80;
            statusType = StatusType.ERROR;
            return;
        }
        String ep = endpointField.getValue();
        statusMessage = Component.translatable("gui.transferstation_whimsicalideas.testing").getString();
        statusTimer = 200;
        statusType = StatusType.INFO;

        CompletableFuture.runAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("model", "gmod-npc");
                body.addProperty("message", "ping");
                body.addProperty("system", "Reply with just the word: pong");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ep))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    final String msg = Component.translatable("gui.transferstation_whimsicalideas.test_success").getString();
                    minecraft.tell(() -> {
                        statusMessage = msg;
                        statusType = StatusType.SUCCESS;
                        statusTimer = 100;
                    });
                } else {
                    final String msg = Component.translatable("gui.transferstation_whimsicalideas.test_server_error", response.statusCode()).getString();
                    minecraft.tell(() -> {
                        statusMessage = msg;
                        statusType = StatusType.ERROR;
                        statusTimer = 100;
                    });
                }
            } catch (Exception e) {
                final String msg = Component.translatable("gui.transferstation_whimsicalideas.test_failed", e.getMessage()).getString();
                minecraft.tell(() -> {
                    statusMessage = msg;
                    statusType = StatusType.ERROR;
                    statusTimer = 100;
                });
            }
        });
    }

    @Override
    public void tick() {
        apiKeyField.tick();
        endpointField.tick();
        if (modelField != null) modelField.tick();
        if (statusTimer > 0) statusTimer--;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);

        int cx = width / 2;

        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.api_key_label"), cx - 150, 54, 0xF3EFE0);
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.endpoint_label"), cx - 150, 82, 0xF3EFE0);

        graphics.drawString(font,
                Component.translatable("gui.transferstation_whimsicalideas.provider_info"), cx - 150, 180, 0x888888);
        graphics.drawString(font,
                Component.translatable("gui.transferstation_whimsicalideas.provider_description"), cx - 150, 194, 0x666666);
        graphics.drawString(font,
                Component.translatable("gui.transferstation_whimsicalideas.endpoint_description"), cx - 150, 207, 0x666666);

        if (statusTimer > 0 && !statusMessage.isEmpty()) {
            int color = switch (statusType) {
                case ERROR -> 0xFF5555;
                case SUCCESS -> 0x55FF55;
                default -> 0xF3EFE0;
            };
            graphics.drawCenteredString(font, Component.literal(statusMessage), width / 2, height - 30, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (apiKeyField.mouseClicked(mouseX, mouseY, button)) setFocused(apiKeyField);
        else if (endpointField.mouseClicked(mouseX, mouseY, button)) setFocused(endpointField);
        else if (modelField.mouseClicked(mouseX, mouseY, button)) setFocused(modelField);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (apiKeyField.isFocused()) return apiKeyField.charTyped(codePoint, modifiers);
        if (endpointField.isFocused()) return endpointField.charTyped(codePoint, modifiers);
        if (modelField.isFocused()) return modelField.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { onClose(); return true; }
        if (apiKeyField.isFocused()) return apiKeyField.keyPressed(keyCode, scanCode, modifiers);
        if (endpointField.isFocused()) return endpointField.keyPressed(keyCode, scanCode, modifiers);
        if (modelField.isFocused()) return modelField.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        if (apiKeyField != null) NpcChatHandler.setApiKey(apiKeyField.getValue());
        if (endpointField != null) NpcChatHandler.setApiEndpoint(endpointField.getValue());
        NpcChatHandler.setEnabled(enabled);
        minecraft.setScreen(null);
    }
}
