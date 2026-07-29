package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public class PhysicsBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean available = false;
    private static boolean initialized = false;

    public static final int SHAPE_BOX = 0;
    public static final int SHAPE_SPHERE = 1;
    public static final int SHAPE_CAPSULE = 2;
    public static final int SHAPE_MESH = 3;

    public static boolean isAvailable() {
        return available;
    }

    public static boolean tryInitialize() {
        if (initialized) return available;
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

        return available;
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
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void setFriction(long id, float friction) {
        if (!available) return;
        try {
            nativeSetFriction(id, friction);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void setLinearFactor(long id, float fx, float fy, float fz) {
        if (!available) return;
        try {
            nativeSetLinearFactor(id, fx, fy, fz);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void setAngularFactor(long id, float fx, float fy, float fz) {
        if (!available) return;
        try {
            nativeSetAngularFactor(id, fx, fy, fz);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void destroyRigidBody(long id) {
        if (!available) return;
        try {
            nativeDestroyRigidBody(id);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void setVelocity(long id, float vx, float vy, float vz) {
        if (!available) return;
        try {
            nativeSetVelocity(id, vx, vy, vz);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void applyImpulse(long id, float ix, float iy, float iz) {
        if (!available) return;
        try {
            nativeApplyImpulse(id, ix, iy, iz);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void setKinematicPose(long id, float px, float py, float pz, float rx, float ry, float rz, float rw) {
        if (!available) return;
        try {
            nativeSetKinematicPose(id, px, py, pz, rx, ry, rz, rw);
        } catch (UnsatisfiedLinkError e) {}
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
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void setGravity(float gx, float gy, float gz) {
        if (!available) return;
        try {
            nativeSetGravity(gx, gy, gz);
        } catch (UnsatisfiedLinkError e) {}
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

    public static void setJointLimit(long jointId, float linearLimit, float angularLimit) {
        if (!available) return;
        try {
            nativeSetJointLimit(jointId, linearLimit, angularLimit);
        } catch (UnsatisfiedLinkError e) {}
    }

    public static void destroyJoint(long id) {
        if (!available) return;
        try {
            nativeDestroyJoint(id);
        } catch (UnsatisfiedLinkError e) {}
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
    private static native void nativeSetJointLimit(long jointId, float linearLimit, float angularLimit);
    private static native void nativeDestroyJoint(long id);
    private static native boolean nativeRaycast(float fromX, float fromY, float fromZ, float toX, float toY, float toZ,
                                                  float[] hitPointOut, float[] hitNormalOut);
}
