# .phy 真实物理 ragdoll 实现计划（PHY）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 C++ 完整解析 `.phy`（solid 全字段 + ragdollconstraint + editparams）并经 JNI 全量传输给 Java，`NpcRagdoll` 完全由 `.phy` 驱动（真实 pivot/limits/mass/inertia/damping），并启用全骨骼 CAPSULE 环境碰撞 + 接触状态反馈。

**架构：** 4 层：C++ PhyParser 文本 KV 解析 → `nativeParsePhySerialized` ByteWriter 尾部追加序列化 → Java `PhyParser.java` 字段 + `WindowsNativeModelParserStrategy.deserializeParsedPhy` 读回 → `NpcRagdoll` 消费。另加 C++ `resolveSphereMesh` 扩展到 CAPSULE + 接触标志，`NpcRagdoll` 改用 `createRigidBodyWithShape` 创建 CAPSULE 骨骼。

**技术栈：** C++20（CMake Release / MSVC）、Java 17 Forge 1.20.1、JNI、JUnit 5（gradle test）、JOML。

---

## 文件结构

| 文件 | 职责 | 动作 |
|------|------|------|
| `src/main/native/include/phy_parser.h` | `RagdollConstraint` 结构、`PhySolid` 全字段、`ParsedPhy` 扩展 | 修改 |
| `src/main/native/src/phy_parser.cpp` | `parseSolidBlocks`/`parseRagdollConstraintBlocks`/`parseEditParams` + `parse()` 挂钩 | 修改 |
| `src/main/native/src/native_core_bridge.cpp` | `nativeParsePhySerialized` ByteWriter 尾部追加三段新数据 | 修改 |
| `src/main/native/include/physics_simulation.h` | CAPSULE 网格碰撞声明 + 接触状态查询 API | 修改 |
| `src/main/native/src/physics_simulation.cpp` | `resolveSphereMesh` 支持 CAPSULE、`resolveSphereGround` 支持 CAPSULE、`s_bodyContactMap` + `isBodyGrounded` | 修改 |
| `src/main/native/src/native_bridge.cpp` | `nativeIsBodyGrounded` JNI | 修改 |
| `src/main/java/.../client/model/PhyParser.java` | `PhyConstraint`/`PhySolid` 全字段 + 文本 KV 解析 | 修改 |
| `src/main/java/.../client/model/WindowsNativeModelParserStrategy.java` | `deserializeParsedPhy` 读回新段 | 修改 |
| `src/main/java/.../client/model/PhysicsBridge.java` | `createRigidBodyWithShape` 常量、`isBodyGrounded` | 修改 |
| `src/main/java/.../client/model/NpcRagdoll.java` | `getPhyConstraints()`、CAPSULE 骨骼、真实约束、solid mass/damping | 修改 |
| `src/test/.../client/model/PhyParserTest.java` | Java 文本 KV 解析测试 | 创建 |
| `src/test/.../client/model/PhyConstraintContractTest.java` | native 序列化契约测试 | 创建 |

---

### 任务 1：C++ PhyParser 数据结构扩展

**文件：**
- 修改：`src/main/native/include/phy_parser.h`

- [ ] **步骤 1：在 `PhySolid` 加全物理字段**

在 `phy_parser.h` 中把 `PhySolid` 改为：

```cpp
    struct PhySolid {
        int index;
        std::string name;
        int parent = -1;
        float mass = 0.0f;
        std::string surfaceprop;
        float damping = 0.0f;
        float rotdamping = 0.0f;
        float inertia = 0.0f;
        float volume = 0.0f;
        std::vector<PhyConvexHull> convexHulls;
    };
```

- [ ] **步骤 2：新增 `RagdollConstraint` 结构与 `ParsedPhy` 扩展**

在 `RagdollJointRef` 结构之后、`ParsedPhy` 之前插入：

```cpp
    // One constraint from the "ragdollconstraint" KeyValues block. parent/child
    // reference solid indices; the names are resolved via the solid table.
    // Source maps X/Y/Z Euler ranges to swing/twist of a cone-twist joint:
    //   xmin/xmax -> swing1 (around parent local X)
    //   ymin/ymax -> swing2 (around parent local Y)
    //   zmin/zmax -> twist   (around parent local Z)
    struct RagdollConstraint {
        int parentIndex = -1;
        int childIndex = -1;
        std::string parentName;
        std::string childName;
        float xmin = 0.0f, xmax = 0.0f, xfriction = 0.0f;
        float ymin = 0.0f, ymax = 0.0f, yfriction = 0.0f;
        float zmin = 0.0f, zmax = 0.0f, zfriction = 0.0f;
        // Estimated joint pivot: child solid convex-hull centroid (world = local
        // at creation time, matching how NpcRagdoll builds local-frame anchors).
        float pivotX = 0.0f, pivotY = 0.0f, pivotZ = 0.0f;
    };
```

`ParsedPhy` 扩展为：

```cpp
    struct ParsedPhy {
        int32_t size;
        std::string id;
        int32_t solidCount;
        int32_t checksum;
        std::vector<PhySolid> solids;
        std::vector<RagdollJointRef> ragdollJoints;
        std::vector<RagdollConstraint> ragdollConstraints;
        std::string rootName;
        float totalMass = 0.0f;
        bool valid;
    };
```

- [ ] **步骤 3：验证编译**

运行：`cmake --build "src/main/native/build" --config Release --target native-renderer -j`
预期：`native-renderer.vcxproj -> ...\native-renderer.dll` 成功，无报错。

---

### 任务 2：C++ PhyParser 文本 KV 解析实现

**文件：**
- 修改：`src/main/native/src/phy_parser.cpp`

- [ ] **步骤 1：实现 solid 全字段解析**

在 `parseSolidNames` 之后新增（复用相同 KV 扫描模式）：

```cpp
// Parse the full per-solid property set from the KeyValues text section:
// parent, mass, surfaceprop, damping, rotdamping, inertia, volume.
static void parseSolidProperties(const uint8_t* data, int kvStart, int kvEnd,
                                 std::vector<PhyParser::PhySolid>& solids) {
    std::string kvText(reinterpret_cast<const char*>(data) + kvStart, kvEnd - kvStart);
    size_t idx = 0;
    while (idx < kvText.size()) {
        size_t blockStart = kvText.find("solid", idx);
        if (blockStart == std::string::npos) break;
        size_t braceOpen = kvText.find('{', blockStart);
        if (braceOpen == std::string::npos) break;
        size_t braceClose = kvText.find('}', braceOpen);
        if (braceClose == std::string::npos) break;

        std::string block = kvText.substr(braceOpen + 1, braceClose - braceOpen - 1);
        int solidIndex = -1;
        std::map<std::string, std::string> props;

        size_t propIdx = 0;
        while (true) {
            size_t q1 = block.find('"', propIdx); if (q1 == std::string::npos) break;
            size_t q2 = block.find('"', q1 + 1); if (q2 == std::string::npos) break;
            size_t q3 = block.find('"', q2 + 1); if (q3 == std::string::npos) break;
            size_t q4 = block.find('"', q3 + 1); if (q4 == std::string::npos) break;
            std::string key = block.substr(q1 + 1, q2 - q1 - 1);
            std::string value = block.substr(q3 + 1, q4 - q3 - 1);
            props[key] = value;
            if (key == "index") { try { solidIndex = std::stoi(value); } catch (...) {} }
            propIdx = q4 + 1;
        }

        if (solidIndex >= 0 && solidIndex < static_cast<int>(solids.size())) {
            auto& solid = solids[solidIndex];
            auto getF = [&](const char* k, float def) -> float {
                auto it = props.find(k);
                if (it == props.end()) return def;
                try { return std::stof(it->second); } catch (...) { return def; }
            };
            auto getS = [&](const char* k) -> std::string {
                auto it = props.find(k);
                return (it != props.end()) ? it->second : "";
            };
            solid.parent = (props.count("parent") && !props["parent"].empty()) ? std::stoi(props["parent"]) : -1;
            solid.mass = getF("mass", 0.0f);
            solid.surfaceprop = getS("surfaceprop");
            solid.damping = getF("damping", 0.0f);
            solid.rotdamping = getF("rotdamping", 0.0f);
            solid.inertia = getF("inertia", 0.0f);
            solid.volume = getF("volume", 0.0f);
        }
        idx = braceClose + 1;
    }
}
```

文件顶部补 `#include <map>`。

- [ ] **步骤 2：实现 ragdollconstraint 解析**

在 `parseRagdollJoints` 之后新增：

```cpp
// Parse "ragdollconstraint" blocks. Each block names parent/child by solid
// INDEX (not bone name); names are resolved through the solid table. The
// pivot is estimated as the child solid's convex-hull vertex centroid.
static void parseRagdollConstraintBlocks(const uint8_t* data, int kvStart, int kvEnd,
                                         std::vector<PhyParser::PhySolid>& solids,
                                         std::vector<PhyParser::RagdollConstraint>& out) {
    std::string kvText(reinterpret_cast<const char*>(data) + kvStart, kvEnd - kvStart);
    size_t idx = 0;
    while (idx < kvText.size()) {
        size_t blockStart = kvText.find("ragdollconstraint", idx);
        if (blockStart == std::string::npos) break;
        size_t braceOpen = kvText.find('{', blockStart);
        if (braceOpen == std::string::npos) break;
        size_t braceClose = kvText.find('}', braceOpen);
        if (braceClose == std::string::npos) break;

        std::string block = kvText.substr(braceOpen + 1, braceClose - braceOpen - 1);
        PhyParser::RagdollConstraint c;
        std::map<std::string, std::string> props;

        size_t propIdx = 0;
        while (true) {
            size_t q1 = block.find('"', propIdx); if (q1 == std::string::npos) break;
            size_t q2 = block.find('"', q1 + 1); if (q2 == std::string::npos) break;
            size_t q3 = block.find('"', q2 + 1); if (q3 == std::string::npos) break;
            size_t q4 = block.find('"', q3 + 1); if (q4 == std::string::npos) break;
            props[block.substr(q1 + 1, q2 - q1 - 1)] = block.substr(q3 + 1, q4 - q3 - 1);
            propIdx = q4 + 1;
        }

        auto getF = [&](const char* k, float def) -> float {
            auto it = props.find(k);
            if (it == props.end()) return def;
            try { return std::stof(it->second); } catch (...) { return def; }
        };
        auto getI = [&](const char* k, int def) -> int {
            auto it = props.find(k);
            if (it == props.end()) return def;
            try { return std::stoi(it->second); } catch (...) { return def; }
        };

        c.parentIndex = getI("parent", -1);
        c.childIndex = getI("child", -1);
        c.xmin = getF("xmin", 0.0f); c.xmax = getF("xmax", 0.0f); c.xfriction = getF("xfriction", 0.0f);
        c.ymin = getF("ymin", 0.0f); c.ymax = getF("ymax", 0.0f); c.yfriction = getF("yfriction", 0.0f);
        c.zmin = getF("zmin", 0.0f); c.zmax = getF("zmax", 0.0f); c.zfriction = getF("zfriction", 0.0f);

        // Resolve names + estimate pivot from child solid hull centroid.
        if (c.parentIndex >= 0 && c.parentIndex < static_cast<int>(solids.size())) {
            c.parentName = solids[c.parentIndex].name;
        }
        if (c.childIndex >= 0 && c.childIndex < static_cast<int>(solids.size())) {
            const auto& child = solids[c.childIndex];
            c.childName = child.name;
            float sx = 0, sy = 0, sz = 0; int n = 0;
            for (const auto& hull : child.convexHulls) {
                for (const auto& v : hull.vertices) { sx += v.x; sy += v.y; sz += v.z; n++; }
            }
            if (n > 0) {
                c.pivotX = sx / n; c.pivotY = sy / n; c.pivotZ = sz / n;
            }
        }

        out.push_back(c);
        idx = braceClose + 1;
    }
}
```

- [ ] **步骤 3：实现 editparams 解析**

在 `parseRagdollConstraintBlocks` 之后新增：

```cpp
// Parse the "editparams" block: rootname + totalmass.
static void parseEditParams(const uint8_t* data, int kvStart, int kvEnd,
                            std::string& rootName, float& totalMass) {
    std::string kvText(reinterpret_cast<const char*>(data) + kvStart, kvEnd - kvStart);
    size_t blockStart = kvText.find("editparams");
    if (blockStart == std::string::npos) return;
    size_t braceOpen = kvText.find('{', blockStart);
    if (braceOpen == std::string::npos) return;
    size_t braceClose = kvText.find('}', braceOpen);
    if (braceClose == std::string::npos) return;

    std::string block = kvText.substr(braceOpen + 1, braceClose - braceOpen - 1);
    std::map<std::string, std::string> props;
    size_t propIdx = 0;
    while (true) {
        size_t q1 = block.find('"', propIdx); if (q1 == std::string::npos) break;
        size_t q2 = block.find('"', q1 + 1); if (q2 == std::string::npos) break;
        size_t q3 = block.find('"', q2 + 1); if (q3 == std::string::npos) break;
        size_t q4 = block.find('"', q3 + 1); if (q4 == std::string::npos) break;
        props[block.substr(q1 + 1, q2 - q1 - 1)] = block.substr(q3 + 1, q4 - q3 - 1);
        propIdx = q4 + 1;
    }
    auto it = props.find("rootname");
    if (it != props.end()) rootName = it->second;
    it = props.find("totalmass");
    if (it != props.end()) { try { totalMass = std::stof(it->second); } catch (...) {} }
}
```

- [ ] **步骤 4：在 `parse()` 末尾挂钩三个解析器**

在 `parse()` 中 `parseSolidNames(...)` / `parseRagdollJoints(...)` 调用之后追加：

```cpp
        if (kvStart > 0) {
            parseSolidNames(raw, kvStart, fileLen, result.solids);
            parseSolidProperties(raw, kvStart, fileLen, result.solids);
            parseRagdollJoints(raw, kvStart, fileLen, result.ragdollJoints);
            parseRagdollConstraintBlocks(raw, kvStart, fileLen, result.solids, result.ragdollConstraints);
            parseEditParams(raw, kvStart, fileLen, result.rootName, result.totalMass);
        }
```

注意：需将原 `parseSolidNames(raw, kvStart, fileLen, result.solids);` 和 `parseRagdollJoints(raw, kvStart, fileLen, result.ragdollJoints);` 两行移入同一 `if (kvStart > 0)` 块（原代码分散在两处，见 `parse()` 尾部）。

- [ ] **步骤 5：验证编译**

运行：`cmake --build "src/main/native/build" --config Release --target native-renderer -j`
预期：编译成功，无报错。

---

### 任务 3：JNI 序列化追加三段新数据

**文件：**
- 修改：`src/main/native/src/native_core_bridge.cpp`

- [ ] **步骤 1：追加 solid 全字段、约束数组、editparams**

在 `nativeParsePhySerialized` 的 solids 循环结束后、`return w.toJByteArray(env);` 之前插入：

```cpp
        // --- Segment A: per-solid physics properties (parent/mass/...).
        // Read after the existing solid block data; old Java deserializers
        // simply stop at the previous end-of-data, so this stays backwards
        // compatible (trailing bytes are ignored by a reader that never reads
        // past its known schema).
        w.writeInt(static_cast<int>(parsed.solids.size()));
        for (const auto& solid : parsed.solids) {
            w.writeInt(solid.parent);
            w.writeFloat(solid.mass);
            w.writeString(solid.surfaceprop);
            w.writeFloat(solid.damping);
            w.writeFloat(solid.rotdamping);
            w.writeFloat(solid.inertia);
            w.writeFloat(solid.volume);
        }

        // --- Segment B: ragdoll constraints.
        w.writeInt(static_cast<int>(parsed.ragdollConstraints.size()));
        for (const auto& c : parsed.ragdollConstraints) {
            w.writeInt(c.parentIndex);
            w.writeInt(c.childIndex);
            w.writeString(c.parentName);
            w.writeString(c.childName);
            w.writeFloat(c.xmin); w.writeFloat(c.xmax); w.writeFloat(c.xfriction);
            w.writeFloat(c.ymin); w.writeFloat(c.ymax); w.writeFloat(c.yfriction);
            w.writeFloat(c.zmin); w.writeFloat(c.zmax); w.writeFloat(c.zfriction);
            w.writeFloat(c.pivotX); w.writeFloat(c.pivotY); w.writeFloat(c.pivotZ);
        }

        // --- Segment C: editparams.
        w.writeString(parsed.rootName);
        w.writeFloat(parsed.totalMass);
```

- [ ] **步骤 2：验证编译**

运行：`cmake --build "src/main/native/build" --config Release --target native-renderer -j`
预期：编译成功。

---

### 任务 4：C++ 求解器 CAPSULE 环境碰撞 + 接触标志

**文件：**
- 修改：`src/main/native/include/physics_simulation.h`
- 修改：`src/main/native/src/physics_simulation.cpp`
- 修改：`src/main/native/src/native_bridge.cpp`

- [ ] **步骤 1：头文件声明接触查询 API**

在 `physics_simulation.h` 的 `sphereCast` 声明之后追加：

```cpp
    // True if the body collided with the ground plane or the environment mesh
    // during the most recent stepSimulation (reset at the start of each step).
    static bool isBodyGrounded(uint64_t id);
```

- [ ] **步骤 2：加入接触标志存储**

在 `physics_simulation.cpp` 顶部（`s_bodyShapeMap` 声明之后）追加：

```cpp
static std::unordered_map<uint64_t, bool> s_bodyContactMap;
```

- [ ] **步骤 3：`stepSimulation` 开始处重置接触标志**

在 `stepSimulation` 的 `int subSteps = 4;` 之前插入：

```cpp
    s_bodyContactMap.clear();
```

- [ ] **步骤 4：`resolveSphereMesh` 支持 CAPSULE 并在命中时置接触标志**

将 `resolveSphereMesh` 签名改为接受形状并处理 CAPSULE，替换整个函数：

```cpp
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
```

- [ ] **步骤 5：更新 `stepSimulation` 中 mesh 碰撞调用点**

替换 `physics_simulation.cpp:903-913` 的 mesh 碰撞循环：

```cpp
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
```

- [ ] **步骤 6：`resolveSphereGround` 支持 CAPSULE + 置接触标志**

将签名改为：

```cpp
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
```

- [ ] **步骤 7：更新 ground 碰撞调用点**

替换 `physics_simulation.cpp:890-900` 的 sphere-ground 循环：

```cpp
        // Sphere/Capsule-ground collisions
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
```

- [ ] **步骤 8：实现 `isBodyGrounded`**

在文件末尾 `getGravity` 实现之后追加：

```cpp
bool PhysicsSimulation::isBodyGrounded(uint64_t id) {
    auto it = s_bodyContactMap.find(id);
    return it != s_bodyContactMap.end() && it->second;
}
```

- [ ] **步骤 9：JNI 暴露 `nativeIsBodyGrounded`**

在 `native_bridge.cpp` 的 `nativeSetActivationState` JNI 实现附近追加：

```cpp
JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_PhysicsBridge_nativeIsBodyGrounded(
    JNIEnv*, jclass, jlong id)
{
    return PhysicsSimulation::isBodyGrounded(static_cast<uint64_t>(id)) ? JNI_TRUE : JNI_FALSE;
}
```

- [ ] **步骤 10：验证编译**

运行：`cmake --build "src/main/native/build" --config Release --target native-renderer -j`
预期：编译成功。

---

### 任务 5：Java PhyParser 字段 + 文本 KV 解析

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/PhyParser.java`

- [ ] **步骤 1：加 `PhyConstraint` 类与 `ParsedPhy`/`PhySolid` 字段**

在 `PhyVertex` 类之后插入 `PhyConstraint`：

```java
    public static class PhyConstraint {
        public int parentIndex = -1;
        public int childIndex = -1;
        public String parentName;
        public String childName;
        public float xmin, xmax, xfriction;
        public float ymin, ymax, yfriction;
        public float zmin, zmax, zfriction;
        public float pivotX, pivotY, pivotZ;
    }
```

`ParsedPhy` 增加字段：

```java
        public List<PhyConstraint> ragdollConstraints = new ArrayList<>();
        public String rootName;
        public float totalMass;
```

`PhySolid` 增加字段：

```java
        public int parent = -1;
        public float mass;
        public String surfaceprop;
        public float damping;
        public float rotdamping;
        public float inertia;
        public float volume;
```

- [ ] **步骤 2：实现 Java 侧文本 KV 解析**

在 `parseSolidNames` 之后新增三个方法（镜像 C++）：

```java
    private static void parseSolidProperties(byte[] data, int kvStart, int kvEnd, List<PhySolid> solids) {
        String kvText = new String(data, kvStart, kvEnd - kvStart, StandardCharsets.UTF_8);
        int idx = 0;
        while (idx < kvText.length()) {
            int blockStart = kvText.indexOf("solid", idx);
            if (blockStart < 0) break;
            int braceOpen = kvText.indexOf('{', blockStart);
            if (braceOpen < 0) break;
            int braceClose = kvText.indexOf('}', braceOpen);
            if (braceClose < 0) break;
            String block = kvText.substring(braceOpen + 1, braceClose);
            Integer solidIndex = null;
            Map<String, String> props = new HashMap<>();
            int propIdx = 0;
            while (true) {
                int q1 = block.indexOf('"', propIdx); if (q1 < 0) break;
                int q2 = block.indexOf('"', q1 + 1); if (q2 < 0) break;
                int q3 = block.indexOf('"', q2 + 1); if (q3 < 0) break;
                int q4 = block.indexOf('"', q3 + 1); if (q4 < 0) break;
                props.put(block.substring(q1 + 1, q2), block.substring(q3 + 1, q4));
                if (block.substring(q1 + 1, q2).equals("index")) {
                    try { solidIndex = Integer.parseInt(block.substring(q3 + 1, q4)); }
                    catch (NumberFormatException ignored) {}
                }
                propIdx = q4 + 1;
            }
            if (solidIndex != null && solidIndex >= 0 && solidIndex < solids.size()) {
                PhySolid solid = solids.get(solidIndex);
                solid.parent = parseIntOr(props.get("parent"), -1);
                solid.mass = parseFloatOr(props.get("mass"), 0f);
                solid.surfaceprop = props.getOrDefault("surfaceprop", "");
                solid.damping = parseFloatOr(props.get("damping"), 0f);
                solid.rotdamping = parseFloatOr(props.get("rotdamping"), 0f);
                solid.inertia = parseFloatOr(props.get("inertia"), 0f);
                solid.volume = parseFloatOr(props.get("volume"), 0f);
            }
            idx = braceClose + 1;
        }
    }

    private static void parseRagdollConstraintBlocks(byte[] data, int kvStart, int kvEnd,
                                                     List<PhySolid> solids, List<PhyConstraint> out) {
        String kvText = new String(data, kvStart, kvEnd - kvStart, StandardCharsets.UTF_8);
        int idx = 0;
        while (idx < kvText.length()) {
            int blockStart = kvText.indexOf("ragdollconstraint", idx);
            if (blockStart < 0) break;
            int braceOpen = kvText.indexOf('{', blockStart);
            if (braceOpen < 0) break;
            int braceClose = kvText.indexOf('}', braceOpen);
            if (braceClose < 0) break;
            String block = kvText.substring(braceOpen + 1, braceClose);
            Map<String, String> props = new HashMap<>();
            int propIdx = 0;
            while (true) {
                int q1 = block.indexOf('"', propIdx); if (q1 < 0) break;
                int q2 = block.indexOf('"', q1 + 1); if (q2 < 0) break;
                int q3 = block.indexOf('"', q2 + 1); if (q3 < 0) break;
                int q4 = block.indexOf('"', q3 + 1); if (q4 < 0) break;
                props.put(block.substring(q1 + 1, q2), block.substring(q3 + 1, q4));
                propIdx = q4 + 1;
            }
            PhyConstraint c = new PhyConstraint();
            c.parentIndex = parseIntOr(props.get("parent"), -1);
            c.childIndex = parseIntOr(props.get("child"), -1);
            c.xmin = parseFloatOr(props.get("xmin"), 0f); c.xmax = parseFloatOr(props.get("xmax"), 0f);
            c.xfriction = parseFloatOr(props.get("xfriction"), 0f);
            c.ymin = parseFloatOr(props.get("ymin"), 0f); c.ymax = parseFloatOr(props.get("ymax"), 0f);
            c.yfriction = parseFloatOr(props.get("yfriction"), 0f);
            c.zmin = parseFloatOr(props.get("zmin"), 0f); c.zmax = parseFloatOr(props.get("zmax"), 0f);
            c.zfriction = parseFloatOr(props.get("zfriction"), 0f);

            if (c.parentIndex >= 0 && c.parentIndex < solids.size()) {
                c.parentName = solids.get(c.parentIndex).name;
            }
            if (c.childIndex >= 0 && c.childIndex < solids.size()) {
                PhySolid child = solids.get(c.childIndex);
                c.childName = child.name;
                float sx = 0, sy = 0, sz = 0; int n = 0;
                for (PhyConvexHull hull : child.convexHulls) {
                    for (PhyVertex v : hull.vertices) { sx += v.x; sy += v.y; sz += v.z; n++; }
                }
                if (n > 0) {
                    c.pivotX = sx / n; c.pivotY = sy / n; c.pivotZ = sz / n;
                }
            }
            out.add(c);
            idx = braceClose + 1;
        }
    }

    private static void parseEditParams(byte[] data, int kvStart, int kvEnd, ParsedPhy result) {
        String kvText = new String(data, kvStart, kvEnd - kvStart, StandardCharsets.UTF_8);
        int blockStart = kvText.indexOf("editparams");
        if (blockStart < 0) return;
        int braceOpen = kvText.indexOf('{', blockStart);
        if (braceOpen < 0) return;
        int braceClose = kvText.indexOf('}', braceOpen);
        if (braceClose < 0) return;
        String block = kvText.substring(braceOpen + 1, braceClose);
        Map<String, String> props = new HashMap<>();
        int propIdx = 0;
        while (true) {
            int q1 = block.indexOf('"', propIdx); if (q1 < 0) break;
            int q2 = block.indexOf('"', q1 + 1); if (q2 < 0) break;
            int q3 = block.indexOf('"', q2 + 1); if (q3 < 0) break;
            int q4 = block.indexOf('"', q3 + 1); if (q4 < 0) break;
            props.put(block.substring(q1 + 1, q2), block.substring(q3 + 1, q4));
            propIdx = q4 + 1;
        }
        result.rootName = props.getOrDefault("rootname", "");
        result.totalMass = parseFloatOr(props.get("totalmass"), 0f);
    }

    private static int parseIntOr(String v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static float parseFloatOr(String v, float def) {
        if (v == null) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }
```

- [ ] **步骤 3：在 `parse()` 挂钩**

在 `parse()` 中调用 `parseSolidNames` 之后（`int kvStart = findKvStart(data);` 所在处，`parseSolidNames` 调用行之后）追加：

```java
            parseSolidProperties(data, kvStart, fileLen, result.solids);
            parseRagdollConstraintBlocks(data, kvStart, fileLen, result.solids, result.ragdollConstraints);
            parseEditParams(data, kvStart, fileLen, result);
```

注意 `parseSolidNames` 调用返回 Map 赋给 `solidNames`，要把它也改为同时填充（不改现有逻辑，只在之后追加新调用）。

- [ ] **步骤 4：验证编译**

运行：`gradlew.bat compileJava --rerun-tasks`
预期：BUILD SUCCESSFUL。

---

### 任务 6：Java 反序列化读回新段

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/WindowsNativeModelParserStrategy.java`

- [ ] **步骤 1：在 `deserializeParsedPhy` 中读回三段数据**

在 `deserializeParsedPhy` 的 `result.solids.add(solid);` 循环结束、`result.valid = true;` 之前插入：

```java
            // --- Segment A: per-solid physics properties.
            int propCount = buf.getInt();
            for (int i = 0; i < propCount && i < result.solids.size(); i++) {
                PhyParser.PhySolid solid = result.solids.get(i);
                solid.parent = buf.getInt();
                solid.mass = buf.getFloat();
                solid.surfaceprop = readString(buf);
                solid.damping = buf.getFloat();
                solid.rotdamping = buf.getFloat();
                solid.inertia = buf.getFloat();
                solid.volume = buf.getFloat();
            }

            // --- Segment B: ragdoll constraints.
            int constraintCount = buf.getInt();
            result.ragdollConstraints = new ArrayList<>(constraintCount);
            for (int i = 0; i < constraintCount; i++) {
                PhyParser.PhyConstraint c = new PhyParser.PhyConstraint();
                c.parentIndex = buf.getInt();
                c.childIndex = buf.getInt();
                c.parentName = readString(buf);
                c.childName = readString(buf);
                c.xmin = buf.getFloat(); c.xmax = buf.getFloat(); c.xfriction = buf.getFloat();
                c.ymin = buf.getFloat(); c.ymax = buf.getFloat(); c.yfriction = buf.getFloat();
                c.zmin = buf.getFloat(); c.zmax = buf.getFloat(); c.zfriction = buf.getFloat();
                c.pivotX = buf.getFloat(); c.pivotY = buf.getFloat(); c.pivotZ = buf.getFloat();
                result.ragdollConstraints.add(c);
            }

            // --- Segment C: editparams.
            result.rootName = readString(buf);
            result.totalMass = buf.getFloat();
```

- [ ] **步骤 2：验证编译**

运行：`gradlew.bat compileJava --rerun-tasks`
预期：BUILD SUCCESSFUL。

---

### 任务 7：Java PhysicsBridge 接触查询

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/PhysicsBridge.java`

- [ ] **步骤 1：加 `isBodyGrounded` 方法**

在 `setActivationState` 方法之后追加：

```java
    /**
     * Whether the given body collided with the ground plane or the environment
     * mesh during the most recent native stepSimulation call.
     */
    public static boolean isBodyGrounded(long id) {
        if (!available) return false;
        try {
            return nativeIsBodyGrounded(id);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
```

- [ ] **步骤 2：声明 native 方法**

在 native 声明区追加：

```java
    private static native boolean nativeIsBodyGrounded(long id);
```

- [ ] **步骤 3：验证编译**

运行：`gradlew.bat compileJava --rerun-tasks`
预期：BUILD SUCCESSFUL。

---

### 任务 8：NpcRagdoll 接入真实约束 + CAPSULE 骨骼

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcRagdoll.java`

- [ ] **步骤 1：加 `getPhyConstraints()` 与 solid 查找**

在 `getJointSpans` 方法之前追加：

```java
    /**
     * Best-effort resolution of .phy-driven constraints for this ragdoll.
     * Returns a map from child bone name -> {parentName, pivot(MC space),
     * swing1(rad), swing2(rad), twist(rad), friction}. Falls back through:
     *   .phy ragdollconstraint blocks -> ragdoll->joints + heuristic spans
     *   -> empty (caller uses pure heuristic).
     */
    private Map<String, PhyJointSpec> loadPhyConstraints() {
        Map<String, PhyJointSpec> result = new HashMap<>();
        String modelName = entityData.get(DATA_MODEL_NAME);
        if (modelName.isEmpty()) return result;

        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir == null) return result;
        Path packageDir = modelsDir.resolve(modelName);
        Path phyFile = findPhyFile(packageDir);
        if (phyFile == null) return result;

        PhyParser.ParsedPhy phy;
        try {
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            phy = strategy.parsePhy(Files.readAllBytes(phyFile));
        } catch (Exception e) {
            LOGGER.debug("[NpcRagdoll] Failed to parse PHY: {}", e.getMessage());
            return result;
        }
        if (phy == null || !phy.valid) return result;

        // Map solid index -> name for constraint resolution.
        Map<Integer, String> solidNameByIndex = new HashMap<>();
        for (PhyParser.PhySolid s : phy.solids) {
            if (s.index >= 0 && s.name != null) solidNameByIndex.put(s.index, s.name);
        }

        // Preferred: real ragdollconstraint blocks.
        for (PhyParser.PhyConstraint c : phy.ragdollConstraints) {
            if (c.childName == null || c.parentName == null) continue;
            float swing1 = rad(c.xmin, c.xmax);
            float swing2 = rad(c.ymin, c.ymax);
            float twist = rad(c.zmin, c.zmax);
            float friction = (c.xfriction + c.yfriction + c.zfriction) / 3f;
            PhyJointSpec spec = new PhyJointSpec(c.parentName, new float[]{c.pivotX, c.pivotY, c.pivotZ},
                    swing1, swing2, twist, friction);
            result.put(c.childName, spec);
        }
        if (!result.isEmpty()) return result;

        // Fallback: ragdoll->joints + heuristic spans.
        for (PhyParser.RagdollJointRef ref : phy.ragdollJoints) {
            if (ref.name == null || ref.parentName == null) continue;
            float[] spans = getJointSpans(ref.parentName, ref.name);
            PhyJointSpec spec = new PhyJointSpec(ref.parentName, null,
                    spans[0], spans[1], spans[2], 0f);
            result.put(ref.name, spec);
        }
        return result;
    }

    /** Convert a Source Euler range [min,max] (radians) into a cone half-angle. */
    private static float rad(float min, float max) {
        float span = Math.abs(max - min) * 0.5f;
        return Math.min(span, (float) Math.PI);
    }

    private static Path findPhyFile(Path packageDir) {
        if (packageDir == null) return null;
        try (Stream<Path> files = Files.walk(packageDir, 4)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (f.getFileName().toString().toLowerCase().endsWith(".phy")) return f;
            }
        } catch (IOException e) {
            LOGGER.debug("[NpcRagdoll] PHY search failed: {}", e.getMessage());
        }
        return null;
    }

    /** Resolved .phy joint spec (all values in Minecraft space / radians). */
    private static class PhyJointSpec {
        final String parentName;
        final float[] pivotMc;
        final float swing1, swing2, twist, friction;
        PhyJointSpec(String parentName, float[] pivotMc, float swing1, float swing2, float twist, float friction) {
            this.parentName = parentName;
            this.pivotMc = pivotMc;
            this.swing1 = swing1;
            this.swing2 = swing2;
            this.twist = twist;
            this.friction = friction;
        }
    }
```

- [ ] **步骤 2：在 `initPhysics` 使用真实约束 + solid 物理 + CAPSULE body**

修改 `initPhysics` 开头（`modelBones` 加载之后、`boneDepths` 计算之前）加载约束：

```java
        Map<String, PhyJointSpec> phySpecs = loadPhyConstraints();
        Map<String, PhyParser.PhySolid> solidsByName = new HashMap<>();
        loadPhySolidMap(solidsByName);
```

并新增 `loadPhySolidMap`：

```java
    private void loadPhySolidMap(Map<String, PhyParser.PhySolid> out) {
        String modelName = entityData.get(DATA_MODEL_NAME);
        if (modelName.isEmpty()) return;
        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir == null) return;
        Path phyFile = findPhyFile(modelsDir.resolve(modelName));
        if (phyFile == null) return;
        try {
            PhyParser.ParsedPhy phy = ModelParserProvider.getStrategy().parsePhy(Files.readAllBytes(phyFile));
            if (phy != null && phy.valid) {
                for (PhyParser.PhySolid s : phy.solids) {
                    if (s.name != null) out.put(s.name, s);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[NpcRagdoll] Solid map failed: {}", e.getMessage());
        }
    }
```

- [ ] **步骤 3：body 创建改为带 CAPSULE 形状**

将 `initPhysics` 中 body 创建循环改为（替换 `boneBodyIds[i] = PhysicsBridge.createRigidBody(...)` 段）：

```java
        for (int i = 0; i < boneCount; i++) {
            MdlDataTypes.Bone bone = modelBones.get(i);

            float bx = -bone.pos[1];
            float by = bone.pos[2];
            float bz = bone.pos[0];
            bx *= modelScale; by *= modelScale; bz *= modelScale;

            float worldX = baseX + bx * cosYaw - bz * sinYaw;
            float worldZ = baseZ + bx * sinYaw + bz * cosYaw;
            float worldY = baseY + by;

            // Mass: .phy solid mass if present, else heuristic depth-based.
            float mass = getMass(boneDepths, i, bone);
            if (phySpecs != null) {
                PhyParser.PhySolid solid = solidsByName.get(bone.name);
                if (solid != null && solid.mass > 0) mass = solid.mass;
            }

            // Capsule: radius scaled to the bone's length, axis along Y.
            float boneLen = boneLength(bone);
            float capRadius = Math.max(0.1f, boneLen * modelScale * 0.18f);
            float capHeight = Math.max(0.2f, boneLen * modelScale * 0.9f);
            boneBodyIds[i] = PhysicsBridge.createRigidBodyWithShape(
                worldX, worldY, worldZ, mass, PhysicsBridge.SHAPE_CAPSULE,
                new float[]{capRadius, capHeight});

            // Apply .phy damping if present (linear + angular).
            PhyParser.PhySolid solid = solidsByName.get(bone.name);
            if (solid != null && (solid.damping != 0 || solid.rotdamping != 0)) {
                PhysicsBridge.setDamping(boneBodyIds[i],
                    solid.damping > 0 ? solid.damping : 0.1f,
                    solid.rotdamping > 0 ? solid.rotdamping : 0.1f);
            }

            PhysicsBridge.setVelocity(boneBodyIds[i],
                    deathVelocityX, deathVelocityY + (float)(Math.random() * 0.5), deathVelocityZ);
            PhysicsBridge.setAngularVelocity(boneBodyIds[i],
                    deathAngularVelX * (float)(Math.random() * 0.5 + 0.5),
                    deathAngularVelY,
                    deathAngularVelZ * (float)(Math.random() * 0.5 + 0.5));
        }
```

新增 `boneLength` 辅助：

```java
    private static float boneLength(MdlDataTypes.Bone bone) {
        float[] pos = bone.pos;
        return (float) Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2]);
    }
```

- [ ] **步骤 4：joint 创建使用真实约束**

替换 `initPhysics` 的 joint 创建循环（`createConeTwistJointEx` 段），当存在 .phy 约束时使用真实 pivot/limits：

```java
        int jointIdx = 0;
        for (int i = 0; i < boneCount; i++) {
            MdlDataTypes.Bone bone = modelBones.get(i);
            if (bone.parent >= 0 && bone.parent < boneCount) {
                float[] parentPos = PhysicsBridge.getPosition(boneBodyIds[bone.parent]);
                float[] childPos = PhysicsBridge.getPosition(boneBodyIds[i]);

                // .phy-driven joint spec (child bone name lookup), if available.
                PhyJointSpec spec = (phySpecs != null) ? phySpecs.get(bone.name) : null;

                float jx, jy, jz;
                if (spec != null && spec.pivotMc != null) {
                    // Pivot is relative to the child bone's creation position in
                    // world space (yaw applied already in spec? no — spec pivot is
                    // Source space, convert via same transform).
                    float px = -spec.pivotMc[1];
                    float py = spec.pivotMc[2];
                    float pz = spec.pivotMc[0];
                    px *= modelScale; py *= modelScale; pz *= modelScale;
                    float wx = baseX + px * cosYaw - pz * sinYaw;
                    float wz = baseZ + px * sinYaw + pz * cosYaw;
                    float wy = baseY + py;
                    // Project onto the parent-child segment for stability.
                    jx = (parentPos[0] + wx) * 0.5f;
                    jy = (parentPos[1] + wy) * 0.5f;
                    jz = (parentPos[2] + wz) * 0.5f;
                } else {
                    jx = (parentPos[0] + childPos[0]) / 2.0f;
                    jy = (parentPos[1] + childPos[1]) / 2.0f;
                    jz = (parentPos[2] + childPos[2]) / 2.0f;
                }

                float pax = jx - parentPos[0], pay = jy - parentPos[1], paz = jz - parentPos[2];
                float pbx = jx - childPos[0], pby = jy - childPos[1], pbz = jz - childPos[2];

                float dx = childPos[0] - parentPos[0];
                float dy = childPos[1] - parentPos[1];
                float dz = childPos[2] - parentPos[2];
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-4f) { dx = 0; dy = 1; dz = 0; len = 1; }
                float ax = dx / len, ay = dy / len, az = dz / len;

                float swing1, swing2, twist;
                if (spec != null) {
                    swing1 = spec.swing1; swing2 = spec.swing2; twist = spec.twist;
                } else {
                    float[] spans = getJointSpans(bone.parent >= 0 ? modelBones.get(bone.parent).name : null, bone.name);
                    swing1 = spans[0]; swing2 = spans[1]; twist = spans[2];
                }

                long jointId = PhysicsBridge.createConeTwistJointEx(
                    boneBodyIds[bone.parent], boneBodyIds[i],
                    pax, pay, paz, pbx, pby, pbz,
                    ax, ay, az, ax, ay, az,
                    swing1, swing2, twist);
                jointIds[jointIdx++] = jointId;

                if (spec != null && spec.friction > 0) {
                    // Friction softens the swing/twist limits slightly.
                    float damped = 1.0f / (1.0f + spec.friction * 0.1f);
                    PhysicsBridge.setJointAngularLimits(jointId,
                        swing1 * damped, swing2 * damped, twist * damped);
                }
            }
        }
```

- [ ] **步骤 5：`updatePhysics` 加接触状态（最低点着地判定）**

在 `updatePhysics` 中 `setPosRaw` 之后追加：

```java
        boolean anyGrounded = false;
        for (long bodyId : boneBodyIds) {
            if (PhysicsBridge.isBodyGrounded(bodyId)) { anyGrounded = true; break; }
        }
        this.grounded = anyGrounded;
```

并在字段区声明：

```java
    private boolean grounded = false;
```

及 getter：

```java
    /** True if at least one bone touched the ground in the last physics step. */
    public boolean isGrounded() {
        return grounded;
    }
```

- [ ] **步骤 6：更新 import**

在文件头部 import 区加入：

```java
import java.util.HashMap;
import java.util.Map;
```

（`ArrayList`、`List`、`Stream`、`Path`、`Files`、`IOException` 已存在。）

- [ ] **步骤 7：验证编译**

运行：`gradlew.bat compileJava --rerun-tasks`
预期：BUILD SUCCESSFUL。

---

### 任务 9：Java 单元测试

**文件：**
- 创建：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/PhyParserTest.java`

- [ ] **步骤 1：编写文本 KV 解析测试**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhyParserTest {

    /**
     * Minimal .phy byte blob: 16-byte header + a single "solid" KV text section
     * that carries solid properties and a ragdollconstraint block. The binary
     * convex-hull section is omitted (surfaceSize 0) so parsing stays cheap.
     */
    private byte[] buildPhyWithConstraints() {
        String kv =
            "solid\n{\n" +
            "\t\"index\" \"0\"\n" +
            "\t\"name\" \"ValveBiped.Bip01_Head\"\n" +
            "\t\"parent\" \"3\"\n" +
            "\t\"mass\" \"4.50\"\n" +
            "\t\"surfaceprop\" \"flesh\"\n" +
            "\t\"damping\" \"0.10\"\n" +
            "\t\"rotdamping\" \"0.20\"\n" +
            "\t\"inertia\" \"1.0\"\n" +
            "\t\"volume\" \"2.0\"\n" +
            "}\n" +
            "solid\n{\n" +
            "\t\"index\" \"3\"\n" +
            "\t\"name\" \"ValveBiped.Bip01_Neck1\"\n" +
            "}\n" +
            "ragdollconstraint\n{\n" +
            "\t\"parent\" \"3\"\n" +
            "\t\"child\" \"0\"\n" +
            "\t\"xmin\" \"-1.2\"\n" +
            "\t\"xmax\" \"1.2\"\n" +
            "\t\"xfriction\" \"0.1\"\n" +
            "\t\"ymin\" \"-0.5\"\n" +
            "\t\"ymax\" \"0.5\"\n" +
            "\t\"yfriction\" \"0.2\"\n" +
            "\t\"zmin\" \"-0.3\"\n" +
            "\t\"zmax\" \"0.3\"\n" +
            "\t\"zfriction\" \"0.3\"\n" +
            "}\n" +
            "editparams\n{\n" +
            "\t\"rootname\" \"ValveBiped.Bip01\"\n" +
            "\t\"totalmass\" \"80.0\"\n" +
            "}\n";

        // Header: size, id("VPHY"), solidCount=2, checksum.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 + kv.getBytes(StandardCharsets.UTF_8).length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);                    // size (patched below)
        buf.put("VPHY".getBytes(StandardCharsets.UTF_8));
        buf.putInt(2);                    // solidCount
        buf.putInt(0x1234);               // checksum
        byte[] kvBytes = kv.getBytes(StandardCharsets.UTF_8);
        buf.put(kvBytes);
        // Fix up size field = bytes after the 16-byte header.
        buf.putInt(0, kvBytes.length + 16);
        return buf.array();
    }

    @Test
    void parsesSolidProperties() {
        PhyParser.ParsedPhy phy = PhyParser.parse(buildPhyWithConstraints());
        assertTrue(phy.valid);
        assertEquals(2, phy.solids.size());

        PhyParser.PhySolid head = phy.solids.get(0);
        assertEquals("ValveBiped.Bip01_Head", head.name);
        assertEquals(3, head.parent);
        assertEquals(4.5f, head.mass, 0.001f);
        assertEquals("flesh", head.surfaceprop);
        assertEquals(0.1f, head.damping, 0.001f);
        assertEquals(0.2f, head.rotdamping, 0.001f);
        assertEquals(1.0f, head.inertia, 0.001f);
        assertEquals(2.0f, head.volume, 0.001f);
    }

    @Test
    void parsesRagdollConstraints() {
        PhyParser.ParsedPhy phy = PhyParser.parse(buildPhyWithConstraints());
        assertEquals(1, phy.ragdollConstraints.size());

        PhyParser.PhyConstraint c = phy.ragdollConstraints.get(0);
        assertEquals(3, c.parentIndex);
        assertEquals(0, c.childIndex);
        assertEquals("ValveBiped.Bip01_Neck1", c.parentName);
        assertEquals("ValveBiped.Bip01_Head", c.childName);
        assertEquals(-1.2f, c.xmin, 0.001f);
        assertEquals(1.2f, c.xmax, 0.001f);
        assertEquals(0.1f, c.xfriction, 0.001f);
        assertEquals(0.3f, c.zfriction, 0.001f);
    }

    @Test
    void parsesEditParams() {
        PhyParser.ParsedPhy phy = PhyParser.parse(buildPhyWithConstraints());
        assertEquals("ValveBiped.Bip01", phy.rootName);
        assertEquals(80.0f, phy.totalMass, 0.001f);
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.PhyParserTest"`
预期：测试因新字段尚未解析而失败或编译失败（任务未完成时字段缺失）。

- [ ] **步骤 3：完成实现后运行测试确认通过**

运行：`gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.PhyParserTest"`
预期：3 个测试全 PASS。

---

### 任务 10：native 序列化契约测试

**文件：**
- 创建：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/PhyConstraintContractTest.java`

- [ ] **步骤 1：编写契约测试**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the extended native PHY serialization contract: Java round-trips the
 * byte stream produced by nativeParsePhySerialized and reads back the new
 * per-solid properties, ragdoll constraints, and editparams segments.
 */
class PhyConstraintContractTest {

    @Test
    void deserializeExtendedSegments() {
        // Build a serialized blob matching the C++ ByteWriter layout in
        // native_core_bridge.cpp nativeParsePhySerialized (post-extension).
        // Structure (LE):
        //   MAGIC(4)=0x574E5057, size, id(str), solidCount, checksum,
        //   solidCount, [per solid: index, name, hulls...], then:
        //   Segment A: propCount + [parent, mass, surfaceprop(str), damping,
        //     rotdamping, inertia, volume] * propCount
        //   Segment B: constraintCount + [parentIndex, childIndex, parentName,
        //     childName, 9 floats, 3 pivot floats] * constraintCount
        //   Segment C: rootName(str), totalMass(float)
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4096)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

        buf.putInt(0x574E5057);
        buf.putInt(100);
        putString(buf, "VPHY");
        buf.putInt(1);
        buf.putInt(0x99);

        // solids block (existing schema)
        buf.putInt(1);
        buf.putInt(0);                 // solid.index
        putString(buf, "ValveBiped.Bip01_Head");
        buf.putInt(0);                 // hull count

        // Segment A
        buf.putInt(1);
        buf.putInt(3);                 // parent
        buf.putFloat(4.5f);            // mass
        putString(buf, "flesh");       // surfaceprop
        buf.putFloat(0.1f);            // damping
        buf.putFloat(0.2f);            // rotdamping
        buf.putFloat(1.0f);            // inertia
        buf.putFloat(2.0f);            // volume

        // Segment B
        buf.putInt(1);
        buf.putInt(3);                 // parentIndex
        buf.putInt(0);                 // childIndex
        putString(buf, "ValveBiped.Bip01_Neck1");
        putString(buf, "ValveBiped.Bip01_Head");
        buf.putFloat(-1.2f); buf.putFloat(1.2f); buf.putFloat(0.1f);
        buf.putFloat(-0.5f); buf.putFloat(0.5f); buf.putFloat(0.2f);
        buf.putFloat(-0.3f); buf.putFloat(0.3f); buf.putFloat(0.3f);
        buf.putFloat(0.0f); buf.putFloat(1.5f); buf.putFloat(0.0f);

        // Segment C
        putString(buf, "ValveBiped.Bip01");
        buf.putFloat(80.0f);

        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, buf.position());

        PhyParser.ParsedPhy phy = WindowsNativeModelParserStrategy.deserializeParsedPhy(data);
        assertNotNull(phy);
        assertEquals(1, phy.solids.size());
        PhyParser.PhySolid solid = phy.solids.get(0);
        assertEquals(3, solid.parent);
        assertEquals(4.5f, solid.mass, 0.001f);
        assertEquals("flesh", solid.surfaceprop);
        assertEquals(0.2f, solid.rotdamping, 0.001f);

        assertEquals(1, phy.ragdollConstraints.size());
        PhyParser.PhyConstraint c = phy.ragdollConstraints.get(0);
        assertEquals("ValveBiped.Bip01_Head", c.childName);
        assertEquals(1.2f, c.xmax, 0.001f);
        assertEquals(1.5f, c.pivotY, 0.001f);

        assertEquals("ValveBiped.Bip01", phy.rootName);
        assertEquals(80.0f, phy.totalMass, 0.001f);
    }

    private static void putString(java.nio.ByteBuffer buf, String s) {
        buf.putShort((short) s.length());
        buf.put(s.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **步骤 2：运行测试**

运行：`gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.PhyConstraintContractTest"`
预期：PASS。

---

### 任务 11：全量验证 + 提交

- [ ] **步骤 1：native 全量编译**

运行：`cmake --build "src/main/native/build" --config Release -j`
预期：所有 target 编译成功。

- [ ] **步骤 2：Java 全量编译**

运行：`gradlew.bat compileJava --rerun-tasks`
预期：BUILD SUCCESSFUL。

- [ ] **步骤 3：全部测试**

运行：`gradlew.bat test`
预期：BUILD SUCCESSFUL，新增测试 PASS。

- [ ] **步骤 4：Commit**

```bash
git add docs/superpowers/specs/2026-08-10-source-parity-design.md
git add docs/superpowers/plans/2026-08-10-phy-real-ragdoll-plan.md
git add src/main/native/include/phy_parser.h src/main/native/src/phy_parser.cpp
git add src/main/native/src/native_core_bridge.cpp
git add src/main/native/include/physics_simulation.h src/main/native/src/physics_simulation.cpp
git add src/main/native/src/native_bridge.cpp
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/PhyParser.java
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/WindowsNativeModelParserStrategy.java
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/PhysicsBridge.java
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcRagdoll.java
git add src/test/java/transferstation/transferstation_whimsicalideas/client/model/PhyParserTest.java
git add src/test/java/transferstation/transferstation_whimsicalideas/client/model/PhyConstraintContractTest.java
git commit -m "feat: .phy ragdoll constraints + capsule env collision drive NpcRagdoll"
```
