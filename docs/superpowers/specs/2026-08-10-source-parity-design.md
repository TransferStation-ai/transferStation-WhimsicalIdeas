# 设计规格：Source 解析完整性与消费链路修复（第二轮）

- 日期：2026-08-10
- 状态：已获用户批准（第 1/2/3 节全部批准）
- 范围：#4 PCF 粒子全全套 + #7「只解析不消费」审计 + ragdoll_constraint 解码 + 全骨骼环境碰撞

## 1. 整体架构与执行组织

### 1.1 三块工作

| 工作 | 内容 | 交付物 |
|------|------|--------|
| PHY | `.phy` 的 `ragdollconstraint`/`solid` 全字段解码，C++→Java 全量传输，NpcRagdoll 接入真实约束 + 全骨骼环境碰撞 | 物理真实化 |
| 审计（#7） | `ParsedModel → SourceModelData` 链路逐字段审计，「只解析不消费」字段补全 | 审计报告 + 修复 |
| 粒子（#4） | PCF type id 全量映射、子发射器/碰撞、CP 运行时、曲线 ramp、MC 行为对齐 | 粒子框架补全 |

### 1.2 执行顺序与组织

- 顺序：**PHY → 审计 → 粒子**（主代理逐块验收）
- 组织：**phy 主代理亲自实现** + **审计/粒子两个 sensei 子代理并行**（文件所有权不重叠），主代理验收
- 审计 lane 只做**链路+清单**；JiggleBone 等实际消费逻辑留给主代理
- 每块完成后编译验证：`cmake`（native Release 增量）+ `gradlew.bat compileJava --rerun-tasks`

## 2. PHY 子系统

### 2.1 目标

`.phy` 的物理数据（solid 全字段 + ragdollconstraint 全字段 + editparams）由 C++ 解析、经 JNI 全量传输给 Java，`NpcRagdoll` 完全由 `.phy` 驱动（真实 pivot/limits/mass/inertia/damping），并启用全骨骼环境碰撞。

### 2.2 分层实现

**第 1 层 C++ 解析（`phy_parser.h/.cpp`）**
- 新增 `RagdollConstraint` 结构：`parent`/`child`（solid name，由 index 反查 name）、`xmin/xmax/xfriction`→swing1（绕父 X 轴）、`ymin/ymax/yfriction`→swing2（绕父 Y 轴）、`zmin/zmax/zfriction`→twist（绕父 Z 轴）、pivot（由子 solid 质心估算）
- 新增 `Solid` 全字段：`index`/`name`/`parent`/`mass`/`surfaceprop`/`damping`/`rotdamping`/`inertia`/`volume`
- 新增 `parseSolidBlocks()` + `parseRagdollConstraintBlocks()` + `parseEditParams()`（`rootname`/`totalmass`）
- `ParsedPhy` 增加 `List<RagdollConstraint> ragdollConstraints` + solid 全字段

**第 2 层 JNI 序列化（`native_core_bridge.cpp`）**
- `nativeParsePhySerialized` 的 ByteWriter 链路尾部追加三段新数据：solid 全字段数组、ragdollConstraints 数组、editparams；保持向后兼容（旧 Java 端可安全跳过未知尾部）

**第 3 层 Java（`PhyParser.java` + `WindowsNativeModelParserStrategy.deserializeParsedPhy`）**
- 同步加字段；native 反序列化与 Java fallback 双路径一致

**第 4 层消费（`NpcRagdoll.java`）**
- 新增 `getPhyConstraints()`，回退链：`ragdollConstraints` → `ragdoll→joints + heuristic` → 纯 heuristic
- `createConeTwistJointEx` 用真实 pivot/limits，`setJointAngularLimits` 应用摩擦
- 骨骼质量用 solid `mass`/`inertia` 覆盖 `getMass(boneDepths,...)` 启发式；阻尼用 `.phy` `damping`/`rotdamping`

### 2.3 全骨骼环境碰撞（用户增补，已批准）

**现状缺口（已查证）**：`NpcRagdoll.initPhysics` 用 `createRigidBody`（无形状，不进 `s_bodyShapeMap`），C++ 的 `resolveSphereMesh`/`resolveSphereGround`（physics_simulation.cpp:890-913）只遍历 `s_bodyShapeMap`——ragdoll 骨骼全部跳过，直接穿透方块。

**方案**：
- C++ 求解器：`resolveSphereMesh`/`resolveSphereGround` 从仅 `SPHERE`（physics_simulation.cpp:910 类型过滤）扩展到 `CAPSULE`（胶囊端点线段 vs 三角形最近点，复用摩擦/回弹/穿透修复）
- `NpcRagdoll`：每骨骼改用 `createRigidBodyWithShape(...CAPSULE...)`（半径按骨骼长度估算，垂直贴合骨骼），替代裸 `createRigidBody`
- 接触状态反馈：stepSimulation 后 C++ 暴露每骨骼接触标志/接触点/法线，Java 侧用于「最低点着地」判断（停摆判定、落点粒子）
- `refreshEnvironmentMesh` 已有（每 10 tick 重建，MESH_RADIUS=4），碰撞即刻生效

## 3. 审计 lane（#7「只解析不消费」）

### 3.1 问题本质

`MdlParser` 把大量字段写进 `ParsedModel`，但 `toSourceModelData` 转换时字段被丢弃，`SourceModelData` 无对应字段，渲染管线完全无法消费——解析了但不消费。

### 3.2 确凿证据（已查证）

- `axisInterpBones` / `quatInterpBones` / `jiggleBones` / `aimAtBones` / `sequenceIKRules` / `sequenceAutolayers` 只存在于 `ParsedModel`（MdlDataTypes.java:472-483），`SourceModelData` 无对应字段 → 转换即丢失
- `MdlParser` 内部 `MdlProceduralBones`、`MdlSequenceData` 已产出这些数据，但未接到转换层
- 消费点 `AnimationProcessor`（渲染管线）想用 JiggleBone 等却拿不到

### 3.3 交付物

1. **审计报告**（`docs/` 下）：逐字段「已解析 → 转换丢失 → 渲染不可用」完整链路清单，分级（丢失/半消费/正常）
2. **修复**：
   - `SourceModelData` 补齐缺失字段（jiggleBones、axis/quatInterpBones、IKRules、autolayers 等）
   - `MdlParser`/转换层把字段真正写入 `SourceModelData`
   - 链路+清单由审计 sensei 子代理做；JiggleBone 实际消费逻辑（AnimationProcessor 应用骨骼偏移）由主代理做

## 4. 粒子 lane（#4 PCF 全套）

### 4.1 现状

自研 `PcfParser`（KV）+ `ParticleEmitter`/`ParticleManager` + 8 个 renderer（Sprite/Model/Beam/Rope/Trail/Light/Decal）已成型；MC 集成在 `ParticleClientHandler`（RenderLevelStageEvent.AFTER_PARTICLES 渲染 + ClientTickEvent 20fps tick）+ `ParticleCommands`（/particle_spawn、/particle_list）。

### 4.2 范围

1. **type id 全量映射**：Source `particles.txt`/`particles_manifest.txt` id 全表 → 映射含附着力、颜色、自定义模型
2. **子发射器 + 碰撞**：`Children`/`child_particles` 真实现（发射时触发子发射器）；世界方块碰撞 → 消失/反弹/触发事件
3. **CP 运行时 + 曲线 ramp**：控制点真传入 emitter；`_ramp` 曲线用真实采样（interp 曲线段而非常量），支持 remap/时间轴
4. **forces 补全**：`m_vDirection` 等空解析字段补齐
5. **MC 行为对齐**：重力/色乘/发光对齐 MC 渲染语义（非翻译成 MC ParticleOptions）

### 4.3 文件所有权（不与审计 lane 重叠）

`client/particle/**` 全部归粒子 lane：`PcfParser.java`、`ParticleEmitter.java`、`ParticleManager.java`、`Particle.java`、`renderer/` 8 个 renderer、`integration/ParticleClientHandler.java`、`integration/ParticleCommands.java`。

## 5. 验收标准

- PHY：cmake Release 全 target 编译通过 + `gradlew.bat compileJava --rerun-tasks` BUILD SUCCESSFUL；NpcRagdoll 骨骼碰撞方块表面（带摩擦/回弹），约束使用 .phy 真实数据
- 审计：报告列出全部丢失字段；SourceModelData 补齐；渲染管线可读 JiggleBone 等
- 粒子：type id 全量映射表；子发射器/碰撞/CP/曲线 ramp 实现；MC 行为对齐

## 6. 参考文件

- `src/main/native/include/phy_parser.h` / `src/main/native/src/phy_parser.cpp`
- `src/main/native/src/native_core_bridge.cpp`（`nativeParsePhySerialized`）
- `src/main/native/src/physics_simulation.cpp`（stepSimulation / resolveSphereMesh / resolveSphereGround / resolveSphereSphere）
- `src/main/java/.../client/model/PhyParser.java`、`WindowsNativeModelParserStrategy.java`
- `src/main/java/.../client/model/NpcRagdoll.java`
- `src/main/java/.../client/model/MdlDataTypes.java`（ParsedModel 472-483）、`SourceModelData.java`
- `src/main/java/.../client/animation/AnimationProcessor.java`
- `src/main/java/.../client/particle/**`（PcfParser、ParticleEmitter、ParticleManager、Particle、renderer/、integration/）
