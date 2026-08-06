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
        uint64_t id;
        uint64_t bodyA;
        uint64_t bodyB;
        Vec3 pivotA;
        Vec3 pivotB;
        Vec3 axisA;
        Vec3 axisB;
        float breakForce;
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
    static RigidBody getRigidBody(uint64_t id);
    static void applyForce(uint64_t id, const Vec3& force);
    static void applyImpulse(uint64_t id, const Vec3& impulse);

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
    static void setJointLimit(uint64_t jointId, float linearLimit, float angularLimit);
    static void breakJointWithForce(uint64_t id, float force);

private:
    static bool s_available;
    static bool s_initialized;
    static uint64_t s_nextId;
};

#endif // PHYSICS_SIMULATION_H
