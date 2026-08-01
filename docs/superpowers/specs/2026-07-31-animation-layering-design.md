# 动画层混合系统设计

**日期：** 2026-07-31
**状态：** 已批准（v2：新增 AI 骨骼控制通道）
**参考：** MC-MMD-rust（多层动画混合播放）、Source Engine 动画系统（pose 参数 / 程序化骨骼）

## 背景

当前 `AnimationProcessor.getBoneTransforms()` 一次只播放单一动画：`GameStateAnimationMapper` 映射一个状态 → 一个动画 → 全部骨骼受控。NPC 播放手势时（`NpcEntity.setAnimation(gesture)`）是**整体替换**动画，下半身走路也被掐掉。关键帧采样使用"最近帧"（无插值），动画切换无过渡。

目标：实现引擎级动画层混合（layer + weight + boneMask + crossfade + 帧间插值），接入 NPC 手势（上半身叠加、下半身保留基础动画），并提供 **AI 骨骼控制通道**（LLM 直接指定骨骼旋转，模型可做任意动作），保持渲染端零改动。

## 架构

```
base 层（GameStateAnimationMapper 驱动）    overlay 层（NPC 手势/AI 骨骼驱动）
     走路/待机动画                             挥手/点头 + AI 骨骼姿态
         │ delta                                  │ delta × boneMask[骨骼]
         └──────────────┬─────────────────────────┘
                    nlerp 混合（按权重）
                        │
            bindPose × 混合后 delta → 骨骼矩阵
                        │
            pose 覆盖通道（AI 骨骼指令，最后叠加）
                        │
                    world 变换 / morph
```

- **delta 分离**：每层独立计算相对 bind pose 的增量（平移向量 / 四元数 / 缩放向量），层间按权重混合后乘到 bind pose。不在 4×4 矩阵上直接叠加（矩阵无法正确插值）。
- **权重**：`perBoneWeight = layer.weight × boneMask[bone]`；多源混合时归一化后 nlerp。
- **旋转混合**：四元数 nlerp（快速、视觉可接受；slerp 留作未来优化）。
- **帧间插值**：采样时取目标帧相邻两个 keyframe，平移/缩放 lerp、旋转 nlerp；单帧动画退化为常数。
- **pose 覆盖通道**：AI 骨骼指令不进入层混合，而是作为独立通道在层混合之后、morph 之前逐骨骼叠加（与 Source 引擎 procedural bones 同思路），带平滑与时限。

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
4. **pose 覆盖通道**：`BonePoseController.applyPoses(entity, modelData, localTransforms, partialTicks)`（见下）
5. 世界变换（不变）、morph 叠加（不变）

新增私有方法：

```java
private static float[] sampleTrackAtTime(AnimationTrack track, float timeSec, float fps);
// 返回 {tx,ty,tz, qx,qy,qz,qw, sx,sy,sz}，帧间插值
private static float[] blendDeltas(float[] baseDelta, float[] overlayDelta, float weight);
// 平移/缩放 lerp，旋转 nlerp
```

关键帧采样从 `findKeyFrame`（最近帧）升级为 `interpolateKeyFrames`（相邻两帧插值）。`mapVmdBoneNameToMdl` 提升为包可见，供 BonePoseController 复用。

### 2b. 新文件 `client/animation/BonePoseController.java`（AI 骨骼控制通道）

每实体维护一张骨骼姿态表（WeakHashMap 外层，防泄漏）：

```java
class BonePose {
    String boneName;     // 经 mapVmdBoneNameToMdl 映射后的 MDL 骨名
    float[] targetRot;   // 目标欧拉角（弧度，绕 XYZ），clamp 后转四元数
    float progress;      // 0..1，指数平滑或按 duration 线性推进
    float duration;      // 保持时长（秒），之后淡出并清除
}

public static void applyPose(LivingEntity entity, String boneName,
                             float rx, float ry, float rz, float duration);
public static void clearEntity(LivingEntity entity);
public static void applyPoses(LivingEntity entity, SourceModelData modelData,
                              float[][] localTransforms, float partialTicks);
```

- **叠加方式**：`localTransforms[boneIdx] *= poseMat`（与 morph 相同的后乘方式，局部空间）。
- **平滑**：进入时按 0.15s 淡入（线性插值目标角度），到期后 0.3s 淡出清除。
- **防护（安全钳制）**：
  - 每轴角度 clamp 到 ±120°（2.09 rad）
  - 单次指令骨骼数量 ≤ 8
  - duration clamp 到 [0.5, 10] 秒
  - 未知骨骼名（映射后找不到）→ warn + 忽略
  - 同一实体同骨骼重复指令 → 覆盖旧值并重置计时

### 3. 接入 `NpcEntity.java` 与 `NpcChatHandler.java`

- `NpcEntity.playGesture(String gesture, float fadeTime)`：调用 `AnimationLayers.play(entity, "overlay", gesture, 1.0f, UPPER_BODY, fadeTime)`。
- `NpcEntity.applyBonePose(Map<String, float[]> boneRotations, float duration)`：逐骨骼调用 `BonePoseController.applyPose`（含 clamp 防护），返回实际接受的骨骼数。
- `NpcEntity.setAnimation()` 语义调整：保留为**显式整体动画覆盖**（编辑器/测试用），但 NPC 聊天手势改走 `playGesture`。
- `NpcChatHandler.processStructuredResponse()` 中 `npc.setAnimation(gesture)` → `npc.playGesture(gesture, 0.15f)`；新增 pose 解析（见 3b）。
- `GameStateAnimationMapper.getAnimationForEntity()`：NpcEntity 的 `getCurrentAnimation()` 显式覆盖保留（非手势场景），但**手势不再写入该字段**，避免掐掉 base 动画。

### 3b. AI 对话协议扩展（NpcChatHandler）

system prompt 追加 pose 用法说明。AI 回复 JSON 增加可选 `pose` 字段：

```json
{
  "reply": "看我的厉害！",
  "emotion": "happy",
  "gesture": "wave",
  "pose": {
    "bones": {
      "ValveBiped.Bip01_Head": [0, 0.5, 0],
      "ValveBiped.Bip01_R_UpperArm": [-0.8, 0, 0]
    },
    "duration": 2.0
  }
}
```

- 骨骼名支持 VMD 风格（`Bip01 Head`）或 MDL 风格（`ValveBiped.Bip01_Head`），统一走 `mapVmdBoneNameToMdl` 映射。
- 角度为弧度（绕 XYZ，Source 引擎惯例），引擎做 clamp。
- 服务端解析后，骨骼指令随 `ChatS2CPacket` 的 pose 字段发给目标玩家，客户端在 `NpcEntity` 上应用（与 emotion/gesture 同链路，保证多人一致）。
- 解析失败或字段非法：记录 warn，忽略 pose 部分，其余回复正常处理。

### 4. 渲染端

`MdlModelRenderer.render()` / `JavaModelRenderer.renderWithSkinning()` 调用链零改动。

## 数据流

```
AI 回复 → processStructuredResponse → npc.playGesture("wave", 0.15)   [overlay 层]
                                   → npc.applyBonePose({...}, 2.0)     [pose 通道]
    → ChatS2CPacket(gesture, pose) → 目标玩家客户端 → 本端 NpcEntity
渲染帧 → AnimationProcessor.getBoneTransforms(entity, modelData, partialTicks)
    → base delta + overlay delta×mask → nlerp → bindPose
    → BonePoseController.applyPoses（pose 矩阵后乘 localTransforms）→ world → morph
```

## 错误处理与边界

- overlay 层动画不存在：记录 warn，overlay 权重归零，行为等同现状。
- 骨骼名不匹配 mask（如 VMD 骨名 "Bip01 Head" vs MDL "ValveBiped.Bip01_Head"）：mask 匹配基于**映射后**骨名（复用 `mapVmdBoneNameToMdl`）。
- 非 loop 动画播完：停在最后一帧，权重保持；`stop` 时按 fadeTime 淡出。
- fade 进度推进放在渲染 tick（`AnimationLayers.tickFades`），由 `AnimationProcessor` 现有调用点驱动（`getBoneTransforms` 内或实体渲染前置调用）。
- pose 骨骼不存在 / 角度超限 / 数量超限：一律 clamp 或忽略并 warn，绝不抛异常中断渲染。
- pose 与手势同骨骼冲突：pose 通道最后叠加，视觉上 pose 胜出；两者独立计时，互不打断。

## 测试（`src/test/.../client/animation/`）

`AnimationLayersTest.java`：

1. nlerp 半权重：两个四元数（0° 与 90° 绕 Y 轴）以 0.5 混合 → 45°（容差 1°）。
2. boneMask：`BoneMaskType.UPPER_BODY` 对 "ValveBiped.Bip01_Spine4"/"ValveBiped.Bip01_Head" 为 true，对 "ValveBiped.Bip01_L_Foot" 为 false。
3. fade：fadeElapsed 0 / 0.5 / 1（fadeTime=1）时权重为 0 / 0.5 / 1。
4. 回归：无 overlay 时 `getBoneTransforms` 输出与旧路径一致（以 SourceModelData 测试数据验证矩阵接近）。
5. 帧间插值：两 keyframe（frame 0/10），t=5 时采样为中间值。

`BonePoseControllerTest.java`：

6. 角度 clamp：输入 3.0 rad → 输出 ≤ 2.09 rad。
7. 骨骼数超限：9 根骨骼 → 只接受 8 根。
8. 未知骨名：映射后不存在 → 忽略 + 返回接受数不增加。
9. 淡入进度：0 / 0.075s / 0.15s 时角度为 0 / 半 / 全。
10. duration 到期：模拟时间推进超过 duration + fadeOut → 姿态表清空。

## 明确不做（YAGNI）

- 通用骨骼蒙版编辑器 UI
- 玩家 IK / procedural bones 落地（pose 通道只做显式骨骼覆盖）
- MDL 原生序列动画的完整播放（数据已解析，留待未来任务）
- 多 overlay 层（本次只有 base + overlay 两层）
- pose 平移/缩放指令（本次仅旋转；平移指令直接忽略并 warn）

## 验收标准

1. NPC 走路时播放手势 → 下半身保持走路、上半身播放手势，无跳变。
2. 手势结束（或新手势开始）→ 平滑淡入淡出，不掐断 base 动画。
3. AI 回复携带 pose → NPC 对应骨骼转动到指定角度，按时限淡出；超限输入被钳制不崩溃。
4. 无手势无 pose 的 NPC 渲染与改造前完全一致。
5. 上述单元测试通过。
