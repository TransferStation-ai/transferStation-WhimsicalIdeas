package transferstation.transferstation_whimsicalideas.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.common.InjurySystem;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class InjuryHudOverlay {

    public static final IGuiOverlay INSTANCE = (ForgeGui gui, GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) -> {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;

        int y = screenHeight / 2 + 20;
        int x = screenWidth / 2 + 10;

        var player = mc.player;
        int line = 0;

        if (InjurySystem.hasFracture(player)) {
            graphics.drawString(mc.font, Component.translatable("hud.transferstation_whimsicalideas.fracture"), x, y + line * 10, 0xFF4444);
            line++;
        }
        if (InjurySystem.isBleeding(player)) {
            graphics.drawString(mc.font, Component.translatable("hud.transferstation_whimsicalideas.bleeding"), x, y + line * 10, 0xFF0000);
            line++;
        }
        if (InjurySystem.hasEmbeddedArrow(player)) {
            graphics.drawString(mc.font, Component.translatable("hud.transferstation_whimsicalideas.arrow_embedded"), x, y + line * 10, 0xFFAA00);
            line++;
        }
    };
}
