package transferstation.transferstation_whimsicalideas.client.particle.integration;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, value = Dist.CLIENT)
public class ParticleClientHandler {

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ParticleManager.getInstance().initRenderers(); // 幂等
        if (Minecraft.getInstance().level == null) {
            ParticleManager.getInstance().onWorldUnload();
            return;
        }
        ParticleManager.getInstance().tick(0.05f); // ~20fps tick
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        ParticleManager.getInstance().render(
            event.getPoseStack(),
            Minecraft.getInstance().renderBuffers().bufferSource(),
            event.getPartialTick()
        );
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ParticleManager.getInstance().onWorldUnload();
    }
}
