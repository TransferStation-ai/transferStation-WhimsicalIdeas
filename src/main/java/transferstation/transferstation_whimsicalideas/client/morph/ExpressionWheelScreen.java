package transferstation.transferstation_whimsicalideas.client.morph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class ExpressionWheelScreen extends Screen {

    private static final float RADIUS_OUTER = 90;
    private static final float RADIUS_INNER = 30;
    private static final float SLOT_ANGLE = (float) (Math.PI / 4);

    private final List<String> morphNames;
    private int selectedSlot = -1;
    private String activeMorph = null;

    protected ExpressionWheelScreen() {
        super(Component.translatable("gui.transferstation_whimsicalideas.expression_wheel"));
        this.morphNames = MorphManager.getMorphNames();
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int centerX = width / 2;
        int centerY = height / 2;

        fillRadialBackground(guiGraphics, centerX, centerY);

        drawSlotLabels(guiGraphics, centerX, centerY, mouseX, mouseY);

        if (activeMorph != null) {
            guiGraphics.drawCenteredString(font,
                Component.translatable("gui.transferstation_whimsicalideas.active_morph", activeMorph),
                centerX, (int)(centerY + RADIUS_OUTER + 20), 0xFFFFFF);
        }

        RenderSystem.disableBlend();
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics) {
    }

    private void fillRadialBackground(GuiGraphics guiGraphics, int cx, int cy) {
        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(cx, cy, 0).color(0, 0, 0, 100).endVertex();

        int segments = 32;
        for (int i = 0; i <= segments; i++) {
            float angle = (float) (2 * Math.PI * i / segments - Math.PI / 2);
            float x = cx + (float) Math.cos(angle) * RADIUS_OUTER;
            float y = cy + (float) Math.sin(angle) * RADIUS_OUTER;
            buffer.vertex(x, y, 0).color(0, 0, 0, 60).endVertex();
        }
        tesselator.end();

        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(cx, cy, 0).color(40, 40, 40, 80).endVertex();

        for (int i = 0; i <= segments; i++) {
            float angle = (float) (2 * Math.PI * i / segments - Math.PI / 2);
            float x = cx + (float) Math.cos(angle) * RADIUS_INNER;
            float y = cy + (float) Math.sin(angle) * RADIUS_INNER;
            buffer.vertex(x, y, 0).color(60, 60, 60, 100).endVertex();
        }
        tesselator.end();
    }

    private void drawSlotLabels(GuiGraphics guiGraphics, int cx, int cy, int mouseX, int mouseY) {
        int slotCount = Math.min(morphNames.size(), 8);
        selectedSlot = -1;

        for (int i = 0; i < slotCount; i++) {
            float angle = (float) (i * 2 * Math.PI / slotCount - Math.PI / 2);
            float labelX = cx + (float) Math.cos(angle) * (RADIUS_OUTER + RADIUS_INNER) * 0.5f;
            float labelY = cy + (float) Math.sin(angle) * (RADIUS_OUTER + RADIUS_INNER) * 0.5f;

            boolean hovered = isMouseInSlot(mouseX, mouseY, cx, cy, angle, slotCount);
            int color = hovered ? 0xFFFFAA00 : 0xFFFFFFFF;

            if (hovered) {
                selectedSlot = i;
                guiGraphics.drawCenteredString(font, ">", (int)(labelX - 20), (int)(labelY - 4), 0xFFFFAA00);
            }

            String name = morphNames.get(i);
            String display = name.length() > 10 ? name.substring(0, 10) + "..." : name;
            guiGraphics.drawCenteredString(font, display, (int)labelX, (int)(labelY - 4), color);
        }
    }

    private boolean isMouseInSlot(int mx, int my, int cx, int cy, float angle, int totalSlots) {
        int slot = getSlotAtPosition(mx, my);
        if (slot < 0) return false;
        int expected = Math.floorMod((int) Math.round((angle + Math.PI / 2) / (2 * Math.PI / totalSlots)), totalSlots);
        return slot == expected;
    }

    private int getSlotAtPosition(int mx, int my) {
        int cx = width / 2;
        int cy = height / 2;
        float dx = mx - cx;
        float dy = my - cy;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        if (dist < RADIUS_INNER || dist > RADIUS_OUTER) return -1;

        float mouseAngle = (float) Math.atan2(dy, dx);
        int slotCount = Math.min(morphNames.size(), 8);
        if (slotCount == 0) return -1;

        for (int i = 0; i < slotCount; i++) {
            float slotAngle = (float) (i * 2 * Math.PI / slotCount - Math.PI / 2);
            float diff = mouseAngle - slotAngle;
            while (diff > Math.PI) diff -= 2 * Math.PI;
            while (diff < -Math.PI) diff += 2 * Math.PI;
            if (Math.abs(diff) <= Math.PI / slotCount) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int slot = getSlotAtPosition((int) mouseX, (int) mouseY);
            if (slot >= 0 && slot < morphNames.size()) {
                activeMorph = morphNames.get(slot);
                AnimationProcessor.setCurrentMorph(activeMorph);
                Player player = Minecraft.getInstance().player;
                if (player != null) {
                    player.displayClientMessage(
                        Component.translatable("message.transferstation_whimsicalideas.morph_applied", activeMorph), true);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 || keyCode == 27 || keyCode == 342 || keyCode == 346) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Clear morph on right-click
        if (button == 1) {
            activeMorph = null;
            AnimationProcessor.clearMorph();
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                    Component.translatable("message.transferstation_whimsicalideas.morph_cleared"), true);
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new ExpressionWheelScreen());
    }

    public String getActiveMorph() {
        return activeMorph;
    }
}
