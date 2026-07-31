# 动画层混合系统设计

**日期：** 2026-07-31
**状态：** 已批准
**参考：** MC-MMD-rust（多层动画混合播放）、Source Engine 动画系统

## 背景

当前 `AnimationProcessor.getBoneTransforms()` 一次只播放单一动画：`GameStateAnimationMapper` 映射一个状态 → 一个动画 → 全部骨骼受控。NPC 播放手势时（`NpcEntity.setAnimation(gesture)`）是**整体替换**动画，下半身走路也被掐掉。关键帧采样使用"最近帧"（无插值），动画切换无过渡。

目标：实现引擎级动画层混合（layer + weight + boneMask + crossfade + 帧间插值），接入 NPC 手势（上半身叠加、下半身保留基础动画），并保持渲染端零改动。

## 架构

```
base 层（GameStateAnimationMapper 驱动）    overlay 层（NPC 手势驱动）
     走路/待机动画                             挥手/点头/摇头
         │ delta                                  │ delta × boneMask[骨骼]
         └──────────────┬─────────────────────────┘
                    nlerp 混合（按权重）
                        │
            bindPose × 混合后 delta → 骨骼矩阵
```

- **delta 分离**：每层独立计算相对 bind pose 的增量（平移向量 / 四元数 / 缩放向量），层间按权重混合后乘到 bind pose。不在 4×4 矩阵上直接叠加（矩阵无法正确插值）。
- **权重**：`perBoneWeight = layer.weight × boneMask[bone]`；多源混合时归一化后 nlerp。
- **旋转混合**：四元数 nlerp（快速、视觉可接受；slerp 留作未来优化）。
- **帧间插值**：采样时取目标帧相邻两个 keyframe，平移/缩放 lerp、旋转 nlerp；单帧动画退化为常数。

## 组件

### 1. 新文件 `src/main/java/.../client/animation/AnimationLayers.java`

```java
public enum BoneMaskType {
    ALL,          // 无过滤
    UPPER_BODY,   // spine/chest/neck/head/arm/hand/clavicle 前缀匹配
    LOWER_BODY,   // hip/pelvis/leg/foot
    HEAD,         // neck/head
    ARMS          // arm/hand/clavicle/finger
}
```

```java
class LayerState {
    String layerId;            // "base" | "overlay"
    AnimationData anim;        // 当前动画
    AnimationData prevAnim;    // crossfade 过渡源动画
    float weight;              // 层权重 0..1
    float fadeTime;            // 过渡时长（秒）
    float fadeElapsed;         // 已过渡时间
    BoneMaskType mask;         // 骨骼蒙版
}
```

公开 API（均为 static）：

```java
public static void play(LivingEntity entity, String layerId, String animName,
                        float weight, BoneMaskType mask, float fadeTime);
public static void stop(LivingEntity entity, String layerId, float fadeTime);
public static void tickFades(LivingEntity entity, float deltaSec);
public static float getLayerWeight(LivingEntity entity, String layerId);
public static boolean isMaskedOut(LivingEntity entity, String layerId, String boneName);
public static void clearEntity(LivingEntity entity);   // 实体移除时清理
public static boolean hasActiveLayer(LivingEntity entity, String layerId);
```

内部用 `Map<LivingEntity, Map<String, LayerState>>`（WeakHashMap 外层防泄漏，与 `MdlModelRenderer.entityModelMap` 同模式）。

### 2. 改造 `AnimationProcessor.java`

`getBoneTransforms()` 保持签名不变，内部流程改为：

1. bind pose 初始化（不变）
2. base 层动画采样（VMD 优先，MDL 序列 fallback 不变）→ 每骨骼 delta
3. 若 overlay 层激活且骨骼未蒙版排除：采样 overlay 动画 → 按 `layerWeight × mask` 与 base delta 混合
4. 世界变换（不变）、morph 叠加（不变）

新增私有方法：

```java
private static float[] sampleTrackAtTime(AnimationTrack track, float timeSec, float fps);
// 返回 {tx,ty,tz, qx,qy,qz,qw, sx,sy,sz}，帧间插值
private static float[] blendDeltas(float[] baseDelta, float[] overlayDelta, float weight);
// 平移/缩放 lerp，旋转 nlerp
```

关键帧采样从 `findKeyFrame`（最近帧）升级为 `interpolateKeyFrames`（相邻两帧插值）。

### 3. 接入 `NpcEntity.java` 与 `NpcChatHandler.java`

- `NpcEntity.playGesture(String gesture, float fadeTime)`：调用 `AnimationLayers.play(entity, "overlay", gesture, 1.0f, UPPER_BODY, fadeTime)`。
- `NpcEntity.setAnimation()` 语义调整：保留为**显式整体动画覆盖**（编辑器/测试用），但 NPC 聊天手势改走 `playGesture`。
- `NpcChatHandler.processStructuredResponse()` 中 `npc.setAnimation(gesture)` → `npc.playGesture(gesture, 0.15f)`。
- `GameStateAnimationMapper.getAnimationForEntity()`：NpcEntity 的 `getCurrentAnimation()` 显式覆盖保留（非手势场景），但**手势不再写入该字段**，避免掐掉 base 动画。

### 4. 渲染端

`MdlModelRenderer.render()` / `JavaModelRenderer.renderWithSkinning()` 调用链零改动。

## 数据流

```
NPC 聊天 → processStructuredResponse → npc.playGesture("wave", 0.15)
    → AnimationLayers.play(overlay 层)
渲染帧 → AnimationProcessor.getBoneTransforms(entity, modelData, partialTicks)
    → base delta + overlay delta×mask → nlerp → bindPose → world → morph
```

## 错误处理与边界

- overlay 层动画不存在：记录 warn，overlay 权重归零，行为等同现状。
- 骨骼名不匹配 mask（如 VMD 骨名 "Bip01 Head" vs MDL "ValveBiped.Bip01_Head"）：mask 匹配基于**映射后**骨名（复用 `mapVmdBoneNameToMdl`）。
- 非 loop 动画播完：停在最后一帧，权重保持；`stop` 时按 fadeTime 淡出。
- fade 进度推进放在渲染 tick（`AnimationLayers.tickFades`），由 `AnimationProcessor` 现有调用点驱动（`getBoneTransforms` 内或实体渲染前置调用）。

## 测试（`src/test/.../client/animation/AnimationLayersTest.java`）

1. nlerp 半权重：两个四元数（0° 与 90° 绕 Y 轴）以 0.5 混合 → 45°（容差 1°）。
2. boneMask：`BoneMaskType.UPPER_BODY` 对 "ValveBiped.Bip01_Spine4"/"ValveBiped.Bip01_Head" 为 true，对 "ValveBiped.Bip01_L_Foot" 为 false。
3. fade：fadeElapsed 0 / 0.5 / 1（fadeTime=1）时权重为 0 / 0.5 / 1。
4. 回归：无 overlay 时 `getBoneTransforms` 输出与旧路径一致（以 SourceModelData 测试数据验证矩阵接近）。
5. 帧间插值：两 keyframe（frame 0/10），t=5 时采样为中间值。

## 明确不做（YAGNI）

- 通用骨骼蒙版编辑器 UI
- 玩家 IK / procedural bones 落地
- MDL 原生序列动画的完整播放（数据已解析，留待未来任务）
- 多 overlay 层（本次只有 base + overlay 两层）

## 验收标准

1. NPC 走路时播放手势 → 下半身保持走路、上半身播放手势，无跳变。
2. 手势结束（或新手势开始）→ 平滑淡入淡出，不掐断 base 动画。
3. 无手势 NPC 渲染与改造前完全一致。
4. 上述单元测试通过。
