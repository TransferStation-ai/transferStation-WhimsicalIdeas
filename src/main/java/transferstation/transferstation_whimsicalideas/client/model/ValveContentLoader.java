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
import transferstation.transferstation_whimsicalideas.client.particle.ParticleIdRegistry;
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

            // 构建 Valve type id -> system name 映射（失败仅日志，不阻断）
            buildIdRegistry(resourceManager);
        } catch (Exception e) {
            LOGGER.debug("[ValveContentLoader] No built-in particle files (expected if not bundled)");
        }
    }

    /** 用 ResourceManager 构建 ParticleIdRegistry 并安装到 ParticleManager（失败仅日志） */
    private static void buildIdRegistry(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        try {
            var registry = new ParticleIdRegistry(key -> {
                try {
                    var loc = ResourceLocation.fromNamespaceAndPath(
                        Transferstation_whimsicalideas.MODID, "valve_content/particles/" + key);
                    var opt = resourceManager.getResource(loc);
                    if (opt.isEmpty()) return null;
                    try (var is = opt.get().open()) {
                        return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[ValveContentLoader] Cannot read resource for '{}'", key);
                    return null;
                }
            });
            registry.build();
            ParticleManager.getInstance().installIdRegistry(registry);
        } catch (Exception e) {
            LOGGER.error("[ValveContentLoader] Failed to build particle id registry", e);
        }
    }
}
