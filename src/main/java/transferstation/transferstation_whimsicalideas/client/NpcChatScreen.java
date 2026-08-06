package transferstation.transferstation_whimsicalideas.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
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
    private static final int QUICK_REPLY_COUNT = 6;
    private static final String[] DEFAULT_QUICK_REPLIES = {
        "Hello!",
        "Follow me",
        "Stay here",
        "What do you think?",
        "Goodbye!",
        "Thank you"
    };

    private final UUID npcUuid;
    private final String npcName;
    private EditBox inputField;
    private final List<ChatMessage> messages = new ArrayList<>();
    private int scrollOffset;
    private long lastSendTime = 0;
    private boolean awaitingReply = false;
    private boolean showQuickReplies = false;

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

    // Message history navigation
    private int historyIndex = -1;
    private final List<String> sentHistory = new ArrayList<>();

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
            Component.literal(micAvail ? "\uD83C\uDFA4" : "\u00A77\uD83C\uDFA4"),
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

        // Quick reply toggle button
        addRenderableWidget(Button.builder(
            Component.literal(showQuickReplies ? "\u25BC Quick" : "\u25B6 Quick"),
            btn -> {
                showQuickReplies = !showQuickReplies;
                btn.setMessage(Component.literal(showQuickReplies ? "\u25BC Quick" : "\u25B6 Quick"));
            }
        ).bounds(cx + 167, height - 30, 52, 18).build());

        // Quick reply buttons (hidden by default, shown when toggled)
        if (showQuickReplies) {
            int qrStartY = height - 56;
            for (int i = 0; i < QUICK_REPLY_COUNT; i++) {
                final int idx = i;
                String label = i < DEFAULT_QUICK_REPLIES.length ? DEFAULT_QUICK_REPLIES[i] : "";
                if (label.isEmpty()) continue;
                addRenderableWidget(Button.builder(
                    Component.literal(label),
                    btn -> {
                        inputField.setValue(DEFAULT_QUICK_REPLIES[idx]);
                        sendMessage();
                    }
                ).bounds(cx - 150 + (i % 3) * 102, qrStartY - (i / 3) * 20, 98, 16).build());
            }
        }

        // Close button
        addRenderableWidget(Button.builder(
            Component.translatable("gui.transferstation_whimsicalideas.back"),
            btn -> onClose()
        ).bounds(10, 10, 50, 18).build());
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);

        int cx = width / 2;
        int chatTop = 40;
        int chatHeight = Math.min(height - 80, 300);
        int chatBottom = chatTop + chatHeight;
        int lineHeight = font.lineHeight + 4;

        // Title
        graphics.drawCenteredString(font, getTitle(), cx, 18, 0xF3EFE0);

        // Chat area background
        graphics.fillGradient(cx - 152, chatTop - 2, cx + 152, chatBottom + 2, 0xFF1A1A1A, 0xFF1A1A1A);

        // Chat area clipping
        RenderSystem.enableScissor(
            (int) ((cx - 150) * getMinecraft().getWindow().getGuiScale()),
            (int) ((height - chatBottom - 10) * getMinecraft().getWindow().getGuiScale()),
            (int) (300 * getMinecraft().getWindow().getGuiScale()),
            (int) ((chatHeight + 10) * getMinecraft().getWindow().getGuiScale())
        );

        int y = chatBottom - 8;
        int totalContentHeight = messages.size() * lineHeight;
        int maxScroll = Math.max(0, totalContentHeight - chatHeight);

        // Clamp scroll
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        // Draw messages from bottom
        for (int i = messages.size() - 1; i >= 0 && y > chatTop; i--) {
            ChatMessage msg = messages.get(i);

            // Draw message bubble background
            int msgMaxWidth = 280;
            String rawText = msg.isPlayer ? msg.text : msg.text;
            List<FormattedCharSequence> lines = font.split(Component.literal(rawText), msgMaxWidth);
            int bubbleHeight = lines.size() * lineHeight + 4;
            int bubbleX = msg.isPlayer ? cx + 5 : cx - 148;
            int bubbleW = 0;
            for (FormattedCharSequence line : lines) {
                bubbleW = Math.max(bubbleW, font.width(line));
            }
            bubbleW = Math.min(bubbleW + 8, msgMaxWidth);

            // Render bubble
            int bubbleColor = msg.isPlayer ? 0xFF2A5296 : 0xFF3A3A3A;
            graphics.fillGradient(bubbleX, y - bubbleHeight + lineHeight, bubbleX + bubbleW, y + lineHeight, bubbleColor, bubbleColor);

            // Render text
            String prefix = msg.isPlayer ? "\u00A7e\u4F60\u00A7r: " : "\u00A7b" + npcName + "\u00A7r: ";
            String text = prefix + rawText;
            var wrappedLines = font.split(Component.literal(text), msgMaxWidth);
            for (int li = wrappedLines.size() - 1; li >= 0 && y > chatTop; li--) {
                y -= lineHeight;
                if (y >= chatTop - 5) {
                    int textColor = msg.isPlayer ? 0xFFFFFF : 0xE0E0E0;
                    graphics.drawString(font, wrappedLines.get(li), cx - 145, y, textColor);
                }
            }
        }

        // Typewriter effect pending reply
        if (!pendingReply.isEmpty()) {
            String display = "\u00A7b" + npcName + "\u00A7r: " + displayReply;
            y -= lineHeight;
            if (y >= chatTop - 5) {
                graphics.drawString(font, display, cx - 145, y, 0xE0E0E0);
            }
        }

        RenderSystem.disableScissor();

        // Scroll indicator
        if (maxScroll > 0) {
            int scrollBarHeight = Math.max(20, (int) ((float) chatHeight / totalContentHeight * chatHeight));
            int scrollBarY = chatTop + (int) ((float) scrollOffset / maxScroll * (chatHeight - scrollBarHeight));
            graphics.fillGradient(cx + 148, scrollBarY, cx + 150, scrollBarY + scrollBarHeight, 0xFF888888, 0xFF888888);
        }

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
            micButton.setMessage(Component.literal("\u00A7c\uD83D\uDD34"));
            voiceStatusText = "\u00A7c\u5F55\u97F3\u4E2D...";
            voiceStatusTimer = 0;

            VoiceCaptureService.startRecording(wavData -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                micButton.setMessage(Component.literal("\u00A7e\u23F3"));
                voiceStatusText = "\u00A7e\u8BC6\u522B\u4E2D...";

                VoskSttEngine.transcribe(wavData)
                    .thenAccept(text -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
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
                            voiceStatusText = "\u00A77\u672A\u68C0\u6D4B\u5230\u8BED\u97F3";
                            voiceStatusTimer = 40;
                        }
                        micButton.setMessage(Component.literal("\uD83C\uDFA4"));
                    }));
            }));
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
        sentHistory.add(text);
        historyIndex = sentHistory.size();
        inputField.setValue("");
        awaitingReply = true;

        // Send to server
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
            if (typewriterTimer >= 2) { // 2 ticks per character ~ 10 chars/sec
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
        // Up/Down arrow for sent message history
        if (keyCode == 265 && inputField.isFocused() && !sentHistory.isEmpty()) { // Up
            if (historyIndex > 0) {
                historyIndex--;
                inputField.setValue(sentHistory.get(historyIndex));
                inputField.moveCursorToEnd();
            }
            return true;
        }
        if (keyCode == 264 && inputField.isFocused() && !sentHistory.isEmpty()) { // Down
            if (historyIndex < sentHistory.size() - 1) {
                historyIndex++;
                inputField.setValue(sentHistory.get(historyIndex));
                inputField.moveCursorToEnd();
            } else {
                historyIndex = sentHistory.size();
                inputField.setValue("");
            }
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

    private record ChatMessage(String text, boolean isPlayer) {
    }
}
