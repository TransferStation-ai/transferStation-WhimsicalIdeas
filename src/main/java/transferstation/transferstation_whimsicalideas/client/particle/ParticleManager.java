package transferstation.transferstation_whimsicalideas.client.particle;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

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
    private int maxGlobalParticles = 10000;
    private int maxParticlesPerEffect = 2000;

    // PCF file cache (raw bytes)
    private final Map<String, byte[]> pcfCache = new ConcurrentHashMap<>();

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
                if (globalCount > maxGlobalParticles) {
                    emitter.active = false;
                }
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
                // Delegate rendering to the appropriate renderer based on emitter definition
                var def = emitter.getDefinition();
                if (def.renderer == null) continue;
                // Renderers will be selected and dispatched here
            }
        }
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
