package transferstation.transferstation_whimsicalideas.client.physics;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.model.PhysicsBridge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PhysicsSimulationManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, SoftBodySimulation> activeSimulations = new ConcurrentHashMap<>();

    private static boolean initialized = false;
    private static boolean physicsEnabled = true;

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        PhysicsBridge.tryInitialize();
        LOGGER.info("[PhysicsSimulationManager] Physics simulation manager initialized");
    }

    public static String registerSimulation(SoftBodySimulation simulation) {
        String id = UUID.randomUUID().toString();
        activeSimulations.put(id, simulation);
        return id;
    }

    public static void unregisterSimulation(String id) {
        SoftBodySimulation sim = activeSimulations.remove(id);
        if (sim != null) {
            sim.cleanup();
        }
    }

    public static SoftBodySimulation getSimulation(String id) {
        return activeSimulations.get(id);
    }

    public static boolean isPhysicsEnabled() {
        return physicsEnabled;
    }

    public static void setPhysicsEnabled(boolean enabled) {
        physicsEnabled = enabled;
        LOGGER.info("[PhysicsSimulationManager] Physics simulation {}", enabled ? "enabled" : "disabled");
        for (SoftBodySimulation sim : activeSimulations.values()) {
            sim.setEnabled(enabled);
        }
    }

    public static void tick() {
        if (!initialized) return;
        if (!physicsEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        float deltaTime = 1.0f / 20.0f;

        if (PhysicsBridge.isAvailable()) {
            PhysicsBridge.stepSimulation(deltaTime);
        }

        for (SoftBodySimulation sim : activeSimulations.values()) {
            if (sim.isEnabled()) {
                sim.stepSimulation(deltaTime);
            }
        }
    }

    public static void cleanup() {
        for (SoftBodySimulation sim : activeSimulations.values()) {
            sim.cleanup();
        }
        activeSimulations.clear();
        initialized = false;
        LOGGER.info("[PhysicsSimulationManager] Cleaned up all physics simulations");
    }

    public static void setGravity(Vector3f gravity) {
        if (PhysicsBridge.isAvailable()) {
            PhysicsBridge.setGravity(gravity.x, gravity.y, gravity.z);
        }
        for (SoftBodySimulation sim : activeSimulations.values()) {
            sim.setGravity(gravity);
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static int getActiveSimulationCount() {
        return activeSimulations.size();
    }
}
