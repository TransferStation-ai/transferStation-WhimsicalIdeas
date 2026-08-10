package transferstation.transferstation_whimsicalideas.client.particle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.BeamParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.DecalParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.LightParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.ModelParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.ParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.RopeParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.SpriteParticleRenderer;
import transferstation.transferstation_whimsicalideas.client.particle.renderer.TrailParticleRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ParticleManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ParticleManager INSTANCE;

    private final Map<String, PcfParticleSystemDef.SystemDefinition> registry = new ConcurrentHashMap<>();
    private final List<ParticleEmitter> activeEmitters = Collections.synchronizedList(new ArrayList<>());

    // PCF file cache (raw bytes)
    private final Map<String, byte[]> pcfCache = new ConcurrentHashMap<>();

    // Renderer registry: maps renderer type to implementation
    private final Map<PcfParticleSystemDef.RendererType, ParticleRenderer> renderers = new HashMap<>();

    // Valve type id -> system name mapping (from particles_manifest.txt), may be null
    private volatile ParticleIdRegistry idRegistry;

    private ParticleManager() {}

    public static ParticleManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ParticleManager();
        }
        return INSTANCE;
    }

    /** Register a particle system definition from parsed PCF data */
    public void registerSystem(PcfParticleSystemDef.SystemDefinition def) {
        if (def.name != null && !def.name.isEmpty()) {
            registry.put(def.name, def);
            LOGGER.info("[ParticleManager] Registered particle system: '{}' ({} inits, {} ops, {} children)",
                def.name, def.initializers.size(), def.operators.size(), def.children.size());
        }
    }

    /** Load a .pcf file from path and register all systems within */
    public void loadPcfFile(Path path) {
        try {
            byte[] data = Files.readAllBytes(path);
            PcfParticleSystemDef systems = PcfParser.parse(data);
            for (var def : systems.systemDefinitions) {
                registerSystem(def);
            }
            pcfCache.put(path.getFileName().toString(), data);
            LOGGER.info("[ParticleManager] Loaded {} particle systems from {}", systems.systemDefinitions.size(), path);
        } catch (Exception e) {
            LOGGER.error("[ParticleManager] Failed to load PCF: {}", path, e);
        }
    }

    /** Load a .pcf from byte array (e.g. from jar resources) */
    public void loadPcfFromBytes(String name, byte[] data) {
        try {
            PcfParticleSystemDef systems = PcfParser.parse(data);
            for (var def : systems.systemDefinitions) {
                registerSystem(def);
            }
            pcfCache.put(name, data);
        } catch (Exception e) {
            LOGGER.error("[ParticleManager] Failed to parse PCF from bytes: {}", name, e);
        }
    }

    /** Spawn a particle effect at a world position */
    public ParticleEmitter spawnEffect(String systemName, Level level, double x, double y, double z) {
        return spawnEffect(systemName, level, x, y, z, null);
    }

    public ParticleEmitter spawnEffect(String systemName, Level level, double x, double y, double z,
                                        Consumer<Particle> onSpawn) {
        var def = registry.get(systemName);
        if (def == null) {
            LOGGER.warn("[ParticleManager] Unknown particle system: '{}'", systemName);
            return null;
        }
        var emitter = new ParticleEmitter(def, level, onSpawn);
        emitter.origin.set((float) x, (float) y, (float) z);
        emitter.active = true;
        activeEmitters.add(emitter);

        // Burst initial particles
        int burstCount = def.continuous ? Math.min((int) def.emissionRate, 20) : def.maxParticles;
        emitter.burst(burstCount);

        return emitter;
    }

    /** Install the Valve type id -> system name registry (built from particles_manifest.txt) */
    public void installIdRegistry(ParticleIdRegistry registry) {
        this.idRegistry = registry;
    }

    /** Current id registry, or null if none installed yet */
    public ParticleIdRegistry getIdRegistry() {
        return idRegistry;
    }

    /** Spawn a particle effect by Valve type id. Falls back to null if id unknown. */
    public ParticleEmitter spawnEffectById(int id, Level level, double x, double y, double z) {
        ParticleIdRegistry reg = this.idRegistry;
        if (reg == null) {
            LOGGER.warn("[ParticleManager] No id registry installed - cannot spawn by id {}", id);
            return null;
        }
        String systemName = reg.systemNameForId(id);
        if (systemName == null) {
            LOGGER.warn("[ParticleManager] Unknown particle id: '{}'", id);
            return null;
        }
        return spawnEffect(systemName, level, x, y, z);
    }

    /** Tick all active emitters (called every client tick) */
    public void tick(float dt) {
        dt = Math.min(dt, 0.05f); // cap to prevent physics explosion

        synchronized (activeEmitters) {
            int globalCount = getTotalParticleCount();
            Iterator<ParticleEmitter> it = activeEmitters.iterator();
            while (it.hasNext()) {
                ParticleEmitter emitter = it.next();
                emitter.tick(dt);

                // Enforce particle caps using local count tracking
                int maxGlobalParticles = 10000;
                if (globalCount > maxGlobalParticles) {
                    emitter.active = false;
                }
                int maxParticlesPerEffect = 2000;
                if (emitter.getParticleCount() > maxParticlesPerEffect) {
                    emitter.active = false;
                }

                // Remove finished emitters (0 particles and inactive)
                if (!emitter.active && emitter.getParticleCount() == 0) {
                    it.remove();
                }
                globalCount = getTotalParticleCount();
            }
        }
    }

    /** Render all particles (called every frame) */
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, float partialTicks) {
        synchronized (activeEmitters) {
            for (var emitter : activeEmitters) {
                var def = emitter.getDefinition();
                if (def.renderer == null) continue;
                var renderer = renderers.get(def.renderer.type);
                if (renderer != null) {
                    renderer.render(poseStack, bufferSource, emitter, emitter.getParticles(), partialTicks, 0xF000F0);
                }
            }
        }
    }

    /** Return the number of active emitters */
    public int getActiveEmitterCount() {
        synchronized (activeEmitters) {
            return activeEmitters.size();
        }
    }

    /** Register a particle renderer for the given renderer type */
    public void registerRenderer(PcfParticleSystemDef.RendererType type, ParticleRenderer renderer) {
        renderers.put(type, renderer);
    }

    private boolean renderersInitialized = false;

    /**
     * 注册全部 8 个 renderer（幂等）。材质解析：优先 valve_content/materials/** 下同名 .png，
     * 否则回退 minecraft:textures/particle/generic_0.png。
     */
    public void initRenderers() {
        if (renderersInitialized) return;
        renderersInitialized = true;

        var sprite = new SpriteParticleRenderer(resolveMaterial("particle/particle.vmt"));
        var trail = new TrailParticleRenderer(resolveMaterial("particle/particle.vmt"));
        var decal = new DecalParticleRenderer(resolveMaterial("particle/particle.vmt"));
        var light = new LightParticleRenderer(resolveMaterial("particle/particle.vmt"));

        registerRenderer(PcfParticleSystemDef.RendererType.SPRITE, sprite);
        registerRenderer(PcfParticleSystemDef.RendererType.TRAIL, trail);
        registerRenderer(PcfParticleSystemDef.RendererType.DECAL, decal);
        registerRenderer(PcfParticleSystemDef.RendererType.LIGHT, light);
        registerRenderer(PcfParticleSystemDef.RendererType.MODEL, new ModelParticleRenderer());
        registerRenderer(PcfParticleSystemDef.RendererType.BEAM, new BeamParticleRenderer());
        registerRenderer(PcfParticleSystemDef.RendererType.ROPE, new RopeParticleRenderer());

        LOGGER.info("[ParticleManager] Initialized {} particle renderers", renderers.size());
    }

    /** 在资源 valve_content/materials/** 下找同名 .png；找不到回退 generic_0 */
    private net.minecraft.resources.ResourceLocation resolveMaterial(String vmtPath) {
        try {
            String base = vmtPath;
            int slash = vmtPath.lastIndexOf('/');
            if (slash >= 0) {
                base = vmtPath.substring(slash + 1);
            }
            if (base.endsWith(".vmt")) {
                base = base.substring(0, base.length() - 4);
            }
            var resourceManager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            var candidates = resourceManager.listResources(
                "valve_content/materials",
                loc -> loc.getPath().endsWith(".png"));
            // 简化：优先精确名匹配，其次任一 .png
            for (var entry : candidates.entrySet()) {
                String path = entry.getKey().getPath();
                if (path.equals("valve_content/materials/" + base + ".png")) {
                    return entry.getKey();
                }
            }
            for (var entry : candidates.entrySet()) {
                String path = entry.getKey().getPath();
                if (path.endsWith(base + ".png")) {
                    return entry.getKey();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[ParticleManager] No custom material for '{}', falling back", vmtPath);
        }
        return net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/particle/generic_0.png");
    }

    /** Return the list of registered system names */
    public List<String> getRegisteredSystemNames() {
        return List.copyOf(registry.keySet());
    }

    public int getTotalParticleCount() {
        int count = 0;
        synchronized (activeEmitters) {
            for (var emitter : activeEmitters) {
                count += emitter.getParticleCount();
            }
        }
        return count;
    }

    public void clearAll() {
        synchronized (activeEmitters) {
            activeEmitters.clear();
        }
    }

    public void onWorldUnload() {
        clearAll();
    }
}
