package transferstation.transferstation_whimsicalideas.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import transferstation.transferstation_whimsicalideas.Ransferstation_whimsicalideas;

@Mod.EventBusSubscriber(modid = Ransferstation_whimsicalideas.MODID, value = Dist.CLIENT)
public class GmodKeyBindings {

    public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
            "key.transferstation_whimsicalideas.gmod_gui",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.transferstation_whimsicalideas"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI_KEY);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (OPEN_GUI_KEY.consumeClick()) {
            mc.setScreen(new GmodModelScreen());
        }
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(GmodKeyBindings.class);
    }
}