#include "physics_simulation.h"
#include <iostream>
#include <unordered_map>
#include <cmath>
#include <algorithm>
#include <limits>

using Vec3 = PhysicsSimulation::Vec3;

static const float COLLISION_EPSILON = 0.001f;
static const float PENETRATION_SLOP = 0.01f;
static const float GROUND_PLANE_Y = 0.0f;

// ── Environment mesh (static triangle soup) ─────────────────────────
struct MeshTriangle {
    PhysicsSimulation::Vec3 a, b, c;
    PhysicsSimulation::Vec3 normal;
};

static std::vector<MeshTriangle> s_envTriangles;
static bool s_envMeshValid = false;

bool PhysicsSimulation::s_available = false;
bool PhysicsSimulation::s_initialized = false;
uint64_t PhysicsSimulation::s_nextId = 1;

static std::unordered_map<uint64_t, PhysicsSimulation::RigidBody> s_rigidBodies;
static std::unordered_map<uint64_t, PhysicsSimulation::CollisionShape> s_collisionShapes;
static std::unordered_map<uint64_t, PhysicsSimulation::Joint> s_joints;
static std::unordered_map<uint64_t, uint64_t> s_bodyShapeMap;
static std::unordered_map<uint64_t, bool> s_bodyContactMap;
static PhysicsSimulation::Vec3 s_gravity(0, -9.81f, 0);

static float vec3Dot(const PhysicsSimulation::Vec3& a, const PhysicsSimulation::Vec3& b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

static PhysicsSimulation::Vec3 vec3Cross(const PhysicsSimulation::Vec3& a, const PhysicsSimulation::Vec3& b) {
    return PhysicsSimulation::Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x
    );
}

static PhysicsSimulation::Vec3 vec3Sub(const PhysicsSimulation::Vec3& a, const PhysicsSimulation::Vec3& b) {
    return PhysicsSimulation::Vec3(a.x - b.x, a.y - b.y, a.z - b.z);
}

static PhysicsSimulation::Vec3 vec3Add(const PhysicsSimulation::Vec3& a, const PhysicsSimulation::Vec3& b) {
    return PhysicsSimulation::Vec3(a.x + b.x, a.y + b.y, a.z + b.z);
}

static PhysicsSimulation::Vec3 vec3Scale(const PhysicsSimulation::Vec3& a, float s) {
    return PhysicsSimulation::Vec3(a.x * s, a.y * s, a.z * s);
}

// Per-axis multiply (used for linear/angular factors).
static PhysicsSimulation::Vec3 vec3Mul(const PhysicsSimulation::Vec3& a, const PhysicsSimulation::Vec3& b) {
    return PhysicsSimulation::Vec3(a.x * b.x, a.y * b.y, a.z * b.z);
}

static float vec3Length(const PhysicsSimulation::Vec3& a) {
    return std::sqrt(a.x * a.x + a.y * a.y + a.z * a.z);
}

static float vec3LengthSq(const PhysicsSimulation::Vec3& a) {
    return a.x * a.x + a.y * a.y + a.z * a.z;
}

static PhysicsSimulation::Vec3 vec3Normalize(const PhysicsSimulation::Vec3& a) {
    float len = vec3Length(a);
    if (len < 0.0001f) return PhysicsSimulation::Vec3(0, 0, 0);
    return vec3Scale(a, 1.0f / len);
}

static PhysicsSimulation::Vec3 vec3Negate(const PhysicsSimulation::Vec3& a) {
    return PhysicsSimulation::Vec3(-a.x, -a.y, -a.z);
}

static float vec3Distance(const PhysicsSimulation::Vec3& a, const PhysicsSimulation::Vec3& b) {
    return vec3Length(vec3Sub(a, b));
}

static float clamp(float v, float mn, float mx) {
    return std::max(mn, std::min(mx, v));
}

// ── Quaternion helpers ──────────────────────────────────────────────
using Quat = PhysicsSimulation::Quat;

static Quat quatNormalize(const Quat& q) {
    float n = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w;
    if (n < 1e-8f) return Quat(0, 0, 0, 1);
    float inv = 1.0f / std::sqrt(n);
    return Quat(q.x * inv, q.y * inv, q.z * inv, q.w * inv);
}

static Quat quatConjugate(const Quat& q) {
    return Quat(-q.x, -q.y, -q.z, q.w);
}

static Quat quatMul(const Quat& a, const Quat& b) {
    return Quat(
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    );
}

static Quat quatFromAxisAngle(const Vec3& axis, float angle) {
    float len = vec3Length(axis);
    if (len < 1e-6f) return Quat(0, 0, 0, 1);
    float h = angle * 0.5f;
    float s = std::sin(h);
    Vec3 n = vec3Scale(axis, s / len);
    return Quat(n.x, n.y, n.z, std::cos(h));
}

// Rotate a world vector by quaternion (q * v * q^-1).
static Vec3 quatRotate(const Quat& q, const Vec3& v) {
    Quat p(v.x, v.y, v.z, 0);
    Quat r = quatMul(quatMul(q, p), quatConjugate(q));
    return Vec3(r.x, r.y, r.z);
}

// ── Rigid body inertia ──────────────────────────────────────────────
// Inverse inertia tensor (diagonal in the body's local frame), computed
// from the attached collision shape. Bodies without a shape get a small
// isotropic value so angular dynamics stay well-defined.
static void computeInverseInertia(PhysicsSimulation::RigidBody& body, const PhysicsSimulation::CollisionShape& shape) {
    if (body.isStatic || body.isKinematic) {
        body.invInertia = Vec3(0, 0, 0);
        return;
    }
    float m = body.mass;
    if (m <= 0.0f) {
        body.invInertia = Vec3(0, 0, 0);
        return;
    }
    switch (shape.type) {
        case PhysicsSimulation::CollisionShape::SPHERE: {
            float r = shape.radius;
            float I = 0.4f * m * r * r; // solid sphere: 2/5 m r^2
            float inv = (I > 1e-6f) ? 1.0f / I : 0.0f;
            body.invInertia = Vec3(inv, inv, inv);
            break;
        }
        case PhysicsSimulation::CollisionShape::BOX: {
            float hx = shape.halfExtents.x;
            float hy = shape.halfExtents.y;
            float hz = shape.halfExtents.z;
            float ix = (1.0f / 12.0f) * m * (hy * hy + hz * hz);
            float iy = (1.0f / 12.0f) * m * (hx * hx + hz * hz);
            float iz = (1.0f / 12.0f) * m * (hx * hx + hy * hy);
            body.invInertia = Vec3(ix > 1e-6f ? 1.0f / ix : 0.0f,
                                   iy > 1e-6f ? 1.0f / iy : 0.0f,
                                   iz > 1e-6f ? 1.0f / iz : 0.0f);
            break;
        }
        case PhysicsSimulation::CollisionShape::CAPSULE: {
            // Approximate a capsule as a sphere of the larger of radius/half-height.
            float r = std::max(shape.radius, shape.height * 0.5f);
            float I = 0.4f * m * r * r;
            float inv = (I > 1e-6f) ? 1.0f / I : 0.0f;
            body.invInertia = Vec3(inv, inv, inv);
            break;
        }
        default:
            body.invInertia = Vec3(0, 0, 0);
            break;
    }
}

// Apply the inverse inertia tensor (diagonal in local frame) to a world vector.
static Vec3 worldInvInertiaApply(const PhysicsSimulation::RigidBody& body, const Vec3& v) {
    Vec3 lv = quatRotate(quatConjugate(body.rotation), v);
    Vec3 lr(lv.x * body.invInertia.x, lv.y * body.invInertia.y, lv.z * body.invInertia.z);
    return quatRotate(body.rotation, lr);
}

// Scalar stand-in for the body's inverse inertia (exact for isotropic bodies).
static float bodyInvI(const PhysicsSimulation::RigidBody& body) {
    if (body.isStatic || body.isKinematic || !body.isActive) return 0.0f;
    return (body.invInertia.x + body.invInertia.y + body.invInertia.z) * (1.0f / 3.0f);
}

static float bodyInvMass(const PhysicsSimulation::RigidBody& body) {
    if (body.isStatic || body.isKinematic || !body.isActive) return 0.0f;
    return body.mass > 0.0f ? 1.0f / body.mass : 0.0f;
}

// Closest point on triangle (Ericson, "Real-Time Collision Detection").
static PhysicsSimulation::Vec3 closestPointOnTriangle(
    const PhysicsSimulation::Vec3& p,
    const PhysicsSimulation::Vec3& a,
    const PhysicsSimulation::Vec3& b,
    const PhysicsSimulation::Vec3& c)
{
    PhysicsSimulation::Vec3 ab = vec3Sub(b, a);
    PhysicsSimulation::Vec3 ac = vec3Sub(c, a);
    PhysicsSimulation::Vec3 ap = vec3Sub(p, a);
    float d1 = vec3Dot(ab, ap);
    float d2 = vec3Dot(ac, ap);
    if (d1 <= 0.0f && d2 <= 0.0f) return a;

    PhysicsSimulation::Vec3 bp = vec3Sub(p, b);
    float d3 = vec3Dot(ab, bp);
    float d4 = vec3Dot(ac, bp);
    if (d3 >= 0.0f && d4 <= d3) return b;

    float vc = d1 * d4 - d3 * d2;
    if (vc <= 0.0f && d1 >= 0.0f && d3 <= 0.0f) {
        float v = d1 / (d1 - d3);
        return vec3Add(a, vec3Scale(ab, v));
    }

    PhysicsSimulation::Vec3 cp = vec3Sub(p, c);
    float d5 = vec3Dot(ab, cp);
    float d6 = vec3Dot(ac, cp);
    if (d6 >= 0.0f && d5 <= d6) return c;

    float vb = d5 * d2 - d1 * d6;
    if (vb <= 0.0f && d2 >= 0.0f && d6 <= 0.0f) {
        float w = d2 / (d2 - d6);
        return vec3Add(a, vec3Scale(ac, w));
    }

    float va = d3 * d6 - d5 * d4;
    if (va <= 0.0f && (d4 - d3) >= 0.0f && (d5 - d6) >= 0.0f) {
        float w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
        return vec3Add(b, vec3Scale(vec3Sub(c, b), w));
    }

    float denom = 1.0f / (va + vb + vc);
    float v = vb * denom;
    float w = vc * denom;
    return vec3Add(a, vec3Add(vec3Scale(ab, v), vec3Scale(ac, w)));
}

// AABB pre-filter: cheap reject for a sphere vs triangle box.
static bool sphereIntersectsTriangleAABB(
    const PhysicsSimulation::Vec3& center, float radius,
    const PhysicsSimulation::Vec3& a,
    const PhysicsSimulation::Vec3& b,
    const PhysicsSimulation::Vec3& c)
{
    float minX = std::min({a.x, b.x, c.x}) - radius;
    float maxX = std::max({a.x, b.x, c.x}) + radius;
    float minY = std::min({a.y, b.y, c.y}) - radius;
    float maxY = std::max({a.y, b.y, c.y}) + radius;
    float minZ = std::min({a.z, b.z, c.z}) - radius;
    float maxZ = std::max({a.z, b.z, c.z}) + radius;
    return center.x >= minX && center.x <= maxX &&
           center.y >= minY && center.y <= maxY &&
           center.z >= minZ && center.z <= maxZ;
}

static void resolveSphereMesh(
    PhysicsSimulation::RigidBody& body,
    const PhysicsSimulation::CollisionShape& shape,
    uint64_t bodyId)
{
    for (const auto& tri : s_envTriangles) {
        // For CAPSULE the effective sphere center is the closest point on the
        // capsule segment (axis along local Y from -height/2 to +height/2) to
        // the triangle; solve as a sphere of `radius` around that point.
        PhysicsSimulation::Vec3 center = body.position;
        if (shape.type == PhysicsSimulation::CollisionShape::CAPSULE) {
            Vec3 top = vec3Add(body.position, quatRotate(body.rotation, Vec3(0, shape.height * 0.5f, 0)));
            Vec3 bot = vec3Add(body.position, quatRotate(body.rotation, Vec3(0, -shape.height * 0.5f, 0)));
            Vec3 ab = vec3Sub(top, bot);
            Vec3 ap = vec3Sub(tri.a, bot);
            float t = vec3Dot(ap, ab) / (vec3LengthSq(ab) + 1e-8f);
            t = std::max(0.0f, std::min(1.0f, t));
            center = vec3Add(bot, vec3Scale(ab, t));
        }
        float radius = shape.radius;

        if (!sphereIntersectsTriangleAABB(center, radius, tri.a, tri.b, tri.c)) {
            continue;
        }

        PhysicsSimulation::Vec3 closest = closestPointOnTriangle(center, tri.a, tri.b, tri.c);
        PhysicsSimulation::Vec3 toClosest = vec3Sub(center, closest);
        float distSq = vec3LengthSq(toClosest);
        float radiusSq = radius * radius;
        if (distSq >= radiusSq) continue;

        float dist = std::sqrt(distSq);
        PhysicsSimulation::Vec3 normal;
        if (dist > COLLISION_EPSILON) {
            normal = vec3Scale(toClosest, 1.0f / dist);
        } else {
            normal = tri.normal;
        }

        float penetration = radius - dist;
        if (penetration < 0) penetration = 0;

        if (!body.isStatic) {
            body.position = vec3Add(body.position, vec3Scale(normal, penetration));

            float relVel = vec3Dot(body.velocity, normal);
            if (relVel < 0) {
                body.velocity = vec3Sub(body.velocity, vec3Scale(normal, relVel * (1.0f + body.restitution)));
            }
        }
        s_bodyContactMap[bodyId] = true;
    }
}

bool PhysicsSimulation::isEnvironmentMeshValid() {
    return s_envMeshValid && !s_envTriangles.empty();
}

void PhysicsSimulation::setEnvironmentMesh(const Vec3* vertices, int vertexCount,
                                            const int* indices, int indexCount) {
    if (vertices == nullptr || indices == nullptr || vertexCount <= 0 || indexCount < 3) {
        clearEnvironmentMesh();
        return;
    }
    s_envTriangles.clear();
    s_envTriangles.reserve(indexCount / 3);
    for (int i = 0; i + 2 < indexCount; i += 3) {
        int ia = indices[i];
        int ib = indices[i + 1];
        int ic = indices[i + 2];
        if (ia < 0 || ib < 0 || ic < 0 || ia >= vertexCount || ib >= vertexCount || ic >= vertexCount) {
            continue;
        }
        MeshTriangle tri;
        tri.a = vertices[ia];
        tri.b = vertices[ib];
        tri.c = vertices[ic];
        tri.normal = vec3Normalize(vec3Cross(vec3Sub(tri.b, tri.a), vec3Sub(tri.c, tri.a)));
        s_envTriangles.push_back(tri);
    }
    s_envMeshValid = !s_envTriangles.empty();
}

void PhysicsSimulation::clearEnvironmentMesh() {
    s_envTriangles.clear();
    s_envMeshValid = false;
}

bool PhysicsSimulation::isAvailable() {
    return s_available;
}

bool PhysicsSimulation::initialize() {
    if (s_initialized) return true;

    s_gravity = Vec3(0, -9.81f, 0);
    s_available = true;
    s_initialized = true;

    std::cout << "[Physics] Physics simulation initialized with collision detection" << std::endl;
    return true;
}

void PhysicsSimulation::shutdown() {
    s_rigidBodies.clear();
    s_collisionShapes.clear();
    s_joints.clear();
    s_bodyShapeMap.clear();
    s_initialized = false;
    s_available = false;
}

uint64_t PhysicsSimulation::createRigidBody(const Vec3& position, const Quat& rotation, float mass) {
    uint64_t id = s_nextId++;
    RigidBody body;
    body.id = id;
    body.position = position;
    body.rotation = rotation;
    body.velocity = Vec3(0, 0, 0);
    body.angularVelocity = Vec3(0, 0, 0);
    body.mass = mass;
    body.restitution = 0.3f;
    body.friction = 0.5f;
    body.isStatic = (mass <= 0.0f);
    body.isKinematic = false;
    body.isActive = true;

    CollisionShape shape;
    shape.type = CollisionShape::SPHERE;
    shape.radius = 0.3f;
    uint64_t shapeId = createCollisionShape(shape);
    computeInverseInertia(body, shape);
    s_rigidBodies[id] = body;
    s_bodyShapeMap[id] = shapeId;

    return id;
}

void PhysicsSimulation::destroyRigidBody(uint64_t id) {
    auto it = s_bodyShapeMap.find(id);
    if (it != s_bodyShapeMap.end()) {
        s_collisionShapes.erase(it->second);
        s_bodyShapeMap.erase(it);
    }
    s_rigidBodies.erase(id);
}

void PhysicsSimulation::setRigidBodyPosition(uint64_t id, const Vec3& position) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.position = position;
    }
}

void PhysicsSimulation::setRigidBodyRotation(uint64_t id, const Quat& rotation) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.rotation = rotation;
    }
}

void PhysicsSimulation::setRigidBodyVelocity(uint64_t id, const Vec3& velocity) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.velocity = velocity;
    }
}

void PhysicsSimulation::setRigidBodyAngularVelocity(uint64_t id, const Vec3& angularVelocity) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.angularVelocity = angularVelocity;
    }
}

PhysicsSimulation::RigidBody PhysicsSimulation::getRigidBody(uint64_t id) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        return it->second;
    }
    return RigidBody();
}

void PhysicsSimulation::applyForce(uint64_t id, const Vec3& force) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end() && !it->second.isStatic && !it->second.isKinematic) {
        if (it->second.mass > 0) {
            Vec3 dv = vec3Mul(vec3Scale(force, 1.0f / it->second.mass), it->second.linearFactor);
            it->second.velocity = vec3Add(it->second.velocity, dv);
        }
    }
}

void PhysicsSimulation::applyImpulse(uint64_t id, const Vec3& impulse) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end() && !it->second.isStatic && !it->second.isKinematic) {
        if (it->second.mass > 0) {
            Vec3 dv = vec3Mul(vec3Scale(impulse, 1.0f / it->second.mass), it->second.linearFactor);
            it->second.velocity = vec3Add(it->second.velocity, dv);
        }
    }
}

void PhysicsSimulation::applyTorque(uint64_t id, const Vec3& torque) {
    auto it = s_rigidBodies.find(id);
    if (it == s_rigidBodies.end() || it->second.isStatic || it->second.isKinematic) return;
    Vec3 dw = vec3Mul(worldInvInertiaApply(it->second, torque), it->second.angularFactor);
    it->second.angularVelocity = vec3Add(it->second.angularVelocity, dw);
}

void PhysicsSimulation::applyAngularImpulse(uint64_t id, const Vec3& impulse) {
    auto it = s_rigidBodies.find(id);
    if (it == s_rigidBodies.end() || it->second.isStatic || it->second.isKinematic) return;
    Vec3 dw = vec3Mul(worldInvInertiaApply(it->second, impulse), it->second.angularFactor);
    it->second.angularVelocity = vec3Add(it->second.angularVelocity, dw);
}

PhysicsSimulation::Vec3 PhysicsSimulation::getAngularVelocity(uint64_t id) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        return it->second.angularVelocity;
    }
    return Vec3(0, 0, 0);
}

uint64_t PhysicsSimulation::createCollisionShape(const CollisionShape& shape) {
    uint64_t id = s_nextId++;
    s_collisionShapes[id] = shape;
    return id;
}

void PhysicsSimulation::attachCollisionShape(uint64_t bodyId, uint64_t shapeId) {
    s_bodyShapeMap[bodyId] = shapeId;
    if (s_collisionShapes.find(shapeId) != s_collisionShapes.end()) {
        auto& shape = s_collisionShapes[shapeId];
        auto it = s_rigidBodies.find(bodyId);
        if (it != s_rigidBodies.end()) {
            computeInverseInertia(it->second, shape);
            if (shape.type == CollisionShape::BOX) {
                float r = std::max({shape.halfExtents.x, shape.halfExtents.y, shape.halfExtents.z});
                it->second.friction = 0.6f;
            } else if (shape.type == CollisionShape::SPHERE) {
                it->second.restitution = 0.4f;
            }
        }
    }
}

uint64_t PhysicsSimulation::createJoint(uint64_t bodyA, uint64_t bodyB,
                                         const Vec3& pivotA, const Vec3& pivotB) {
    uint64_t id = s_nextId++;
    Joint joint;
    joint.id = id;
    joint.type = Joint::DISTANCE;
    joint.bodyA = bodyA;
    joint.bodyB = bodyB;
    joint.pivotA = pivotA;
    joint.pivotB = pivotB;
    joint.axisA = Vec3(0, 1, 0);
    joint.axisB = Vec3(0, 1, 0);
    joint.swingSpan1 = 0.0f;
    joint.swingSpan2 = 0.0f;
    joint.twistSpan = 0.0f;
    joint.linearLimit = 0.0f;
    joint.angularLimit = 0.0f;
    joint.breakForce = 1000.0f;
    joint.forceAccumulator = 0.0f;
    joint.isBroken = false;
    s_joints[id] = joint;
    return id;
}

void PhysicsSimulation::destroyJoint(uint64_t id) {
    s_joints.erase(id);
}

void PhysicsSimulation::breakJoint(uint64_t id) {
    auto it = s_joints.find(id);
    if (it != s_joints.end()) {
        it->second.isBroken = true;
    }
}

static void resolveCollision(
    PhysicsSimulation::Vec3& posA, PhysicsSimulation::Vec3& velA, float invMassA,
    PhysicsSimulation::Vec3& posB, PhysicsSimulation::Vec3& velB, float invMassB,
    const PhysicsSimulation::Vec3& normal, float penetration,
    float restitution, float friction)
{
    if (penetration <= 0) return;

    Vec3 relVel = vec3Sub(velA, velB);
    float relVelNormal = vec3Dot(relVel, normal);

    if (relVelNormal > 0) return;

    float j = -(1.0f + restitution) * relVelNormal / (invMassA + invMassB + COLLISION_EPSILON);

    Vec3 impulse = vec3Scale(normal, j);
    velA = vec3Add(velA, vec3Scale(impulse, invMassA));
    velB = vec3Sub(velB, vec3Scale(impulse, invMassB));

    Vec3 correction = vec3Scale(normal, penetration * 0.5f);
    if (invMassA > 0) posA = vec3Add(posA, correction);
    if (invMassB > 0) posB = vec3Sub(posB, correction);
}

static void resolveSphereSphere(
    PhysicsSimulation::RigidBody& bodyA, const PhysicsSimulation::CollisionShape& shapeA,
    PhysicsSimulation::RigidBody& bodyB, const PhysicsSimulation::CollisionShape& shapeB)
{
    Vec3 diff = vec3Sub(bodyA.position, bodyB.position);
    float distSq = vec3LengthSq(diff);
    float combinedRadius = shapeA.radius + shapeB.radius;

    if (distSq >= combinedRadius * combinedRadius || distSq < COLLISION_EPSILON) return;

    float dist = std::sqrt(distSq);
    Vec3 normal = vec3Scale(diff, 1.0f / dist);
    float penetration = combinedRadius - dist;

    float invMassA = bodyA.isStatic ? 0 : (1.0f / bodyA.mass);
    float invMassB = bodyB.isStatic ? 0 : (1.0f / bodyB.mass);
    float restitution = (bodyA.restitution + bodyB.restitution) * 0.5f;
    float friction = (bodyA.friction + bodyB.friction) * 0.5f;

    resolveCollision(bodyA.position, bodyA.velocity, invMassA,
                     bodyB.position, bodyB.velocity, invMassB,
                     normal, penetration, restitution, friction);
}

static void resolveSphereGround(
    PhysicsSimulation::RigidBody& body, const PhysicsSimulation::CollisionShape& shape,
    uint64_t bodyId)
{
    float halfH = (shape.type == PhysicsSimulation::CollisionShape::CAPSULE) ? shape.height * 0.5f : 0.0f;
    float bottomY = body.position.y - shape.radius - halfH;
    if (bottomY >= GROUND_PLANE_Y) return;

    float penetration = GROUND_PLANE_Y - bottomY;

    if (!body.isStatic) {
        body.position.y = GROUND_PLANE_Y + shape.radius + halfH;

        if (body.velocity.y < 0) {
            body.velocity.y = -body.velocity.y * body.restitution;
        }

        if (std::abs(body.velocity.y) < 0.1f) {
            body.velocity.y = 0;
            body.velocity.x *= (1.0f - body.friction * 0.1f);
            body.velocity.z *= (1.0f - body.friction * 0.1f);
        }
        s_bodyContactMap[bodyId] = true;
    }
}

static void applyJointConstraint(
    PhysicsSimulation::Joint& joint,
    PhysicsSimulation::RigidBody& bodyA,
    PhysicsSimulation::RigidBody& bodyB)
{
    if (joint.isBroken) return;

    Vec3 diff = vec3Sub(bodyB.position, bodyA.position);
    float dist = vec3Length(diff);
    float restLength = vec3Distance(joint.pivotB, joint.pivotA);

    if (restLength < 0.01f) restLength = 0.1f;

    float stretch = dist - restLength;

    if (std::abs(stretch) < 0.001f) return;

    float forceMag = stretch * 50.0f;
    Vec3 direction = vec3Normalize(diff);
    Vec3 force = vec3Scale(direction, forceMag);

    if (forceMag > joint.breakForce) {
        joint.isBroken = true;
        return;
    }

    float invMassA = bodyA.isStatic ? 0 : (1.0f / bodyA.mass);
    float invMassB = bodyB.isStatic ? 0 : (1.0f / bodyB.mass);

    Vec3 velDiff = vec3Sub(bodyA.velocity, bodyB.velocity);
    float damping = 5.0f;
    Vec3 dampingForce = vec3Scale(direction, vec3Dot(velDiff, direction) * damping);

    Vec3 totalForce = vec3Add(force, dampingForce);

    if (invMassA > 0) bodyA.velocity = vec3Add(bodyA.velocity, vec3Scale(totalForce, invMassA));
    if (invMassB > 0) bodyB.velocity = vec3Sub(bodyB.velocity, vec3Scale(totalForce, invMassB));
}

// ── Cone-twist joint (real geometric constraint) ────────────────────
// A cone-twist joint is a ball-in-socket (3-DOF positional) constraint
// plus an angular limit that keeps body B's frame inside an elliptical
// swing cone around body A's joint axis, and the twist around that axis
// inside the configured twist span.
//
// Solve strategy (per sub-step, CONE_TWIST_ITERATIONS passes):
//   1. Velocity level: sequential-impulse ball-in-socket projection along
//      the current anchor error direction (1D Jacobian projection that
//      accounts for linear + angular inertia). Also damp relative angular
//      velocity about the swing/twist limit axes.
//   2. Position level: PBD-style anchor correction (slop + per-iteration
//      fraction), plus rotation projection that pulls the swing/twist back
//      inside their limits, weighted by inverse inertia.
// The accumulated impulse magnitude |lambda| is compared against
// breakForce; an overloaded joint snaps.

static const int CONE_TWIST_ITERATIONS = 8;
static const float JOINT_POSITION_SLOP = 0.005f;
static const float JOINT_CORRECTION_PERCENT = 0.4f;
static const float JOINT_ANGULAR_SLOP = 0.02f; // ~1.15 deg

static Vec3 jointWorldPivotA(const PhysicsSimulation::Joint& joint, const PhysicsSimulation::RigidBody& a) {
    return vec3Add(a.position, quatRotate(a.rotation, joint.pivotA));
}

static Vec3 jointWorldPivotB(const PhysicsSimulation::Joint& joint, const PhysicsSimulation::RigidBody& b) {
    return vec3Add(b.position, quatRotate(b.rotation, joint.pivotB));
}

// Velocity-level ball-in-socket projection along a unit direction.
// Returns the impulse magnitude (accumulated into joint.forceAccumulator).
static float solveBallSocketVelocity(PhysicsSimulation::Joint& joint,
                                     PhysicsSimulation::RigidBody& a,
                                     PhysicsSimulation::RigidBody& b,
                                     const Vec3& dir)
{
    Vec3 pa = jointWorldPivotA(joint, a);
    Vec3 pb = jointWorldPivotB(joint, b);
    Vec3 rA = vec3Sub(pa, a.position);
    Vec3 rB = vec3Sub(pb, b.position);

    Vec3 vA = vec3Add(a.velocity, vec3Cross(a.angularVelocity, rA));
    Vec3 vB = vec3Add(b.velocity, vec3Cross(b.angularVelocity, rB));
    Vec3 vRel = vec3Sub(vA, vB);
    // Ball-in-socket is an EQUALITY constraint: relative velocity at the
    // anchor must be zero along dir in both directions. Unlike a contact
    // constraint there is no "separating -> skip" case.
    float vn = vec3Dot(vRel, dir);
    if (std::abs(vn) < 1e-6f) return 0.0f;

    float invMassA = bodyInvMass(a);
    float invMassB = bodyInvMass(b);
    Vec3 rAxn = vec3Cross(rA, dir);
    Vec3 rBxn = vec3Cross(rB, dir);
    float k = invMassA + invMassB
            + vec3Dot(rAxn, worldInvInertiaApply(a, rAxn))
            + vec3Dot(rBxn, worldInvInertiaApply(b, rBxn));
    if (k < 1e-8f) return 0.0f;

    float lambda = -vn / k;
    joint.forceAccumulator += std::abs(lambda);

    Vec3 impulse = vec3Scale(dir, lambda);
    if (invMassA > 0.0f) {
        a.velocity = vec3Add(a.velocity, vec3Scale(impulse, invMassA));
        a.angularVelocity = vec3Add(a.angularVelocity, worldInvInertiaApply(a, vec3Cross(rA, impulse)));
    }
    if (invMassB > 0.0f) {
        b.velocity = vec3Sub(b.velocity, vec3Scale(impulse, invMassB));
        b.angularVelocity = vec3Sub(b.angularVelocity, worldInvInertiaApply(b, vec3Cross(rB, impulse)));
    }
    return lambda;
}

// PBD-style position correction for the ball-in-socket anchor.
static void solveBallSocketPosition(PhysicsSimulation::Joint& joint,
                                    PhysicsSimulation::RigidBody& a,
                                    PhysicsSimulation::RigidBody& b)
{
    Vec3 pa = jointWorldPivotA(joint, a);
    Vec3 pb = jointWorldPivotB(joint, b);
    Vec3 error = vec3Sub(pb, pa);
    float dist = vec3Length(error);
    if (dist < JOINT_POSITION_SLOP) return;

    Vec3 dir = vec3Scale(error, 1.0f / dist);
    float invMassA = bodyInvMass(a);
    float invMassB = bodyInvMass(b);
    float total = invMassA + invMassB;
    if (total < 1e-8f) return;

    float corr = (dist - JOINT_POSITION_SLOP) * JOINT_CORRECTION_PERCENT;
    Vec3 correction = vec3Scale(dir, corr);
    if (invMassA > 0.0f) a.position = vec3Add(a.position, vec3Scale(correction, invMassA / total));
    if (invMassB > 0.0f) b.position = vec3Sub(b.position, vec3Scale(correction, invMassB / total));
}

// Damp relative angular velocity about `axis` (zeroes the relative spin).
static void dampAngularAboutAxis(PhysicsSimulation::RigidBody& a, PhysicsSimulation::RigidBody& b, const Vec3& axis) {
    float invA = bodyInvI(a);
    float invB = bodyInvI(b);
    float total = invA + invB;
    if (total < 1e-8f) return;
    Vec3 wRel = vec3Sub(a.angularVelocity, b.angularVelocity);
    float wvn = vec3Dot(wRel, axis);
    if (std::abs(wvn) < 1e-5f) return;
    Vec3 impulse = vec3Scale(axis, -wvn / total);
    if (invA > 0.0f) a.angularVelocity = vec3Add(a.angularVelocity, worldInvInertiaApply(a, impulse));
    if (invB > 0.0f) b.angularVelocity = vec3Sub(b.angularVelocity, worldInvInertiaApply(b, impulse));
}

// Rotate both bodies so the swing/twist exceedance is removed, weighted
// by inverse inertia (PBD angular projection).
static void correctAngularLimit(PhysicsSimulation::RigidBody& a, PhysicsSimulation::RigidBody& b,
                                const Vec3& worldAxis, float excessAngle)
{
    float invA = bodyInvI(a);
    float invB = bodyInvI(b);
    float total = invA + invB;
    if (total < 1e-8f) return;
    Quat corrA = quatFromAxisAngle(worldAxis, excessAngle * (invA / total));
    Quat corrB = quatFromAxisAngle(worldAxis, -excessAngle * (invB / total));
    a.rotation = quatNormalize(quatMul(corrA, a.rotation));
    b.rotation = quatNormalize(quatMul(corrB, b.rotation));
}

// Swing/twist limit enforcement. qRel = qA^-1 * qB (B's frame in A's frame)
// is split into a twist quaternion around local Z and a swing quaternion
// whose axis lies in the XY plane.
static void solveSwingTwistLimits(PhysicsSimulation::Joint& joint,
                                  PhysicsSimulation::RigidBody& a,
                                  PhysicsSimulation::RigidBody& b)
{
    if (joint.swingSpan1 <= 0.0f && joint.swingSpan2 <= 0.0f && joint.twistSpan <= 0.0f) {
        return; // no angular limits configured
    }

    Quat qRel = quatMul(quatConjugate(a.rotation), b.rotation);

    // Twist about the joint axis (local Z of A).
    Quat qTwist = quatNormalize(Quat(0, 0, qRel.z, qRel.w));
    if (std::abs(qTwist.w) < 1e-6f && std::abs(qTwist.z) < 1e-6f) {
        qTwist = Quat(0, 0, 0, 1);
    }
    Quat qSwing = quatMul(qRel, quatConjugate(qTwist));

    // ── Swing limits ──
    float swingAngle = 2.0f * std::acos(clamp(qSwing.w, -1.0f, 1.0f));
    Vec3 swingAxisLocal(1, 0, 0); // fallback (rotate around A's X)
    float sLen = std::sqrt(qSwing.x * qSwing.x + qSwing.y * qSwing.y);
    if (sLen > 1e-6f) {
        swingAxisLocal = Vec3(qSwing.x / sLen, qSwing.y / sLen, 0);
    }

    if (joint.swingSpan1 > 0.0f && joint.swingSpan2 > 0.0f && swingAngle > 1e-5f) {
        // Elliptical cone: allowed tilt angle for direction phi is
        // atan(sqrt((tan s1 cos phi)^2 + (tan s2 sin phi)^2)).
        float phi = std::atan2(swingAxisLocal.y, swingAxisLocal.x);
        float t1 = std::tan(joint.swingSpan1);
        float t2 = std::tan(joint.swingSpan2);
        float limit = std::atan2(
            std::sqrt(t1 * t1 * std::cos(phi) * std::cos(phi) + t2 * t2 * std::sin(phi) * std::sin(phi)),
            1.0f);
        float excess = swingAngle - limit;
        if (excess > JOINT_ANGULAR_SLOP) {
            Vec3 swingAxisWorld = quatRotate(a.rotation, swingAxisLocal);
            correctAngularLimit(a, b, swingAxisWorld, excess - JOINT_ANGULAR_SLOP);
            dampAngularAboutAxis(a, b, swingAxisWorld);
        }
    } else if ((joint.swingSpan1 > 0.0f || joint.swingSpan2 > 0.0f) && swingAngle > 1e-5f) {
        // Single-axis swing limit (hinge-like): clamp the whole swing angle.
        float span = (joint.swingSpan1 > 0.0f) ? joint.swingSpan1 : joint.swingSpan2;
        float excess = swingAngle - span;
        if (excess > JOINT_ANGULAR_SLOP) {
            Vec3 swingAxisWorld = quatRotate(a.rotation, swingAxisLocal);
            correctAngularLimit(a, b, swingAxisWorld, excess - JOINT_ANGULAR_SLOP);
            dampAngularAboutAxis(a, b, swingAxisWorld);
        }
    }

    // ── Twist limit ──
    if (joint.twistSpan > 0.0f) {
        float twistAngle = 2.0f * std::atan2(qTwist.z, qTwist.w); // (-pi, pi]
        float excessTwist = 0.0f;
        if (twistAngle > joint.twistSpan) {
            excessTwist = twistAngle - joint.twistSpan;
        } else if (twistAngle < -joint.twistSpan) {
            excessTwist = twistAngle + joint.twistSpan;
        }
        if (std::abs(excessTwist) > JOINT_ANGULAR_SLOP) {
            Vec3 twistAxisWorld = quatRotate(a.rotation, Vec3(0, 0, 1));
            float correction = (excessTwist > 0.0f) ? (excessTwist - JOINT_ANGULAR_SLOP)
                                                    : (excessTwist + JOINT_ANGULAR_SLOP);
            correctAngularLimit(a, b, twistAxisWorld, correction);
            dampAngularAboutAxis(a, b, twistAxisWorld);
        }
    }
}

static void solveConeTwistJoint(PhysicsSimulation::Joint& joint,
                                PhysicsSimulation::RigidBody& a,
                                PhysicsSimulation::RigidBody& b)
{
    for (int iter = 0; iter < CONE_TWIST_ITERATIONS; iter++) {
        Vec3 pa = jointWorldPivotA(joint, a);
        Vec3 pb = jointWorldPivotB(joint, b);
        Vec3 error = vec3Sub(pb, pa);
        float dist = vec3Length(error);
        if (dist > 1e-6f) {
            Vec3 dir = vec3Scale(error, 1.0f / dist);
            solveBallSocketVelocity(joint, a, b, dir);
        }
        solveSwingTwistLimits(joint, a, b);
        solveBallSocketPosition(joint, a, b);
    }
}

void PhysicsSimulation::stepSimulation(float deltaTime) {
    if (deltaTime <= 0 || deltaTime > 0.1f) return;

    // Sub-stepping for stability
    int subSteps = 4;
    float subDt = deltaTime / subSteps;

    s_bodyContactMap.clear();

    for (int step = 0; step < subSteps; step++) {
        // Apply gravity, integrate linear + angular state
        for (auto& [id, body] : s_rigidBodies) {
            if (body.isStatic || body.isKinematic || !body.isActive) continue;

            Vec3 dv = vec3Mul(vec3Scale(s_gravity, subDt), body.linearFactor);
            body.velocity = vec3Add(body.velocity, dv);
            body.position = vec3Add(body.position, vec3Scale(body.velocity, subDt));

            // Orientation integration: q' = 0.5 * omega_quat (x) q
            Vec3 w = vec3Mul(body.angularVelocity, body.angularFactor);
            Quat wq(w.x, w.y, w.z, 0);
            Quat dq = quatMul(wq, body.rotation);
            body.rotation = quatNormalize(Quat(
                body.rotation.x + dq.x * 0.5f * subDt,
                body.rotation.y + dq.y * 0.5f * subDt,
                body.rotation.z + dq.z * 0.5f * subDt,
                body.rotation.w + dq.w * 0.5f * subDt));

            float damping = 1.0f - (0.01f * subDt * 60.0f);
            if (damping < 0) damping = 0;
            body.velocity = vec3Scale(body.velocity, damping);
            body.angularVelocity = vec3Scale(body.angularVelocity, 0.99f);

            if (body.position.y < -100.0f) {
                body.position.y = 100.0f;
                body.velocity = Vec3(0, 0, 0);
                body.angularVelocity = Vec3(0, 0, 0);
            }
        }

        // Sphere-ground collisions
        for (auto& [id, body] : s_rigidBodies) {
            auto shapeIt = s_bodyShapeMap.find(id);
            if (shapeIt == s_bodyShapeMap.end()) continue;
            auto shapeIt2 = s_collisionShapes.find(shapeIt->second);
            if (shapeIt2 == s_collisionShapes.end()) continue;

            if (shapeIt2->second.type == CollisionShape::SPHERE ||
                shapeIt2->second.type == CollisionShape::CAPSULE) {
                resolveSphereGround(body, shapeIt2->second, id);
            }
        }

        // Sphere/Capsule vs environment mesh collisions
        if (s_envMeshValid) {
            for (auto& [id, body] : s_rigidBodies) {
                if (body.isStatic || body.isKinematic || !body.isActive) continue;
                auto shapeIt = s_bodyShapeMap.find(id);
                if (shapeIt == s_bodyShapeMap.end()) continue;
                auto shapeIt2 = s_collisionShapes.find(shapeIt->second);
                if (shapeIt2 == s_collisionShapes.end()) continue;
                if (shapeIt2->second.type != CollisionShape::SPHERE &&
                    shapeIt2->second.type != CollisionShape::CAPSULE) continue;
                resolveSphereMesh(body, shapeIt2->second, id);
            }
        }

        // Sphere-sphere collisions
        auto itA = s_rigidBodies.begin();
        while (itA != s_rigidBodies.end()) {
            auto shapeAIt = s_bodyShapeMap.find(itA->first);
            if (shapeAIt == s_bodyShapeMap.end()) { ++itA; continue; }
            auto shapeAIt2 = s_collisionShapes.find(shapeAIt->second);
            if (shapeAIt2 == s_collisionShapes.end() || shapeAIt2->second.type != CollisionShape::SPHERE) {
                ++itA; continue;
            }

            auto itB = itA;
            ++itB;
            while (itB != s_rigidBodies.end()) {
                auto shapeBIt = s_bodyShapeMap.find(itB->first);
                if (shapeBIt == s_bodyShapeMap.end()) { ++itB; continue; }
                auto shapeBIt2 = s_collisionShapes.find(shapeBIt->second);
                if (shapeBIt2 != s_collisionShapes.end() && shapeBIt2->second.type == CollisionShape::SPHERE) {
                    resolveSphereSphere(itA->second, shapeAIt2->second,
                                       itB->second, shapeBIt2->second);
                }
                ++itB;
            }
            ++itA;
        }

        // Joint constraints
        for (auto& [id, joint] : s_joints) {
            if (joint.isBroken) continue;

            auto itA = s_rigidBodies.find(joint.bodyA);
            auto itB = s_rigidBodies.find(joint.bodyB);
            if (itA == s_rigidBodies.end() || itB == s_rigidBodies.end()) continue;

            if (joint.type == Joint::CONE_TWIST) {
                joint.forceAccumulator = 0.0f;
                solveConeTwistJoint(joint, itA->second, itB->second);
                if (joint.forceAccumulator > joint.breakForce) {
                    joint.isBroken = true;
                }
            } else {
                applyJointConstraint(joint, itA->second, itB->second);
            }
        }
    }
}

void PhysicsSimulation::setGravity(const Vec3& gravity) {
    s_gravity = gravity;
}

bool PhysicsSimulation::isBodyGrounded(uint64_t id) {
    auto it = s_bodyContactMap.find(id);
    return it != s_bodyContactMap.end() && it->second;
}

uint64_t PhysicsSimulation::createRigidBodyWithShape(const Vec3& position, const Quat& rotation, float mass,
                                                      CollisionShape::Type shapeType, const Vec3& shapeParam1, float shapeParam2) {
    uint64_t id = s_nextId++;
    RigidBody body;
    body.id = id;
    body.position = position;
    body.rotation = rotation;
    body.velocity = Vec3(0, 0, 0);
    body.angularVelocity = Vec3(0, 0, 0);
    body.mass = mass;
    body.restitution = 0.3f;
    body.friction = 0.5f;
    body.isStatic = (mass <= 0.0f);
    body.isKinematic = false;
    body.isActive = true;

    CollisionShape shape;
    shape.type = shapeType;
    switch (shapeType) {
        case CollisionShape::BOX:
            shape.halfExtents = shapeParam1;
            break;
        case CollisionShape::SPHERE:
            shape.radius = shapeParam1.x;
            break;
        case CollisionShape::CAPSULE:
            shape.radius = shapeParam1.x;
            shape.height = shapeParam2;
            break;
        default:
            shape.type = CollisionShape::SPHERE;
            shape.radius = 0.3f;
            break;
    }

    uint64_t shapeId = createCollisionShape(shape);
    computeInverseInertia(body, shape);
    s_rigidBodies[id] = body;
    s_bodyShapeMap[id] = shapeId;

    return id;
}

void PhysicsSimulation::setRestitution(uint64_t id, float restitution) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.restitution = restitution;
    }
}

void PhysicsSimulation::setFriction(uint64_t id, float friction) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.friction = friction;
    }
}

void PhysicsSimulation::setLinearFactor(uint64_t id, const Vec3& factor) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.linearFactor = Vec3(
            clamp(factor.x, 0.0f, 1.0f),
            clamp(factor.y, 0.0f, 1.0f),
            clamp(factor.z, 0.0f, 1.0f));
    }
}

void PhysicsSimulation::setAngularFactor(uint64_t id, const Vec3& factor) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.angularFactor = Vec3(
            clamp(factor.x, 0.0f, 1.0f),
            clamp(factor.y, 0.0f, 1.0f),
            clamp(factor.z, 0.0f, 1.0f));
    }
}

void PhysicsSimulation::setKinematicPose(uint64_t id, const Vec3& position, const Quat& rotation) {
    auto it = s_rigidBodies.find(id);
    if (it != s_rigidBodies.end()) {
        it->second.isKinematic = true;
        it->second.position = position;
        it->second.rotation = rotation;
        it->second.velocity = Vec3(0, 0, 0);
        it->second.angularVelocity = Vec3(0, 0, 0);
    }
}

uint64_t PhysicsSimulation::createConeTwistJoint(uint64_t bodyA, uint64_t bodyB,
                                                  const Vec3& pivotA, const Vec3& pivotB,
                                                  float swingSpan1, float swingSpan2, float twistSpan) {
    return createConeTwistJointEx(bodyA, bodyB, pivotA, pivotB,
                                  Vec3(0, 0, 1), Vec3(0, 0, 1),
                                  swingSpan1, swingSpan2, twistSpan);
}

uint64_t PhysicsSimulation::createConeTwistJointEx(uint64_t bodyA, uint64_t bodyB,
                                                   const Vec3& pivotA, const Vec3& pivotB,
                                                   const Vec3& axisA, const Vec3& axisB,
                                                   float swingSpan1, float swingSpan2, float twistSpan) {
    uint64_t id = s_nextId++;
    Joint joint;
    joint.id = id;
    joint.type = Joint::CONE_TWIST;
    joint.bodyA = bodyA;
    joint.bodyB = bodyB;
    joint.pivotA = pivotA;
    joint.pivotB = pivotB;
    Vec3 aA = axisA, aB = axisB;
    if (vec3LengthSq(aA) < 1e-6f) aA = Vec3(0, 0, 1);
    if (vec3LengthSq(aB) < 1e-6f) aB = Vec3(0, 0, 1);
    joint.axisA = vec3Normalize(aA);
    joint.axisB = vec3Normalize(aB);
    joint.swingSpan1 = std::max(0.0f, swingSpan1);
    joint.swingSpan2 = std::max(0.0f, swingSpan2);
    joint.twistSpan = std::max(0.0f, twistSpan);
    joint.linearLimit = 0.0f;
    joint.angularLimit = 0.0f;
    // Default break force: a hard impact (death impulse) should be able to
    // tear limbs off, but normal ragdoll stresses must not. With typical
    // ragdoll masses (1-5 kg) the per-frame constraint impulse is on the
    // order of tens; 300 gives a safety margin and preserves the old
    // "snap under extreme force" semantics.
    joint.breakForce = 300.0f;
    joint.forceAccumulator = 0.0f;
    joint.isBroken = false;
    s_joints[id] = joint;
    return id;
}

void PhysicsSimulation::setJointAngularLimits(uint64_t jointId, float swingSpan1, float swingSpan2, float twistSpan) {
    auto it = s_joints.find(jointId);
    if (it != s_joints.end()) {
        it->second.swingSpan1 = std::max(0.0f, swingSpan1);
        it->second.swingSpan2 = std::max(0.0f, swingSpan2);
        it->second.twistSpan = std::max(0.0f, twistSpan);
    }
}

void PhysicsSimulation::setJointLimit(uint64_t jointId, float linearLimit, float angularLimit) {
    auto it = s_joints.find(jointId);
    if (it != s_joints.end()) {
        it->second.linearLimit = linearLimit;
        it->second.angularLimit = angularLimit;
        if (linearLimit > 0.0f) {
            it->second.breakForce = linearLimit;
        }
        if (angularLimit > 0.0f && it->second.type == Joint::CONE_TWIST) {
            // Interpret angularLimit as a twist clamp (Bullet-style) when it
            // is tighter than the configured twist span.
            it->second.twistSpan = std::min(it->second.twistSpan, angularLimit);
        }
    }
}

void PhysicsSimulation::breakJointWithForce(uint64_t id, float force) {
    auto it = s_joints.find(id);
    if (it != s_joints.end()) {
        if (force >= it->second.breakForce) {
            it->second.isBroken = true;
        }
    }
}

PhysicsSimulation::Vec3 PhysicsSimulation::getGravity() {
    return s_gravity;
}

bool PhysicsSimulation::raycast(const Vec3& from, const Vec3& to, Vec3& hitPoint, Vec3& hitNormal) {
    Vec3 direction = vec3Sub(to, from);
    float maxDist = vec3Length(direction);
    if (maxDist < 0.001f) return false;
    direction = vec3Scale(direction, 1.0f / maxDist);

    bool hit = false;
    float closestDist = maxDist;

    for (auto& [id, body] : s_rigidBodies) {
        if (body.isKinematic) continue;

        auto shapeIt = s_bodyShapeMap.find(id);
        if (shapeIt == s_bodyShapeMap.end()) continue;
        auto shapeIt2 = s_collisionShapes.find(shapeIt->second);
        if (shapeIt2 == s_collisionShapes.end()) continue;

        Vec3 diff = vec3Sub(body.position, from);
        float proj = vec3Dot(diff, direction);
        if (proj < 0 || proj > maxDist) continue;

        Vec3 closest = vec3Add(from, vec3Scale(direction, proj));
        Vec3 closestDiff = vec3Sub(closest, body.position);
        float dist = vec3Length(closestDiff);

        float radius = 0.3f;
        if (shapeIt2->second.type == CollisionShape::SPHERE) {
            radius = shapeIt2->second.radius;
        } else if (shapeIt2->second.type == CollisionShape::BOX) {
            radius = std::max({shapeIt2->second.halfExtents.x,
                              shapeIt2->second.halfExtents.y,
                              shapeIt2->second.halfExtents.z});
        }

        if (dist < radius && proj < closestDist) {
            closestDist = proj;
            hitPoint = closest;
            hitNormal = vec3Normalize(closestDiff);
            hit = true;
        }
    }

    // Ray vs environment mesh (Möller–Trumbore)
    if (s_envMeshValid) {
        for (const auto& tri : s_envTriangles) {
            PhysicsSimulation::Vec3 edge1 = vec3Sub(tri.b, tri.a);
            PhysicsSimulation::Vec3 edge2 = vec3Sub(tri.c, tri.a);
            PhysicsSimulation::Vec3 h = vec3Cross(direction, edge2);
            float det = vec3Dot(edge1, h);
            if (std::abs(det) < COLLISION_EPSILON) continue;

            float invDet = 1.0f / det;
            PhysicsSimulation::Vec3 s = vec3Sub(from, tri.a);
            float u = vec3Dot(s, h) * invDet;
            if (u < 0.0f || u > 1.0f) continue;

            PhysicsSimulation::Vec3 q = vec3Cross(s, edge1);
            float v = vec3Dot(direction, q) * invDet;
            if (v < 0.0f || u + v > 1.0f) continue;

            float t = vec3Dot(edge2, q) * invDet;
            if (t < 0.0f || t > closestDist) continue;

            closestDist = t;
            hitPoint = vec3Add(from, vec3Scale(direction, t));
            hitNormal = tri.normal;
            hit = true;
        }
    }

    return hit;
}

bool PhysicsSimulation::sphereCast(const Vec3& from, const Vec3& to, float radius,
                                   Vec3& hitPoint, Vec3& hitNormal) {
    Vec3 direction = vec3Sub(to, from);
    float maxDist = vec3Length(direction);
    if (maxDist < 0.001f) return false;
    direction = vec3Scale(direction, 1.0f / maxDist);

    bool hit = false;
    float closestDist = maxDist;

    for (auto& [id, body] : s_rigidBodies) {
        if (body.isKinematic) continue;

        Vec3 diff = vec3Sub(body.position, from);
        float proj = vec3Dot(diff, direction);
        if (proj < 0 || proj > maxDist) continue;

        Vec3 closest = vec3Add(from, vec3Scale(direction, proj));
        Vec3 closestDiff = vec3Sub(closest, body.position);
        float dist = vec3Length(closestDiff);

        float bodyRadius = 0.3f;
        auto shapeIt = s_bodyShapeMap.find(id);
        if (shapeIt != s_bodyShapeMap.end()) {
            auto shapeIt2 = s_collisionShapes.find(shapeIt->second);
            if (shapeIt2 != s_collisionShapes.end() && shapeIt2->second.type == CollisionShape::SPHERE) {
                bodyRadius = shapeIt2->second.radius;
            }
        }

        if (dist < radius + bodyRadius && proj < closestDist) {
            closestDist = proj;
            hitPoint = closest;
            hitNormal = vec3Normalize(closestDiff);
            hit = true;
        }
    }

    return hit;
}
