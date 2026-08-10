#ifndef PHYSICS_SIMULATION_H
#define PHYSICS_SIMULATION_H

#include <cstdint>
#include <vector>
#include <memory>

class PhysicsSimulation {
public:
    struct Vec3 {
        float x, y, z;
        Vec3() : x(0), y(0), z(0) {}
        Vec3(float x, float y, float z) : x(x), y(y), z(z) {}
    };

    struct Quat {
        float x, y, z, w;
        Quat() : x(0), y(0), z(0), w(1) {}
        Quat(float x, float y, float z, float w) : x(x), y(y), z(z), w(w) {}
    };

    struct RigidBody {
        uint64_t id;
        Vec3 position;
        Quat rotation;
        Vec3 velocity;
        Vec3 angularVelocity;
        // Inverse inertia tensor, diagonal in the body's LOCAL frame
        // (isotropic for spheres/capsules, per-axis for boxes).
        Vec3 invInertia{0, 0, 0};
        // Per-axis integration factors: 0 locks an axis, 1 = free.
        Vec3 linearFactor{1, 1, 1};
        Vec3 angularFactor{1, 1, 1};
        float mass;
        float restitution;
        float friction;
        bool isStatic;
        bool isKinematic;
        bool isActive;
    };

    struct CollisionShape {
        enum Type { BOX, SPHERE, CAPSULE, MESH };
        Type type;
        Vec3 halfExtents;
        float radius;
        float height;
    };

    struct Joint {
        enum Type { DISTANCE, CONE_TWIST };
        uint64_t id;
        Type type;
        uint64_t bodyA;
        uint64_t bodyB;
        // Anchor points expressed in each body's LOCAL frame.
        Vec3 pivotA;
        Vec3 pivotB;
        // Joint axis (cone apex direction) in each body's LOCAL frame.
        Vec3 axisA;
        Vec3 axisB;
        // Cone-twist limits, in radians. Swing spans are the elliptical
        // cone half-angles around local X (span1) and local Y (span2) of
        // body A; twistSpan is the allowed rotation around the joint axis.
        float swingSpan1;
        float swingSpan2;
        float twistSpan;
        float linearLimit;
        float angularLimit;
        // Force (impulse magnitude) that breaks the joint when exceeded.
        float breakForce;
        // Reset each substep; the cone-twist solver accumulates |lambda| here
        // so a joint that is stressed beyond breakForce snaps.
        float forceAccumulator;
        bool isBroken;
    };

    static bool isAvailable();
    static bool initialize();
    static void shutdown();

    static uint64_t createRigidBody(const Vec3& position, const Quat& rotation, float mass);
    static void destroyRigidBody(uint64_t id);
    static void setRigidBodyPosition(uint64_t id, const Vec3& position);
    static void setRigidBodyRotation(uint64_t id, const Quat& rotation);
    static void setRigidBodyVelocity(uint64_t id, const Vec3& velocity);
    static void setRigidBodyAngularVelocity(uint64_t id, const Vec3& angularVelocity);
    static Vec3 getAngularVelocity(uint64_t id);
    static RigidBody getRigidBody(uint64_t id);
    static void applyForce(uint64_t id, const Vec3& force);
    static void applyImpulse(uint64_t id, const Vec3& impulse);
    static void applyTorque(uint64_t id, const Vec3& torque);
    static void applyAngularImpulse(uint64_t id, const Vec3& impulse);

    static uint64_t createCollisionShape(const CollisionShape& shape);
    static void attachCollisionShape(uint64_t bodyId, uint64_t shapeId);

    static uint64_t createJoint(uint64_t bodyA, uint64_t bodyB,
                                const Vec3& pivotA, const Vec3& pivotB);
    static void destroyJoint(uint64_t id);
    static void breakJoint(uint64_t id);

    static void stepSimulation(float deltaTime);
    static void setGravity(const Vec3& gravity);
    static Vec3 getGravity();

    static bool raycast(const Vec3& from, const Vec3& to, Vec3& hitPoint, Vec3& hitNormal);
    static bool sphereCast(const Vec3& from, const Vec3& to, float radius,
                           Vec3& hitPoint, Vec3& hitNormal);

    // True if the body collided with the ground plane or the environment mesh
    // during the most recent stepSimulation (reset at the start of each step).
    static bool isBodyGrounded(uint64_t id);

    // Environment mesh: static triangle soup that sphere bodies collide against.
    // vertices are interleaved xyz triplets; indices reference them (triangle fan).
    static void setEnvironmentMesh(const Vec3* vertices, int vertexCount,
                                   const int* indices, int indexCount);
    static void clearEnvironmentMesh();
    static bool isEnvironmentMeshValid();

    // Bullet3-style advanced features
    static uint64_t createRigidBodyWithShape(const Vec3& position, const Quat& rotation, float mass,
                                              CollisionShape::Type shapeType, const Vec3& shapeParam1, float shapeParam2);
    static void setRestitution(uint64_t id, float restitution);
    static void setFriction(uint64_t id, float friction);
    static void setLinearFactor(uint64_t id, const Vec3& factor);
    static void setAngularFactor(uint64_t id, const Vec3& factor);
    static void setKinematicPose(uint64_t id, const Vec3& position, const Quat& rotation);

    static uint64_t createConeTwistJoint(uint64_t bodyA, uint64_t bodyB,
                                          const Vec3& pivotA, const Vec3& pivotB,
                                          float swingSpan1, float swingSpan2, float twistSpan);
    // Full cone-twist creation with explicit per-body joint axes.
    static uint64_t createConeTwistJointEx(uint64_t bodyA, uint64_t bodyB,
                                           const Vec3& pivotA, const Vec3& pivotB,
                                           const Vec3& axisA, const Vec3& axisB,
                                           float swingSpan1, float swingSpan2, float twistSpan);
    static void setJointAngularLimits(uint64_t jointId, float swingSpan1, float swingSpan2, float twistSpan);
    static void setJointLimit(uint64_t jointId, float linearLimit, float angularLimit);
    static void breakJointWithForce(uint64_t id, float force);

private:
    static bool s_available;
    static bool s_initialized;
    static uint64_t s_nextId;
};

#endif // PHYSICS_SIMULATION_H
