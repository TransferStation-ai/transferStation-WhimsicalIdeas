package transferstation.transferstation_whimsicalideas;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.GmodModelConfig;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;
import transferstation.transferstation_whimsicalideas.client.model.NpcModelRegistry;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;
import transferstation.transferstation_whimsicalideas.client.renderer.NpcEntityRenderer;
import transferstation.transferstation_whimsicalideas.client.renderer.NpcRagdollRenderer;
import transferstation.transferstation_whimsicalideas.client.voice.VoiceCaptureService;
import transferstation.transferstation_whimsicalideas.client.voice.VoiceConfig;
import transferstation.transferstation_whimsicalideas.client.voice.VoskSttEngine;
import transferstation.transferstation_whimsicalideas.event.FractureHandler;
import transferstation.transferstation_whimsicalideas.event.InjuryEventHandler;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Mod(Transferstation_whimsicalideas.MODID)
public class Transferstation_whimsicalideas {

    public static final String MODID = "transferstation_whimsicalideas";
    private static final Logger LOGGER = LogUtils.getLogger();

    @SuppressWarnings("removal")
    public Transferstation_whimsicalideas() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        NpcModelRegistry.register(modBus);

        // Scan model directory BEFORE registry events fire so dynamic
        // entity types and spawn eggs are registered in time.
        java.nio.file.Path configDir = FMLPaths.CONFIGDIR.get().resolve(MODID);
        NpcModelRegistry.scanAndRegister(configDir);

        // Register built-in Valve NPC entities from the bundled registry JSON.
        // Must happen in the constructor while DeferredRegister is still unfrozen.
        registerBuiltinValveNpcs();

        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, Config.SPEC, MODID + "-common.toml");

        // 注册网络通道
        transferstation.transferstation_whimsicalideas.network.NpcChatNetwork.register();

        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.register(EntityChatHandler.class);
        bus.register(CleanupHandler.class);
        bus.register(FractureHandler.class);
        bus.register(InjuryEventHandler.class);

        modBus.addListener(this::onEntityAttributeCreation);
    }

    /**
     * Registers all built-in Valve NPC entities from the bundled
     * {@code valve_npc_registry.json} asset.
     * <p>
     * Must be called during mod construction while the DeferredRegister is still
     * open. Entity registration must NOT be deferred to FMLClientSetupEvent
     * because Forge freezes the registry after mod construction.
     */
    private static void registerBuiltinValveNpcs() {
        try (var is = Transferstation_whimsicalideas.class.getClassLoader()
                .getResourceAsStream("assets/transferstation_whimsicalideas/valve_npc_registry.json")) {
            if (is == null) {
                LOGGER.debug("[Transferstation] No valve_npc_registry.json found on classpath, skipping");
                return;
            }
            var json = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            var npcs = json.getAsJsonArray("npcs");
            for (var elem : npcs) {
                var npc = elem.getAsJsonObject();
                String id = npc.get("id").getAsString();
                String modelPath = npc.get("modelPath").getAsString();
                float scale = npc.getAsJsonObject("attributes").get("scale").getAsFloat();
                String entityId = "valve_" + id;
                NpcModelRegistry.registerBuiltinNpc(entityId, modelPath, 20f, 0.25f, 0f, scale);
            }
            LOGGER.info("[Transferstation] Registered {} built-in Valve NPCs", npcs.size());
        } catch (Exception e) {
            LOGGER.debug("[Transferstation] No built-in Valve NPCs to register");
        }
    }

    @SuppressWarnings("unchecked")
    private void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        for (var entry : NpcModelRegistry.getRegisteredNpcs().entrySet()) {
            event.put((EntityType<? extends LivingEntity>) entry.getValue().get(), NpcEntity.createAttributes().build());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Mod initializing for TransferStation: WhimsicalIdeas");
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("Client setup complete");
            // Initialize voice input services
            initializeVoiceInput();
            initializeClientComponents();
        }

        private static void initializeVoiceInput() {
            // Load voice config
            VoiceConfig.load();

            // Initialize microphone detection
            VoiceCaptureService.initialize();

            // Check if Vosk model exists and initialize on background thread
            if (VoiceConfig.isModelAvailable()) {
                VoskSttEngine.initialize();
            } else {
                LOGGER.info("[TransferStation] Vosk model not found at {}; voice input disabled until model is downloaded",
                        VoiceConfig.getModelPath());
            }
        }

        /**
         * 注册模型加载进度条 HUD 叠加层。
         * 使用 IGuiOverlay 系统，在所有内置叠加层之上渲染，
         * 显示当前模型加载阶段、进度条和当前处理的文件。
         */
        @SubscribeEvent
        public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll(
                "model_load_progress",
                transferstation.transferstation_whimsicalideas.client.renderer.ModelLoadProgressOverlay.INSTANCE
            );
            event.registerAboveAll(
                "injury_status",
                transferstation.transferstation_whimsicalideas.client.renderer.InjuryHudOverlay.INSTANCE
            );
            LOGGER.debug("[TransferStation] Registered model load progress overlay");
        }

        /**
         * 注册资源重载监听器，在每次资源包重载后重新注册所有自定义生成的 DynamicTexture。
         * Minecraft 的 TextureManager.onResourceManagerReload() 会关闭 DynamicTexture，
         * 释放其 NativeImage，导致后续渲染时 NullPointerException。
         * 此监听器在重载完成后使用缓存的 NativeImage 副本重建所有 DynamicTexture。
         */
        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((stage, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor) -> stage.wait(null).thenRunAsync(() -> {
                // 用缓存的 NativeImage 副本重建所有 DynamicTexture。
                // reRegisterAllTextures() 内部会递增世代计数器，
                // 使所有未在本批重建的条目在下一帧渲染时自动按需重注册，
                // 避免 DynamicTexture 的 NativeImage 为 null 导致 NPE。
                ModelLoadManager.getColorResolver().reRegisterAllTextures();
            }, gameExecutor));
            LOGGER.debug("[TransferStation] Registered resource reload listener for texture re-registration");
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        @SubscribeEvent
        public static void onRegisterRenderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
            for (var entry : NpcModelRegistry.getRegisteredNpcs().entrySet()) {
                net.minecraft.world.entity.EntityType type = entry.getValue().get();
                event.registerEntityRenderer(type, NpcEntityRenderer::new);
            }

            if (NpcModelRegistry.getNpcRagdollType() != null) {
                event.registerEntityRenderer(
                        NpcModelRegistry.getNpcRagdollType(),
                        NpcRagdollRenderer::new
                );
            }
        }

        private static void initializeClientComponents() {
            java.nio.file.Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                    .resolve(Transferstation_whimsicalideas.MODID);

            transferstation.transferstation_whimsicalideas.client.GmodModelConfig.init(configDir);
            transferstation.transferstation_whimsicalideas.client.GmodKeyBindings.register();

            transferstation.transferstation_whimsicalideas.client.physics.PhysicsSimulationManager.initialize();

            transferstation.transferstation_whimsicalideas.client.animation.DefaultAnimationExtractor.extractIfNeeded(configDir);

            transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor.loadDefaultAnimations(configDir);

            transferstation.transferstation_whimsicalideas.client.morph.MorphManager.init(configDir);

            boolean nativeOk = transferstation.transferstation_whimsicalideas.client.model.GmodNativeBridge.tryLoadNative();
            if (nativeOk) {
                LOGGER.info("Native renderer loaded successfully");
            } else {
                LOGGER.info("Native renderer not available, using Java fallback");
            }

            loadBuiltInParticles();
        }

        private static void loadBuiltInParticles() {
            // 从 mod jar 的 valve_content/particles/ 目录加载
            var loc = new net.minecraft.resources.ResourceLocation(
                    Transferstation_whimsicalideas.MODID,
                    "valve_content/particles/builtin.pcf");
            var opt = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(loc);
            if (opt.isPresent()) {
                try (var input = opt.get().open()) {
                    byte[] data = input.readAllBytes();
                    ParticleManager.getInstance().loadPcfFromBytes("builtin", data);
                } catch (Exception e) {
                    // Not all builds have bundled particles
                }
            }
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientForgeEvents {
        @SubscribeEvent
        public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.START) {
                transferstation.transferstation_whimsicalideas.client.physics.PhysicsSimulationManager.tick();
            }
        }

        /**
         * Fired when the client player logs into a world (single player or multiplayer).
         * Restores the persisted player model selection and auto-loads the model.
         */
        @SubscribeEvent
        public static void onClientPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            LOGGER.info("[TransferStation] Client player logged in, restoring persisted player model config");
            GmodModelConfig.loadPersisted();
        }
    }

    public static class CleanupHandler {
        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            // 不要清除模型缓存 — 模型数据和纹理是客户端渲染状态
            // 服务器停止不应破坏客户端渲染资源（单机重新连接后模型/纹理会丢失）
            // ModelLoadManager.clearAllCaches() 会释放所有 DynamicTexture 并清空 entries，
            // 导致 ensureTextureRegistered() 无法重新注册纹理 → FileNotFoundException
            LOGGER.debug("Server stopping (client model cache preserved)");
            // PhysicsSimulationManager.cleanup() 也是客户端资源，不清除
        }
    }
}
