package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;

import java.util.Map;

/**
 * Loads built-in Valve content (particle effects) from the mod jar.
 * NPC entity registration is handled in the mod constructor via
 * to ensure DeferredRegister is not frozen.
 */
@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ValveContentLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ValveContentLoader::loadValveParticles);
    }

    private static void loadValveParticles() {
        try {
            var resourceManager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            // listResources returns Map<ResourceLocation, Resource> for all resources
            // under the given path matching the filter predicate.
            Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                "valve_content/particles",
                loc -> loc.getNamespace().equals(Transferstation_whimsicalideas.MODID)
                    && loc.getPath().endsWith(".pcf"));

            for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
                try (var is = entry.getValue().open()) {
                    byte[] data = is.readAllBytes();
                    ParticleManager.getInstance().loadPcfFromBytes(entry.getKey().toString(), data);
                }
            }

            if (!resources.isEmpty()) {
                LOGGER.info("[ValveContentLoader] Loaded {} built-in particle files", resources.size());
            }
        } catch (Exception e) {
            LOGGER.debug("[ValveContentLoader] No built-in particle files (expected if not bundled)");
        }
    }
}
