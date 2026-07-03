package transferstation.transferstation_whimsicalideas;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

@Mod(Transferstation_whimsicalideas.MODID)
public class Transferstation_whimsicalideas {

    public static final String MODID = "transferstation_whimsicalideas";
    private static final Logger LOGGER = LogUtils.getLogger();

    public Transferstation_whimsicalideas() {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(EntityChatHandler.class);
        MinecraftForge.EVENT_BUS.register(ClientCacheCleanup.class);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

            java.nio.file.Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve(Transferstation_whimsicalideas.MODID);
            transferstation.transferstation_whimsicalideas.client.GmodModelConfig.init(configDir);
            transferstation.transferstation_whimsicalideas.client.GmodKeyBindings.register();

            // Initialize native renderer (try to load DLL)
            boolean nativeOk = transferstation.transferstation_whimsicalideas.client.model.GmodNativeBridge.tryLoadNative();
            if (nativeOk) {
                LOGGER.info("Native renderer loaded successfully");
            } else {
                LOGGER.info("Native renderer not available, using Java fallback");
            }
        }
    }

    public static class ClientCacheCleanup {
        @SubscribeEvent
        public void onServerStopping(ServerStoppingEvent event) {
            LOGGER.info("Minecraft server stopping — clearing model cache");
            transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager.clearAllCaches();
        }
    }
}
