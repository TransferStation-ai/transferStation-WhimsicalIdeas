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
import transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService;
import transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig;
import transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine;
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

    // Voice input state
    private boolean voiceModeAvailable = false;
    private boolean isRecording = false;
    private Button micButton;
    private String voiceStatusText = "";
    private int voiceStatusTimer = 0;

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

        // Mic button (to the left of the input field)
        boolean micAvail = VoiceCaptureService.isAvailable()
                && VoiceConfig.isEnabled()
                && VoskSttEngine.isInitialized();
        micButton = addRenderableWidget(Button.builder(
            Component.literal(micAvail ? "🎤" : "§7🎤"),
            btn -> handleMicPress()
        ).bounds(cx - 165, height - 30, 20, 18).build());
        micButton.active = micAvail;
        this.voiceModeAvailable = micAvail;

        // Input field at bottom (shifted right to make room for mic button)
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

        // Voice status text
        if (!voiceStatusText.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(voiceStatusText), cx, chatBottom + 16, 0xFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scrollOffset -= (int) (delta * 10);
        return true;
    }

    private void handleMicPress() {
        if (!voiceModeAvailable) return;

        if (!isRecording) {
            // Start recording
            isRecording = true;
            micButton.setMessage(Component.literal("§c🔴"));
            voiceStatusText = "§c录音中...";
            voiceStatusTimer = 0;

            VoiceCaptureService.startRecording(wavData -> {
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    micButton.setMessage(Component.literal("§e⏳"));
                    voiceStatusText = "§e识别中...";

                    VoskSttEngine.transcribe(wavData)
                        .thenAccept(text -> {
                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                isRecording = false;
                                if (text != null && !text.isEmpty()) {
                                    if (VoiceConfig.isAutoSend()) {
                                        inputField.setValue(text);
                                        sendMessage();
                                    } else {
                                        inputField.setValue(text);
                                    }
                                    voiceStatusText = "";
                                } else {
                                    voiceStatusText = "§7未检测到语音";
                                    voiceStatusTimer = 40;
                                }
                                micButton.setMessage(Component.literal("🎤"));
                            });
                        });
                });
            });
        } else {
            // Stop recording
            VoiceCaptureService.stopRecording();
        }
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

        // Send to server — use PacketDistributor.SERVER.noArg() for client→server
        NpcChatNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.SERVER.noArg(), new ChatC2SPacket(npcUuid, text));
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

        // Voice status timer
        if (voiceStatusTimer > 0) {
            voiceStatusTimer--;
            if (voiceStatusTimer <= 0) voiceStatusText = "";
        }

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
