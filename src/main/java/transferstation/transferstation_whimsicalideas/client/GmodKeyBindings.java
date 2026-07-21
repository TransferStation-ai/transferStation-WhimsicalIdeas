package transferstation.transferstation_whimsicalideas.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.editor.AnimationEditorScreen;
import transferstation.transferstation_whimsicalideas.client.editor.ModelEditorScreen;
import transferstation.transferstation_whimsicalideas.client.morph.ExpressionWheelScreen;
import transferstation.transferstation_whimsicalideas.client.physics.PhysicsSimulationManager;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, value = Dist.CLIENT)
public class GmodKeyBindings {

    public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
            "key.transferstation_whimsicalideas.gmod_gui",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.transferstation_whimsicalideas"
    );

    public static final KeyMapping EXPRESSION_WHEEL_KEY = new KeyMapping(
            "key.transferstation_whimsicalideas.expression_wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.transferstation_whimsicalideas"
    );

    public static final KeyMapping TOGGLE_PHYSICS_KEY = new KeyMapping(
            "key.transferstation_whimsicalideas.toggle_physics",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.transferstation_whimsicalideas"
    );

    public static final KeyMapping OPEN_MODEL_EDITOR_KEY = new KeyMapping(
            "key.transferstation_whimsicalideas.model_editor",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.transferstation_whimsicalideas"
    );

    public static final KeyMapping OPEN_ANIM_EDITOR_KEY = new KeyMapping(
            "key.transferstation_whimsicalideas.anim_editor",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.transferstation_whimsicalideas"
    );

    private static boolean physicsEnabled = true;

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_GUI_KEY);
        event.register(EXPRESSION_WHEEL_KEY);
        event.register(TOGGLE_PHYSICS_KEY);
        event.register(OPEN_MODEL_EDITOR_KEY);
        event.register(OPEN_ANIM_EDITOR_KEY);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        if (OPEN_GUI_KEY.consumeClick()) {
            mc.setScreen(new GmodModelScreen());
        }

        if (EXPRESSION_WHEEL_KEY.consumeClick()) {
            ExpressionWheelScreen.open();
        }

        if (OPEN_MODEL_EDITOR_KEY.consumeClick()) {
            mc.setScreen(new ModelEditorScreen(mc.screen));
        }

        if (OPEN_ANIM_EDITOR_KEY.consumeClick()) {
            mc.setScreen(new AnimationEditorScreen(mc.screen));
        }

        if (TOGGLE_PHYSICS_KEY.consumeClick()) {
            physicsEnabled = !physicsEnabled;
            PhysicsSimulationManager.setPhysicsEnabled(physicsEnabled);
            mc.player.displayClientMessage(
                Component.translatable(
                    physicsEnabled ? "message.transferstation_whimsicalideas.physics_enabled"
                                   : "message.transferstation_whimsicalideas.physics_disabled"),
                true);
        }
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(GmodKeyBindings.class);
    }

    public static boolean isPhysicsEnabled() {
        return physicsEnabled;
    }
}
