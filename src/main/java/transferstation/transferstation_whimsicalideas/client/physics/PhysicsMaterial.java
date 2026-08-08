package transferstation.transferstation_whimsicalideas.client.physics;

import java.util.HashMap;
import java.util.Map;

/**
 * Defines physical material properties for collision response.
 * Different materials have different restitution (bounciness), friction,
 * and density values that affect how rigid bodies interact.
 */
public final class PhysicsMaterial {

    private static final Map<String, PhysicsMaterial> REGISTRY = new HashMap<>();

    public static final PhysicsMaterial DEFAULT = register("default",
            new PhysicsMaterial("default", 0.3f, 0.5f, 1.0f));
    public static final PhysicsMaterial METAL = register("metal",
            new PhysicsMaterial("metal", 0.2f, 0.6f, 7.8f));
    public static final PhysicsMaterial WOOD = register("wood",
            new PhysicsMaterial("wood", 0.3f, 0.7f, 0.6f));
    public static final PhysicsMaterial STONE = register("stone",
            new PhysicsMaterial("stone", 0.2f, 0.8f, 2.5f));
    public static final PhysicsMaterial RUBBER = register("rubber",
            new PhysicsMaterial("rubber", 0.9f, 0.9f, 1.1f));
    public static final PhysicsMaterial ICE = register("ice",
            new PhysicsMaterial("ice", 0.1f, 0.05f, 0.9f));
    public static final PhysicsMaterial FLESH = register("flesh",
            new PhysicsMaterial("flesh", 0.4f, 0.6f, 1.0f));
    public static final PhysicsMaterial CLOTH = register("cloth",
            new PhysicsMaterial("cloth", 0.2f, 0.8f, 0.3f));
    public static final PhysicsMaterial GLASS = register("glass",
            new PhysicsMaterial("glass", 0.5f, 0.2f, 2.5f));
    public static final PhysicsMaterial SAND = register("sand",
            new PhysicsMaterial("sand", 0.1f, 0.9f, 1.6f));

    private final String name;
    private final float restitution;
    private final float friction;
    private final float density;

    public PhysicsMaterial(String name, float restitution, float friction, float density) {
        this.name = name;
        this.restitution = clamp(restitution, 0f, 1f);
        this.friction = clamp(friction, 0f, 1f);
        this.density = Math.max(0.01f, density);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    public String getName() { return name; }
    public float getRestitution() { return restitution; }
    public float getFriction() { return friction; }
    public float getDensity() { return density; }

    /**
     * Blends two materials by a factor t in [0,1].
     * Result = A*(1-t) + B*t for each property.
     */
    public static PhysicsMaterial blend(PhysicsMaterial a, PhysicsMaterial b, float t) {
        t = clamp(t, 0f, 1f);
        float inv = 1f - t;
        return new PhysicsMaterial(
                a.name + "+" + b.name,
                a.restitution * inv + b.restitution * t,
                a.friction * inv + b.friction * t,
                a.density * inv + b.density * t
        );
    }

    public static PhysicsMaterial register(String name, PhysicsMaterial material) {
        REGISTRY.put(name, material);
        return material;
    }

    public static PhysicsMaterial get(String name) {
        return REGISTRY.getOrDefault(name, DEFAULT);
    }

    public static Map<String, PhysicsMaterial> getAll() {
        return Map.copyOf(REGISTRY);
    }

    @Override
    public String toString() {
        return "PhysicsMaterial{name='" + name + "', rest=" + restitution
                + ", friction=" + friction + ", density=" + density + "}";
    }
}
