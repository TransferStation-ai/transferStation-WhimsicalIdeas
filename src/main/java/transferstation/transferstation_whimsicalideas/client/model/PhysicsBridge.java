package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.physics.CollisionResponseHandler;
import transferstation.transferstation_whimsicalideas.client.physics.PhysicsMaterial;
import transferstation.transferstation_whimsicalideas.client.physics.SpatialHashGrid;
import transferstation.transferstation_whimsicalideas.client.physics.TriggerVolume;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PhysicsBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean available = false;
    private static boolean initialized = false;

    public static final int SHAPE_BOX = 0;
    public static final int SHAPE_SPHERE = 1;
    public static final int SHAPE_CAPSULE = 2;
    public static final int SHAPE_MESH = 3;

    private static final SpatialHashGrid spatialGrid = new SpatialHashGrid(4.0f);
    private static final Map<Long, String> bodyTags = new HashMap<>();
    private static final Map<Long, float[]> bodyShapeParams = new HashMap<>();
    private static final Map<Long, Integer> bodyShapeTypes = new HashMap<>();
    private static final List<TriggerVolume> triggerVolumes = new ArrayList<>();

    public static boolean isAvailable() {
        return available;
    }

    public static void tryInitialize() {
        if (initialized) return;
        initialized = true;

        try {
            if (GmodNativeBridge.isAvailable()) {
                available = nativePhysicsInitialize();
                if (available) {
                    LOGGER.info("[Physics] Physics simulation initialized");
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("[Physics] Native physics not available: {}", e.getMessage());
        }

    }

    /**
     * Retry native physics init once the renderer has been initialized on the
     * render thread (GL context present). No-op if already available.
     */
    public static void tryInitializeLater() {
        if (available) return;
        initialized = false;
        tryInitialize();
    }

    public static SpatialHashGrid getSpatialGrid() {
        return spatialGrid;
    }

    public static void registerTriggerVolume(TriggerVolume volume) {
        triggerVolumes.add(volume);
    }

    public static void unregisterTriggerVolume(TriggerVolume volume) {
        triggerVolumes.remove(volume);
    }

    public static List<TriggerVolume> getTriggerVolumes() {
        return List.copyOf(triggerVolumes);
    }

    public static void setBodyTag(long bodyId, String tag) {
        bodyTags.put(bodyId, tag);
    }

    public static String getBodyTag(long bodyId) {
        return bodyTags.getOrDefault(bodyId, "");
    }

    public static void removeBodyTag(long bodyId) {
        bodyTags.remove(bodyId);
    }

    /**
     * Update the spatial grid entry for a body based on its shape.
     */
    public static void updateSpatialEntry(long bodyId, int shapeType, float[] shapeParams) {
        if (!available) return;
        float[] pos = getPosition(bodyId);
        if (pos == null) return;

        float px = pos[0], py = pos[1], pz = pos[2];
        float hx = 0.5f, hy = 0.5f, hz = 0.5f;

        switch (shapeType) {
            case SHAPE_BOX:
                if (shapeParams.length >= 3) {
                    hx = shapeParams[0] * 0.5f;
                    hy = shapeParams[1] * 0.5f;
                    hz = shapeParams[2] * 0.5f;
                }
                break;
            case SHAPE_SPHERE:
                if (shapeParams.length >= 1) {
                    hx = hy = hz = shapeParams[0];
                }
                break;
            case SHAPE_CAPSULE:
                if (shapeParams.length >= 2) {
                    hx = hz = shapeParams[0];
                    hy = shapeParams[1] * 0.5f + shapeParams[0];
                }
                break;
        }

        spatialGrid.insert(bodyId, px - hx, py - hy, pz - hz, px + hx, py + hy, pz + hz);
        bodyShapeTypes.put(bodyId, shapeType);
        bodyShapeParams.put(bodyId, shapeParams);
    }

    /**
     * Update all spatial grid entries for tracked bodies.
     */
    public static void updateAllSpatialEntries() {
        if (!available) return;
        for (Long bodyId : bodyTags.keySet()) {
            Integer shapeType = bodyShapeTypes.get(bodyId);
            float[] shapeParam = bodyShapeParams.get(bodyId);
            if (shapeType != null && shapeParam != null) {
                updateSpatialEntry(bodyId, shapeType, shapeParam);
            }
        }
    }

    /**
     * Update trigger volumes, checking which bodies are inside.
     */
    public static void updateTriggerVolumes() {
        if (!available) return;
        for (TriggerVolume vol : triggerVolumes) {
            for (Long bodyId : bodyTags.keySet()) {
                float[] pos = getPosition(bodyId);
                if (pos != null) {
                    vol.updateBody(bodyId, new Vector3f(pos[0], pos[1], pos[2]));
                }
            }
        }
    }

    /**
     * Query potential collision pairs using the spatial grid.
     */
    public static List<long[]> findPotentialCollisions() {
        return spatialGrid.findPotentialPairs();
    }

    /**
     * Apply material properties to a rigid body.
     */
    public static void applyMaterial(long bodyId, PhysicsMaterial material) {
        CollisionResponseHandler.assignMaterial(bodyId, material);
    }

    public static long createRigidBody(float x, float y, float z, float mass) {
        if (!available) return -1;
        try {
            return nativeCreateRigidBody(x, y, z, mass);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    public static long createRigidBodyWithShape(float x, float y, float z, float mass, int shapeType, float[] shapeParams) {
        if (!available) return -1;
        try {
            return nativeCreateRigidBodyWithShape(x, y, z, mass, shapeType, shapeParams);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    public static void setRestitution(long id, float restitution) {
        if (!available) return;
        try {
            nativeSetRestitution(id, restitution);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setFriction(long id, float friction) {
        if (!available) return;
        try {
            nativeSetFriction(id, friction);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setLinearFactor(long id, float fx, float fy, float fz) {
        if (!available) return;
        try {
            nativeSetLinearFactor(id, fx, fy, fz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setAngularFactor(long id, float fx, float fy, float fz) {
        if (!available) return;
        try {
            nativeSetAngularFactor(id, fx, fy, fz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void destroyRigidBody(long id) {
        if (!available) return;
        try {
            nativeDestroyRigidBody(id);
            spatialGrid.remove(id);
            bodyTags.remove(id);
            bodyShapeTypes.remove(id);
            bodyShapeParams.remove(id);
            CollisionResponseHandler.removeBody(id);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static float[] getVelocity(long id) {
        if (!available) return new float[]{0, 0, 0};
        try {
            return nativeGetVelocity(id);
        } catch (UnsatisfiedLinkError e) {
            return new float[]{0, 0, 0};
        }
    }

    public static float getMass(long id) {
        if (!available) return 0f;
        try {
            return nativeGetMass(id);
        } catch (UnsatisfiedLinkError e) {
            return 0f;
        }
    }

    public static void setMass(long id, float mass) {
        if (!available) return;
        try {
            nativeSetMass(id, mass);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setDamping(long id, float linearDamping, float angularDamping) {
        if (!available) return;
        try {
            nativeSetDamping(id, linearDamping, angularDamping);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setActivationState(long id, boolean active) {
        if (!available) return;
        try {
            nativeSetActivationState(id, active);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Perform multiple raycasts and return all hits.
     */
    public static List<float[]> raycastAll(float fromX, float fromY, float fromZ,
                                            float toX, float toY, float toZ) {
        List<float[]> hits = new ArrayList<>();
        float[] hitPoint = new float[3];
        float[] hitNormal = new float[3];

        float dx = toX - fromX;
        float dy = toY - fromY;
        float dz = toZ - fromZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001f) return hits;

        float[] origin = {fromX, fromY, fromZ};
        float maxDist = dist;

        while (maxDist > 0.001f) {
            float nx = fromX + dx * (1f - maxDist / dist);
            float ny = fromY + dy * (1f - maxDist / dist);
            float nz = fromZ + dz * (1f - maxDist / dist);

            boolean hit = raycast(nx, ny, nz, toX, toY, toZ, hitPoint, hitNormal);
            if (hit) {
                hits.add(new float[]{hitPoint[0], hitPoint[1], hitPoint[2],
                        hitNormal[0], hitNormal[1], hitNormal[2]});

                float hx = hitPoint[0] - nx;
                float hy = hitPoint[1] - ny;
                float hz = hitPoint[2] - nz;
                float hitDist = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                float remaining = dist - hitDist - 0.01f;
                if (remaining <= 0) break;
                maxDist = remaining;
                fromX = hitPoint[0] + hitNormal[0] * 0.01f;
                fromY = hitPoint[1] + hitNormal[1] * 0.01f;
                fromZ = hitPoint[2] + hitNormal[2] * 0.01f;
            } else {
                break;
            }
        }
        return hits;
    }

    public static void setVelocity(long id, float vx, float vy, float vz) {
        if (!available) return;
        try {
            nativeSetVelocity(id, vx, vy, vz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void applyImpulse(long id, float ix, float iy, float iz) {
        if (!available) return;
        try {
            nativeApplyImpulse(id, ix, iy, iz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setAngularVelocity(long id, float wx, float wy, float wz) {
        if (!available) return;
        try {
            nativeSetAngularVelocity(id, wx, wy, wz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static float[] getAngularVelocity(long id) {
        if (!available) return new float[]{0, 0, 0};
        try {
            return nativeGetAngularVelocity(id);
        } catch (UnsatisfiedLinkError e) {
            return new float[]{0, 0, 0};
        }
    }

    public static void applyTorque(long id, float tx, float ty, float tz) {
        if (!available) return;
        try {
            nativeApplyTorque(id, tx, ty, tz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setKinematicPose(long id, float px, float py, float pz, float rx, float ry, float rz, float rw) {
        if (!available) return;
        try {
            nativeSetKinematicPose(id, px, py, pz, rx, ry, rz, rw);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static float[] getPosition(long id) {
        if (!available) return new float[]{0, 0, 0};
        try {
            return nativeGetPosition(id);
        } catch (UnsatisfiedLinkError e) {
            return new float[]{0, 0, 0};
        }
    }

    public static float[] getRotation(long id) {
        if (!available) return new float[]{0, 0, 0, 1};
        try {
            return nativeGetRotation(id);
        } catch (UnsatisfiedLinkError e) {
            return new float[]{0, 0, 0, 1};
        }
    }

    public static void stepSimulation(float deltaTime) {
        if (!available) return;
        try {
            nativeStepSimulation(deltaTime);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isBodyGrounded(long id) {
        if (!available) return false;
        try {
            return nativeIsBodyGrounded(id);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    public static void setGravity(float gx, float gy, float gz) {
        if (!available) return;
        try {
            nativeSetGravity(gx, gy, gz);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static long createJoint(long bodyA, long bodyB, float pax, float pay, float paz, float pbx, float pby, float pbz) {
        if (!available) return -1;
        try {
            return nativeCreateJoint(bodyA, bodyB, pax, pay, paz, pbx, pby, pbz);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    public static long createConeTwistJoint(long bodyA, long bodyB, float pax, float pay, float paz, float pbx, float pby, float pbz,
                                             float swingSpan1, float swingSpan2, float twistSpan) {
        if (!available) return -1;
        try {
            return nativeCreateConeTwistJoint(bodyA, bodyB, pax, pay, paz, pbx, pby, pbz, swingSpan1, swingSpan2, twistSpan);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    /**
     * Cone-twist joint with explicit per-body joint axes (in each body's local
     * frame). The joint axis defines the cone apex direction for the swing limit;
     * twist is measured around it.
     */
    public static long createConeTwistJointEx(long bodyA, long bodyB,
                                               float pax, float pay, float paz,
                                               float pbx, float pby, float pbz,
                                               float axA, float ayA, float azA,
                                               float axB, float ayB, float azB,
                                               float swingSpan1, float swingSpan2, float twistSpan) {
        if (!available) return -1;
        try {
            return nativeCreateConeTwistJointEx(bodyA, bodyB, pax, pay, paz, pbx, pby, pbz,
                    axA, ayA, azA, axB, ayB, azB, swingSpan1, swingSpan2, twistSpan);
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    public static void setJointAngularLimits(long jointId, float swingSpan1, float swingSpan2, float twistSpan) {
        if (!available) return;
        try {
            nativeSetJointAngularLimits(jointId, swingSpan1, swingSpan2, twistSpan);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void setJointLimit(long jointId, float linearLimit, float angularLimit) {
        if (!available) return;
        try {
            nativeSetJointLimit(jointId, linearLimit, angularLimit);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static void destroyJoint(long id) {
        if (!available) return;
        try {
            nativeDestroyJoint(id);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean raycast(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                                   float[] hitPointOut, float[] hitNormalOut) {
        if (!available) return false;
        try {
            return nativeRaycast(fromX, fromY, fromZ, toX, toY, toZ, hitPointOut, hitNormalOut);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** Replace the static environment mesh (triangle soup) that sphere bodies collide against. */
    public static void setEnvironmentMesh(float[] vertices, int[] indices) {
        if (!available) return;
        try {
            nativeSetEnvironmentMesh(vertices, indices);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("[Physics] Native environment mesh unavailable: {}", e.getMessage());
        }
    }

    /** Clear the environment mesh so sphere bodies only fall to the flat ground plane. */
    public static void clearEnvironmentMesh() {
        if (!available) return;
        try {
            nativeSetEnvironmentMesh(null, null);
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("[Physics] Native environment mesh unavailable: {}", e.getMessage());
        }
    }

    private static native boolean nativePhysicsInitialize();
    private static native long nativeCreateRigidBody(float x, float y, float z, float mass);
    private static native long nativeCreateRigidBodyWithShape(float x, float y, float z, float mass, int shapeType, float[] shapeParams);
    private static native void nativeSetRestitution(long id, float restitution);
    private static native void nativeSetFriction(long id, float friction);
    private static native void nativeSetLinearFactor(long id, float fx, float fy, float fz);
    private static native void nativeSetAngularFactor(long id, float fx, float fy, float fz);
    private static native void nativeDestroyRigidBody(long id);
    private static native void nativeSetVelocity(long id, float vx, float vy, float vz);
    private static native void nativeApplyImpulse(long id, float ix, float iy, float iz);
    private static native void nativeSetKinematicPose(long id, float px, float py, float pz, float rx, float ry, float rz, float rw);
    private static native float[] nativeGetPosition(long id);
    private static native float[] nativeGetRotation(long id);
    private static native void nativeStepSimulation(float deltaTime);
    private static native void nativeSetGravity(float gx, float gy, float gz);
    private static native long nativeCreateJoint(long bodyA, long bodyB, float pax, float pay, float paz, float pbx, float pby, float pbz);
    private static native long nativeCreateConeTwistJoint(long bodyA, long bodyB, float pax, float pay, float paz, float pbx, float pby, float pbz,
                                                           float swingSpan1, float swingSpan2, float twistSpan);
    private static native long nativeCreateConeTwistJointEx(long bodyA, long bodyB, float pax, float pay, float paz, float pbx, float pby, float pbz,
                                                            float axA, float ayA, float azA, float axB, float ayB, float azB,
                                                            float swingSpan1, float swingSpan2, float twistSpan);
    private static native void nativeSetJointAngularLimits(long jointId, float swingSpan1, float swingSpan2, float twistSpan);
    private static native void nativeSetAngularVelocity(long id, float wx, float wy, float wz);
    private static native float[] nativeGetAngularVelocity(long id);
    private static native void nativeApplyTorque(long id, float tx, float ty, float tz);
    private static native void nativeSetJointLimit(long jointId, float linearLimit, float angularLimit);
    private static native void nativeDestroyJoint(long id);
    private static native boolean nativeRaycast(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                                                   float[] hitPointOut, float[] hitNormalOut);
    private static native void nativeSetEnvironmentMesh(float[] vertices, int[] indices);
    private static native float[] nativeGetVelocity(long id);
    private static native float nativeGetMass(long id);
    private static native void nativeSetMass(long id, float mass);
    private static native void nativeSetDamping(long id, float linearDamping, float angularDamping);
    private static native void nativeSetActivationState(long id, boolean active);
    private static native boolean nativeIsBodyGrounded(long id);
}
