package transferstation.transferstation_whimsicalideas.client.physics;

import com.mojang.logging.LogUtils;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.model.PhysicsBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages collision response by applying bounce, friction, and impulse responses
 * when bodies collide. Tracks material assignments per body and fires collision events.
 */
public final class CollisionResponseHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    public interface CollisionListener {
        void onCollision(CollisionEvent event);
    }

    private static final Map<Long, PhysicsMaterial> bodyMaterials = new ConcurrentHashMap<>();
    private static final List<CollisionListener> listeners = new ArrayList<>();
    private static boolean enabled = true;

    private CollisionResponseHandler() {}

    public static void setEnabled(boolean enabled) {
        CollisionResponseHandler.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Assign a material to a rigid body. The material's restitution and friction
     * will be applied to the native body.
     */
    public static void assignMaterial(long bodyId, PhysicsMaterial material) {
        bodyMaterials.put(bodyId, material);
        if (PhysicsBridge.isAvailable()) {
            PhysicsBridge.setRestitution(bodyId, material.getRestitution());
            PhysicsBridge.setFriction(bodyId, material.getFriction());
        }
    }

    /**
     * Get the material assigned to a body. Returns DEFAULT if none assigned.
     */
    public static PhysicsMaterial getMaterial(long bodyId) {
        return bodyMaterials.getOrDefault(bodyId, PhysicsMaterial.DEFAULT);
    }

    /**
     * Remove material tracking for a body.
     */
    public static void removeBody(long bodyId) {
        bodyMaterials.remove(bodyId);
    }

    /**
     * Add a listener that receives collision events.
     */
    public static void addListener(CollisionListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a collision listener.
     */
    public static void removeListener(CollisionListener listener) {
        listeners.remove(listener);
    }

    /**
     * Process a collision between two bodies. Computes the combined material
     * properties, applies impulse response, and fires a collision event.
     */
    public static void processBodyCollision(long bodyA, long bodyB,
                                             Vector3f contactPoint,
                                             Vector3f contactNormal,
                                             float impactSpeed) {
        if (!enabled || !PhysicsBridge.isAvailable()) return;

        PhysicsMaterial matA = getMaterial(bodyA);
        PhysicsMaterial matB = getMaterial(bodyB);
        PhysicsMaterial combined = PhysicsMaterial.blend(matA, matB, 0.5f);

        float restitution = combined.getRestitution();
        float friction = combined.getFriction();

        applyCollisionImpulse(bodyA, bodyB, contactPoint, contactNormal, impactSpeed, restitution, friction);

        CollisionEvent event = new CollisionEvent(
                CollisionEvent.Type.BODY_BODY,
                bodyA, bodyB,
                contactPoint, contactNormal,
                impactSpeed,
                matA, matB
        );
        fireCollisionEvent(event);
    }

    /**
     * Process a collision between a body and the environment (static mesh).
     */
    public static void processEnvironmentCollision(long bodyId,
                                                     Vector3f contactPoint,
                                                     Vector3f contactNormal,
                                                     float impactSpeed) {
        if (!enabled || !PhysicsBridge.isAvailable()) return;

        PhysicsMaterial bodyMat = getMaterial(bodyId);
        PhysicsMaterial envMat = PhysicsMaterial.STONE;
        PhysicsMaterial combined = PhysicsMaterial.blend(bodyMat, envMat, 0.5f);

        float restitution = combined.getRestitution();
        float friction = combined.getFriction();

        applyEnvironmentResponse(bodyId, contactPoint, contactNormal, impactSpeed, restitution, friction);

        CollisionEvent event = new CollisionEvent(
                CollisionEvent.Type.BODY_ENVIRONMENT,
                bodyId, -1,
                contactPoint, contactNormal,
                impactSpeed,
                bodyMat, envMat
        );
        fireCollisionEvent(event);
    }

    /**
     * Apply impulse-based collision response between two dynamic bodies.
     */
    private static void applyCollisionImpulse(long bodyA, long bodyB,
                                               Vector3f contactPoint, Vector3f contactNormal,
                                               float impactSpeed, float restitution, float friction) {
        if (impactSpeed < 0.01f) return;

        float impulseMagnitude = impactSpeed * (1f + restitution);

        Vector3f impulse = new Vector3f(contactNormal).mul(impulseMagnitude);

        PhysicsBridge.applyImpulse(bodyA, impulse.x * 0.5f, impulse.y * 0.5f, impulse.z * 0.5f);
        PhysicsBridge.applyImpulse(bodyB, -impulse.x * 0.5f, -impulse.y * 0.5f, -impulse.z * 0.5f);

        if (friction > 0.01f) {
            float[] velA = getVelocityArray(bodyA);
            float[] velB = getVelocityArray(bodyB);
            float relVx = velA[0] - velB[0];
            float relVy = velA[1] - velB[1];
            float relVz = velA[2] - velB[2];

            float normalDot = relVx * contactNormal.x + relVy * contactNormal.y + relVz * contactNormal.z;

            float tanX = relVx - contactNormal.x * normalDot;
            float tanY = relVy - contactNormal.y * normalDot;
            float tanZ = relVz - contactNormal.z * normalDot;
            float tanLen = (float) Math.sqrt(tanX * tanX + tanY * tanY + tanZ * tanZ);

            if (tanLen > 0.001f) {
                float frictionImpulse = Math.min(friction * impulseMagnitude, tanLen);
                float fx = -tanX / tanLen * frictionImpulse;
                float fy = -tanY / tanLen * frictionImpulse;
                float fz = -tanZ / tanLen * frictionImpulse;

                PhysicsBridge.applyImpulse(bodyA, fx * 0.5f, fy * 0.5f, fz * 0.5f);
                PhysicsBridge.applyImpulse(bodyB, -fx * 0.5f, -fy * 0.5f, -fz * 0.5f);
            }
        }
    }

    /**
     * Apply impulse-based collision response against a static environment surface.
     */
    private static void applyEnvironmentResponse(long bodyId,
                                                    Vector3f contactPoint, Vector3f contactNormal,
                                                    float impactSpeed, float restitution, float friction) {
        if (impactSpeed < 0.01f) return;

        float impulseMagnitude = impactSpeed * (1f + restitution);

        PhysicsBridge.applyImpulse(bodyId,
                contactNormal.x * impulseMagnitude,
                contactNormal.y * impulseMagnitude,
                contactNormal.z * impulseMagnitude);

        if (friction > 0.01f) {
            float[] vel = getVelocityArray(bodyId);
            float normalDot = vel[0] * contactNormal.x + vel[1] * contactNormal.y + vel[2] * contactNormal.z;

            float tanX = vel[0] - contactNormal.x * normalDot;
            float tanY = vel[1] - contactNormal.y * normalDot;
            float tanZ = vel[2] - contactNormal.z * normalDot;
            float tanLen = (float) Math.sqrt(tanX * tanX + tanY * tanY + tanZ * tanZ);

            if (tanLen > 0.001f) {
                float frictionImpulse = Math.min(friction * impulseMagnitude, tanLen);
                PhysicsBridge.applyImpulse(bodyId,
                        -tanX / tanLen * frictionImpulse,
                        -tanY / tanLen * frictionImpulse,
                        -tanZ / tanLen * frictionImpulse);
            }
        }
    }

    private static float[] getVelocityArray(long bodyId) {
        if (!PhysicsBridge.isAvailable()) return new float[]{0, 0, 0};
        return PhysicsBridge.getVelocity(bodyId);
    }

    private static void fireCollisionEvent(CollisionEvent event) {
        for (CollisionListener listener : listeners) {
            try {
                listener.onCollision(event);
            } catch (Exception e) {
                LOGGER.warn("[Physics] Collision listener threw exception: {}", e.getMessage());
            }
        }
    }

    public static void clear() {
        bodyMaterials.clear();
        listeners.clear();
    }
}
