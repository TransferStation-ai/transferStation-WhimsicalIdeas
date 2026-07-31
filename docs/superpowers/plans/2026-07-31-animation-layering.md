# 动画层混合 + AI 骨骼控制 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现引擎级动画层混合（base + overlay 层、boneMask、crossfade、帧间插值），NPC 手势走 overlay 层不掐断基础动画；并提供 AI 骨骼控制通道（LLM 回复 JSON 指定骨骼旋转，带安全钳制）。

**架构：** 三个协同组件：(1) 新 `AnimationLayers` 管理 per-entity 层状态（play/stop/fade，纯函数可测）；(2) `AnimationProcessor.getBoneTransforms` 重构为"采样 delta 分量 → 按权重 nlerp 混合 → 乘 bindPose"；(3) 增强 `NpcBoneController`（已有 setBoneRotation/程序化手势），加 AI pose 指令（clamp + 时限 + 淡出）。AI 协议经 `ChatS2CPacket` 扩展 pose 字段（骨骼名→欧拉角弧度 + duration），客户端应用。

**技术栈：** Forge 1.20.1 / Java 17 / JOML（Matrix4f、Quaternionf）/ JUnit 5.10.0（junit-jupiter，纯 JVM 测试，无 MC 运行时）

**规格：** `docs/superpowers/specs/2026-07-31-animation-layering-design.md`（v2）

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayers.java` | 创建 | 层状态表（WeakHashMap<LivingEntity, Map<layerId, LayerState>>）、BoneMaskType、play/stop/fadeWeight/isMaskedOut/tickFades |
| `src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationProcessor.java` | 修改 | sampleTrackAtTime（帧间插值）、blendDeltas（nlerp）、getBoneTransforms 混合 base+overlay |
| `src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneController.java` | 修改 | clampAngle、registerBones、applyAiPose（时限+淡出）、updateBones 过期清理 |
| `src/main/java/transferstation/transferstation_whimsicalideas/client/renderer/NpcEntityRenderer.java` | 修改 | 渲染前注册实体骨骼列表（registerBones） |
| `src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcEntity.java` | 修改 | playGesture（overlay VMD 优先 → 程序化手势 fallback）、applyBonePose（≤8 骨骼）、handleGesture 改造、remove 清理 |
| `src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandler.java` | 修改 | system prompt 加 pose 说明、parsePoseBones 解析、processStructuredResponse 打包 |
| `src/main/java/transferstation/transferstation_whimsicalideas/network/ChatS2CPacket.java` | 修改 | record 加 pose（Map<String,float[]>）+ poseDuration，encode/decode |
| `src/test/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayersTest.java` | 创建 | boneMask、fade 权重、blendDeltas、sampleTrackAtTime 插值 |
| `src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneControllerTest.java` | 创建 | clamp、骨骼映射、过期淡出清理 |
| `src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandlerTest.java` | 创建 | parsePoseBones 合法/非法/超限 |

**关键决策（与规格 v2 的差异，均已在本计划内落地）：**
- AI pose 通道**不新建 BonePoseController**，增强现有 `NpcBoneController`（渲染器 `NpcEntityRenderer:122` 已有 `localTransforms.mul(override)` 叠加点，渲染端零改动）。
- overlay 手势在 VMD 缺失时 fallback 到 NpcBoneController 已有程序化手势（playWaveAnimation 等，`NpcBoneController.java:161-211`，当前是死代码）。
- `mapVmdBoneNameToMdl` 保持 private（无需提升，NpcBoneController 用自己的 resolveBoneName）。
- 所有可测逻辑抽成**不依赖 MC 运行时**的 static 纯函数；依赖 LivingEntity 的路径只在 getBoneTransforms/实体方法内部，不单测。

---

### 任务 1：AnimationLayers 层系统（引擎核心）

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayers.java`
- 测试：`src/test/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayersTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
// src/test/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayersTest.java
package transferstation.transferstation_whimsicalideas.client.animation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationLayersTest {

    // --- BoneMaskType 匹配 ---
    @Test
    void upperBodyMatchesTorsoAndHead() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_Spine4"));
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_Head"));
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_R_UpperArm"));
    }

    @Test
    void upperBodyExcludesLegs() {
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_L_Foot"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_Pelvis"));
    }

    @Test
    void lowerBodyMatchesLegsOnly() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.LOWER_BODY, "ValveBiped.Bip01_L_Thigh"));
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.LOWER_BODY, "ValveBiped.Bip01_R_Foot"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.LOWER_BODY, "ValveBiped.Bip01_Head"));
    }

    @Test
    void headMaskMatchesNeckAndHead() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.HEAD, "ValveBiped.Bip01_Head"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.HEAD, "ValveBiped.Bip01_R_UpperArm"));
    }

    @Test
    void armsMaskMatchesArmsAndHands() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.ARMS, "ValveBiped.Bip01_L_Hand"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.ARMS, "ValveBiped.Bip01_Head"));
    }

    @Test
    void allMaskMatchesEverything() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.ALL, "anything"));
    }

    // --- fade 权重 ---
    @Test
    void fadeInWeightRampsZeroToOne() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadeTime = 1.0f;
        s.fadeElapsed = 0.5f;
        assertEquals(0.5f, AnimationLayers.fadeWeight(s), 0.001f);
        s.fadeElapsed = 2.0f;
        assertEquals(1.0f, AnimationLayers.fadeWeight(s), 0.001f);
    }

    @Test
    void fadeOutWeightRampsOneToZero() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadeTime = 1.0f;
        s.fadingOut = true;
        s.fadeElapsed = 0.25f;
        assertEquals(0.75f, AnimationLayers.fadeWeight(s), 0.001f);
        s.fadeElapsed = 5.0f;
        assertEquals(0.0f, AnimationLayers.fadeWeight(s), 0.001f);
    }

    @Test
    void zeroFadeTimeMeansFullWeight() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadeTime = 0;
        assertEquals(1.0f, AnimationLayers.fadeWeight(s), 0.001f);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.animation.AnimationLayersTest"`
预期：FAIL，编译错误 "cannot find symbol: class AnimationLayers"

- [ ] **步骤 3：实现 AnimationLayers**

```java
// src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayers.java
package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 动画层混合系统。
 * base 层由 GameStateAnimationMapper 驱动（走 AnimationProcessor 原路径），
 * overlay 层承载手势/表情动画。渲染端在 AnimationProcessor.getBoneTransforms
 * 内按 per-bone 权重（layer.weight × boneMask）nlerp 混合两层 delta。
 */
public class AnimationLayers {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String BASE = "base";
    public static final String OVERLAY = "overlay";

    /** 所有可测方法为 static 纯函数，不依赖 MC 运行时（WeakHashMap<LivingEntity,..> 只在渲染路径使用）。 */
    private static final Map<LivingEntity, Map<String, LayerState>> layerStates =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AnimationLayers() {
    }

    public enum BoneMaskType {
        ALL,
        UPPER_BODY,
        LOWER_BODY,
        HEAD,
        ARMS
    }

    /** 骨骼蒙版匹配（基于映射后的 MDL 骨名，如 "ValveBiped.Bip01_Head"）。 */
    public static boolean matches(BoneMaskType mask, String mdlBoneName) {
        if (mask == null || mask == BoneMaskType.ALL || mdlBoneName == null) return true;
        String name = mdlBoneName.toLowerCase(Locale.ROOT);
        switch (mask) {
            case UPPER_BODY:
                return containsAny(name, "spine", "chest", "neck", "head", "arm", "hand", "clavicle", "finger");
            case LOWER_BODY:
                return containsAny(name, "hip", "pelvis", "leg", "foot", "thigh");
            case HEAD:
                return containsAny(name, "neck", "head");
            case ARMS:
                return containsAny(name, "arm", "hand", "clavicle", "finger");
            default:
                return true;
        }
    }

    private static boolean containsAny(String name, String... keys) {
        for (String k : keys) {
            if (name.contains(k)) return true;
        }
        return false;
    }

    /** 单层播放状态。 */
    public static class LayerState {
        public String layerId;
        public AnimationData anim;
        public float weight = 1.0f;
        public float fadeTime = 0.2f;
        public float fadeElapsed = 0;
        public float lastElapsedSec = -1;
        public BoneMaskType mask = BoneMaskType.ALL;
        public boolean fadingOut = false;

        public LayerState(String layerId) {
            this.layerId = layerId;
        }
    }

    /** 当前层权重（含 fade）：淡入 0→1，淡出 1→0。fadeTime<=0 视为瞬切。 */
    public static float fadeWeight(LayerState s) {
        if (s == null || s.fadeTime <= 0) return 1.0f;
        float t = s.fadeElapsed / s.fadeTime;
        if (s.fadingOut) {
            return Math.max(0.0f, 1.0f - t);
        }
        return Math.min(1.0f, t);
    }

    /** 播放动画到指定层（覆盖该层当前动画，从 0 重新淡入）。 */
    public static void play(LivingEntity entity, String layerId, String animName,
                            float weight, BoneMaskType mask, float fadeTime) {
        if (entity == null || layerId == null) return;
        AnimationData anim = AnimationProcessor.getAnimation(animName);
        if (anim == null) {
            LOGGER.warn("[AnimationLayers] Unknown animation '{}' for layer '{}'", animName, layerId);
            return;
        }
        Map<String, LayerState> layers = layerStates.computeIfAbsent(entity, k -> new HashMap<>());
        LayerState s = layers.get(layerId);
        if (s == null) {
            s = new LayerState(layerId);
            layers.put(layerId, s);
        }
        s.anim = anim;
        s.weight = Math.max(0.0f, Math.min(1.0f, weight));
        s.mask = mask != null ? mask : BoneMaskType.ALL;
        s.fadingOut = false;
        s.fadeElapsed = 0;
        s.fadeTime = Math.max(0, fadeTime);
        s.lastElapsedSec = -1;
    }

    /** 停止指定层（按 fadeTime 淡出后由 tickFades 清理）。 */
    public static void stop(LivingEntity entity, String layerId, float fadeTime) {
        Map<String, LayerState> layers = layerStates.get(entity);
        if (layers == null) return;
        LayerState s = layers.get(layerId);
        if (s == null) return;
        s.fadingOut = true;
        s.fadeElapsed = 0;
        s.fadeTime = Math.max(0, fadeTime);
    }

    /** 推进 fade 计时（渲染帧调用：currentElapsedSec = (tickCount + partialTicks)/20）。 */
    public static void tickFades(LivingEntity entity, float currentElapsedSec) {
        Map<String, LayerState> layers = layerStates.get(entity);
        if (layers == null) return;
        layers.entrySet().removeIf(e -> {
            LayerState s = e.getValue();
            if (s.lastElapsedSec >= 0) {
                s.fadeElapsed += Math.max(0, currentElapsedSec - s.lastElapsedSec);
            }
            s.lastElapsedSec = currentElapsedSec;
            return s.fadingOut && fadeWeight(s) <= 0;
        });
    }

    /** 该层是否屏蔽此骨骼（无层状态 = 层不存在 = 不参与混合）。 */
    public static boolean isMaskedOut(LivingEntity entity, String layerId, String mdlBoneName) {
        Map<String, LayerState> layers = layerStates.get(entity);
        if (layers == null) return true;
        LayerState s = layers.get(layerId);
        if (s == null || s.anim == null || fadeWeight(s) <= 0) return true;
        return !matches(s.mask, mdlBoneName);
    }

    /** 获取参与混合的 overlay 层状态（淡出中权重为 0 时返回 null）。 */
    public static LayerState getActiveOverlay(LivingEntity entity, String layerId) {
        Map<String, LayerState> layers = layerStates.get(entity);
        if (layers == null) return null;
        LayerState s = layers.get(layerId);
        if (s == null || s.anim == null || fadeWeight(s) <= 0) return null;
        return s;
    }

    public static void clearEntity(LivingEntity entity) {
        layerStates.remove(entity);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.animation.AnimationLayersTest"`
预期：PASS（8 个测试）

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayers.java src/test/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayersTest.java
git commit -m "feat: add animation layer system with bone masks and fades"
```

---

### 任务 2：帧间插值 + delta 混合（AnimationProcessor 重构）

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationProcessor.java`（applyAnimationDelta 重构、getBoneTransforms 加 overlay 混合）
- 测试：`src/test/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayersTest.java`（追加）

- [ ] **步骤 1：编写失败的测试（追加到 AnimationLayersTest）**

```java
    // --- delta 采样与混合（纯函数） ---
    @Test
    void sampleTrackAtTimeInterpolatesMidFrame() {
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("bone");
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{0, 0, 0},
                new float[]{0, 0, 0, 1}, null));
        track.addKeyFrame(new AnimationData.KeyFrame(10, new float[]{1, 0, 0},
                new float[]{0, (float) Math.sin(Math.PI / 4), 0, (float) Math.cos(Math.PI / 4)}, null));

        float[] d = AnimationProcessor.sampleTrackAtTime(track, 5.0f);
        // 布局 {tx,ty,tz, qx,qy,qz,qw, sx,sy,sz}
        assertEquals(0.5f, d[0], 0.001f); // 平移 lerp 中点
        assertEquals(0.0f, d[3], 0.001f);
        assertEquals(0.3826834f, d[4], 0.01f); // sin(22.5°)，nlerp 45° 半程
        assertEquals(0.9238795f, d[6], 0.01f); // cos(22.5°)
    }

    @Test
    void sampleTrackAtTimeClampsOutsideRange() {
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("bone");
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{0, 0, 0},
                new float[]{0, 0, 0, 1}, null));
        track.addKeyFrame(new AnimationData.KeyFrame(10, new float[]{1, 0, 0},
                new float[]{0, 0, 0, 1}, null));

        float[] before = AnimationProcessor.sampleTrackAtTime(track, -3.0f);
        float[] after = AnimationProcessor.sampleTrackAtTime(track, 25.0f);
        assertEquals(0.0f, before[0], 0.001f);
        assertEquals(1.0f, after[0], 0.001f);
    }

    @Test
    void sampleTrackAtTimeSingleKeyFrameIsConstant() {
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("bone");
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{7, 0, 0},
                new float[]{0, 0, 0, 1}, null));
        float[] d = AnimationProcessor.sampleTrackAtTime(track, 42.0f);
        assertEquals(7.0f, d[0], 0.001f);
    }

    @Test
    void blendDeltasHalfWeightGivesFortyFiveDegrees() {
        // base = 0° 绕 Y，overlay = 90° 绕 Y，权重 0.5 → 45°
        float[] base = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] overlay = {1, 0, 0, 0, (float) Math.sin(Math.PI / 4), 0, (float) Math.cos(Math.PI / 4), 1, 1, 1};
        float[] out = AnimationProcessor.blendDeltas(base, overlay, 0.5f);
        assertEquals(0.5f, out[0], 0.001f); // 平移 lerp
        assertEquals((float) Math.sin(Math.PI / 8), out[4], 0.01f); // sin(22.5°)
        assertEquals((float) Math.cos(Math.PI / 8), out[6], 0.01f);
    }

    @Test
    void blendDeltasZeroWeightReturnsBase() {
        float[] base = {3, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] overlay = {9, 0, 0, 0, 0, 1, 0, 1, 1, 1};
        float[] out = AnimationProcessor.blendDeltas(base, overlay, 0.0f);
        assertArrayEquals(base, out, 0.001f);
    }

    @Test
    void blendDeltasClampsWeightAboveOne() {
        float[] base = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] overlay = {4, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] out = AnimationProcessor.blendDeltas(base, overlay, 5.0f);
        assertEquals(4.0f, out[0], 0.001f);
    }

    @Test
    void nlerpHandlesOppositeQuaternions() {
        float[] a = {0, 0, 0, 1};
        float[] b = {0, 0, 0, -1}; // 相反方向 → 应翻转后归一化
        float[] out = AnimationProcessor.nlerpQuat(a, b, 0.5f);
        assertEquals(1.0f, out[3], 0.01f);
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.animation.AnimationLayersTest"`
预期：FAIL，编译错误 "cannot find symbol: method sampleTrackAtTime / blendDeltas / nlerpQuat"

- [ ] **步骤 3：重构 AnimationProcessor**

替换 `applyAnimationDelta`（旧代码 `AnimationProcessor.java:402-440` 直接乘矩阵），新增采样/混合纯函数。在类内新增常量与方法：

```java
    /** delta 分量布局：{tx,ty,tz, qx,qy,qz,qw, sx,sy,sz} */
    static final int DELTA_LEN = 10;

    /**
     * 按浮点帧号采样轨道，相邻两 keyframe 间插值：
     * 平移/缩放 lerp，旋转 nlerp。f 越界时 clamp 到首尾帧。
     * 返回 DELTA_LEN 数组；无关键帧返回 null。
     */
    static float[] sampleTrackAtTime(AnimationData.AnimationTrack track, float frameFloat) {
        if (track == null || track.keyFrames == null || track.keyFrames.isEmpty()) return null;
        List<AnimationData.KeyFrame> kfs = track.keyFrames;
        // 关键帧按 frame 升序（VMD 解析保证，防御性拷贝排序避免改坏原始数据）
        if (kfs.size() > 1 && kfs.get(0).frame > kfs.get(1).frame) {
            kfs = new ArrayList<>(kfs);
            kfs.sort((a, b) -> Integer.compare(a.frame, b.frame));
        }

        AnimationData.KeyFrame first = kfs.get(0);
        AnimationData.KeyFrame last = kfs.get(kfs.size() - 1);
        if (frameFloat <= first.frame) return toDelta(first);
        if (frameFloat >= last.frame) return toDelta(last);

        for (int i = 0; i < kfs.size() - 1; i++) {
            AnimationData.KeyFrame a = kfs.get(i);
            AnimationData.KeyFrame b = kfs.get(i + 1);
            if (frameFloat >= a.frame && frameFloat <= b.frame) {
                float t = (b.frame > a.frame) ? (frameFloat - a.frame) / (b.frame - a.frame) : 0;
                float[] da = toDelta(a);
                float[] db = toDelta(b);
                return blendDeltas(da, db, t);
            }
        }
        return toDelta(last);
    }

    private static float[] toDelta(AnimationData.KeyFrame kf) {
        float[] d = new float[DELTA_LEN];
        if (kf.translation != null) {
            d[0] = kf.translation[0]; d[1] = kf.translation[1]; d[2] = kf.translation[2];
        }
        if (kf.rotation != null) {
            d[3] = kf.rotation[0]; d[4] = kf.rotation[1]; d[5] = kf.rotation[2]; d[6] = kf.rotation[3];
        } else {
            d[6] = 1.0f; // 无旋转 = 恒等四元数
        }
        if (kf.scale != null) {
            d[7] = kf.scale[0]; d[8] = kf.scale[1]; d[9] = kf.scale[2];
        } else {
            d[7] = 1.0f; d[8] = 1.0f; d[9] = 1.0f;
        }
        return d;
    }

    /** 四元数 nlerp（自动处理相反方向），返回归一化结果。 */
    static float[] nlerpQuat(float[] a, float[] b, float t) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float[] b2 = (dot < 0) ? new float[]{-b[0], -b[1], -b[2], -b[3]} : b;
        float[] out = new float[4];
        for (int i = 0; i < 4; i++) {
            out[i] = a[i] + (b2[i] - a[i]) * t;
        }
        float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
        if (len > 1e-6f) {
            for (int i = 0; i < 4; i++) out[i] /= len;
        } else {
            out[3] = 1.0f;
        }
        return out;
    }

    /**
     * 按权重混合 base/overlay delta：平移/缩放 lerp，旋转 nlerp。
     * weight clamp 到 [0,1]。
     */
    static float[] blendDeltas(float[] base, float[] overlay, float weight) {
        float t = Math.max(0.0f, Math.min(1.0f, weight));
        float[] out = new float[DELTA_LEN];
        for (int i = 0; i < 3; i++) out[i] = base[i] + (overlay[i] - base[i]) * t;   // 平移
        float[] rot = nlerpQuat(new float[]{base[3], base[4], base[5], base[6]},
                                new float[]{overlay[3], overlay[4], overlay[5], overlay[6]}, t);
        out[3] = rot[0]; out[4] = rot[1]; out[5] = rot[2]; out[6] = rot[3];
        for (int i = 7; i < DELTA_LEN; i++) out[i] = base[i] + (overlay[i] - base[i]) * t; // 缩放
        return out;
    }
```

替换 `applyAnimationDelta`（`AnimationProcessor.java:402-440`）为 delta 采样版本，并把 `getBoneTransforms` 的步骤 3/4 改为"采样 → 混合 → 乘 bindPose"：

```java
    /**
     * 采样单层动画，返回 boneIndex → delta 分量 {tx,ty,tz,qx,qy,qz,qw,sx,sy,sz}。
     * 动画 delta 相对 bind pose（VMD 惯例）。
     */
    private static Map<Integer, float[]> sampleAnimationDeltas(LivingEntity entity, AnimationData anim,
                                                                SourceModelData modelData, float partialTicks) {
        Map<Integer, float[]> deltas = new HashMap<>();
        if (anim == null || anim.tracks.isEmpty()) return deltas;

        float elapsedSec = (entity.tickCount + partialTicks) / 20.0f;
        float frameFloat = elapsedSec * anim.fps;
        if (anim.loop && anim.frameCount > 0) {
            frameFloat = frameFloat % anim.frameCount;
        } else if (anim.frameCount > 0) {
            frameFloat = Math.min(frameFloat, anim.frameCount - 1);
        }

        for (AnimationData.AnimationTrack track : anim.tracks) {
            String mdlBoneName = mapVmdBoneNameToMdl(track.boneName, modelData);
            int boneIndex = findBoneIndex(modelData, mdlBoneName);
            if (boneIndex < 0) continue;
            float[] d = sampleTrackAtTime(track, frameFloat);
            if (d != null) deltas.put(boneIndex, d);
        }
        return deltas;
    }

    /** delta 分量 → 变换矩阵（T * R * S）。 */
    private static org.joml.Matrix4f deltaToMatrix(float[] d) {
        org.joml.Matrix4f m = new org.joml.Matrix4f();
        m.identity();
        m.translate(d[0], d[1], d[2]);
        float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, d[6]))));
        float s = (float) Math.sqrt(Math.max(0.0f, 1.0f - d[6] * d[6]));
        if (s > 0.001f) {
            float invS = 1.0f / s;
            m.rotate(angle, d[3] * invS, d[4] * invS, d[5] * invS);
        }
        m.scale(d[7], d[8], d[9]);
        return m;
    }
```

`getBoneTransforms` 的步骤 2-4 替换为：

```java
        // 2. Try to get VMD animation
        String animName = getActiveAnimationName(entity);
        AnimationData anim = getAnimation(animName);

        // 3. If no VMD animation, try MDL's built-in sequence animation data
        if (anim == null || anim.tracks.isEmpty()) {
            anim = getMdlSequenceAnimation(entity, modelData, animName);
        }

        // 4. Sample base layer deltas (with frame interpolation)
        Map<Integer, float[]> deltas = sampleAnimationDeltas(entity, anim, modelData, partialTicks);

        // 4b. Overlay layer: blend gesture deltas on top (per-bone weight = layerWeight * boneMask)
        AnimationLayers.tickFades(entity, (entity.tickCount + partialTicks) / 20.0f);
        AnimationLayers.LayerState overlay = AnimationLayers.getActiveOverlay(entity, AnimationLayers.OVERLAY);
        if (overlay != null) {
            float layerWeight = AnimationLayers.fadeWeight(overlay) * overlay.weight;
            Map<Integer, float[]> overlayDeltas = sampleAnimationDeltas(entity, overlay.anim, modelData, partialTicks);
            for (Map.Entry<Integer, float[]> entry : overlayDeltas.entrySet()) {
                int boneIdx = entry.getKey();
                String boneName = modelData.bones.get(boneIdx).name;
                if (AnimationLayers.isMaskedOut(entity, AnimationLayers.OVERLAY, boneName)) continue;
                float[] base = deltas.get(boneIdx);
                if (base == null) {
                    deltas.put(boneIdx, blendDeltas(toIdentityDelta(), entry.getValue(), layerWeight));
                } else {
                    deltas.put(boneIdx, blendDeltas(base, entry.getValue(), layerWeight));
                }
            }
        }

        // 4c. Apply mixed deltas on top of bind pose
        for (Map.Entry<Integer, float[]> entry : deltas.entrySet()) {
            org.joml.Matrix4f bindPose = new org.joml.Matrix4f();
            bindPose.set(localTransforms[entry.getKey()]);
            bindPose.mul(deltaToMatrix(entry.getValue()));
            bindPose.get(localTransforms[entry.getKey()]);
        }
```

（原步骤 5 world 变换、6 morph 保持不变，编号顺延为 5、6。新增辅助方法：）

```java
    private static float[] toIdentityDelta() {
        return new float[]{0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
    }
```

删除旧的 `applyAnimationDelta` 方法（`AnimationProcessor.java:402-440`）与 `findKeyFrame`（`:501-513`，不再使用）。`applyAnimation`/`applyTrackAnimation`（`:122-168`，PoseStack 旧路径）保留不动（编辑器预览仍用）。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.animation.AnimationLayersTest"`
预期：PASS（15 个测试：8 原 + 7 新）

- [ ] **步骤 5：编译验证主代码**

运行：`.\gradlew.bat compileJava`
预期：BUILD SUCCESSFUL（无编译错误）

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationProcessor.java src/test/java/transferstation/transferstation_whimsicalideas/client/animation/AnimationLayersTest.java
git commit -m "feat: frame interpolation and delta blending in AnimationProcessor"
```

---

### 任务 3：NpcBoneController 增强（AI pose 通道）

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneController.java`
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/renderer/NpcEntityRenderer.java:107-118`（registerBones）
- 测试：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneControllerTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
// src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneControllerTest.java
package transferstation.transferstation_whimsicalideas.client.model;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpcBoneControllerTest {

    private final String entityId = "test-entity-1";

    @AfterEach
    void cleanup() {
        NpcBoneController.clearEntity(entityId);
    }

    private List<SourceModelData.BoneInfo> valveBipedBones() {
        List<SourceModelData.BoneInfo> bones = new ArrayList<>();
        bones.add(bone("ValveBiped.Bip01_Pelvis", -1));
        bones.add(bone("ValveBiped.Bip01_Spine4", 0));
        bones.add(bone("ValveBiped.Bip01_Head", 1));
        bones.add(bone("ValveBiped.Bip01_R_UpperArm", 1));
        return bones;
    }

    private SourceModelData.BoneInfo bone(String name, int parent) {
        SourceModelData.BoneInfo b = new SourceModelData.BoneInfo();
        b.name = name;
        b.parent = parent;
        return b;
    }

    @Test
    void clampAngleLimitsTo120Degrees() {
        assertEquals(2.094395f, NpcBoneController.clampAngle(3.0f), 0.001f);
        assertEquals(-2.094395f, NpcBoneController.clampAngle(-3.0f), 0.001f);
        assertEquals(0.5f, NpcBoneController.clampAngle(0.5f), 0.001f);
    }

    @Test
    void applyAiPoseMapsFriendlyNameToMdlBone() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        int accepted = NpcBoneController.applyAiPose(entityId, "Bip01 Head", 0, 0.5f, 0, 1.0f, 0);
        assertEquals(1, accepted);
        Matrix4f m = NpcBoneController.getBoneTransform(entityId, "ValveBiped.Bip01_Head");
        Matrix4f identity = new Matrix4f().identity();
        assertFalse(m.equals(identity, 0.001f), "head bone should be rotated");
    }

    @Test
    void applyAiPoseRejectsUnknownBone() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        int accepted = NpcBoneController.applyAiPose(entityId, "nonexistent", 0, 0.5f, 0, 1.0f, 0);
        assertEquals(0, accepted);
    }

    @Test
    void applyAiPoseClampsExtremeAngles() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        NpcBoneController.applyAiPose(entityId, "head", 10.0f, 0, 0, 1.0f, 0);
        // 目标被 clamp 到 ±120°，插值推进后 rotation.x 不可能超过 2.1 rad
        NpcBoneController.updateBones(1);
        NpcBoneController.BoneState state = NpcBoneController.getBoneState(entityId, "ValveBiped.Bip01_Head");
        assertNotNull(state);
        assertTrue(state.targetRotation.x <= 2.1f);
    }

    @Test
    void poseExpiresAndFadesOut() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        NpcBoneController.applyAiPose(entityId, "head", 0, 1.0f, 0, 1.0f, 0); // duration 1s = 20 ticks
        NpcBoneController.updateBones(20); // 到期：停止目标插值，开始回零
        NpcBoneController.BoneState state = NpcBoneController.getBoneState(entityId, "ValveBiped.Bip01_Head");
        assertNotNull(state);
        assertNull(state.targetRotation, "expired pose should stop tracking target");
        for (int t = 21; t <= 30; t++) {
            NpcBoneController.updateBones(t); // 淡出 0.3s = 6 ticks
        }
        assertNull(NpcBoneController.getBoneState(entityId, "ValveBiped.Bip01_Head"),
                "faded-out pose should be removed");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.NpcBoneControllerTest"`
预期：FAIL，编译错误（clampAngle/registerBones/applyAiPose/getBoneState 不存在）

- [ ] **步骤 3：实现 NpcBoneController 增强**

在 `NpcBoneController.java` 类内新增：

```java
    /** AI 骨骼指令钳制：每轴 ±120°（2.094 rad） */
    private static final float MAX_POSE_ANGLE = (float) (120.0 * Math.PI / 180.0);
    /** AI pose 到期后的淡出时长（秒） */
    private static final float POSE_FADE_OUT_SEC = 0.3f;

    /** 每实体最近一次渲染注册的骨骼列表（供 AI 骨名解析） */
    private static final Map<String, List<SourceModelData.BoneInfo>> entityBones = new ConcurrentHashMap<>();
```

`BoneState` 类内新增字段（`NpcBoneController.java:70-86`）：

```java
        /** AI pose 到期 tick（-1 = 永不自动过期，程序化手势用） */
        public long expireTick = -1;
        /** 淡出开始 tick（-1 = 未开始） */
        public long fadeOutTick = -1;
```

新增方法：

```java
    /** 渲染器每帧调用：注册实体骨骼列表，供 AI 骨名模糊匹配。 */
    public static void registerBones(String entityId, List<SourceModelData.BoneInfo> bones) {
        if (bones == null || bones.isEmpty()) {
            entityBones.remove(entityId);
            return;
        }
        entityBones.put(entityId, bones);
    }

    /** 角度钳制到 ±120°。 */
    public static float clampAngle(float angle) {
        return Math.max(-MAX_POSE_ANGLE, Math.min(MAX_POSE_ANGLE, angle));
    }

    /** 测试辅助：按 key 取状态。 */
    static BoneState getBoneState(String entityId, String boneName) {
        return boneStates.get(entityId + ":" + boneName);
    }

    /**
     * AI 骨骼指令：设置目标旋转（自动 clamp），duration 秒后淡出清除。
     * 骨骼名经 resolveBoneName 映射；已知骨骼列表内找不到时拒绝。
     * @return 1 = 接受，0 = 拒绝
     */
    public static int applyAiPose(String entityId, String boneName,
                                  float rx, float ry, float rz, float durationSec, long currentTick) {
        if (boneName == null || boneName.isEmpty()) return 0;

        List<SourceModelData.BoneInfo> bones = entityBones.get(entityId);
        if (bones != null && !bones.isEmpty()) {
            String resolved = resolveBoneName(boneName, bones);
            boolean found = false;
            for (SourceModelData.BoneInfo b : bones) {
                if (b.name.equalsIgnoreCase(resolved)) {
                    found = true;
                    break;
                }
            }
            if (!found) return 0; // 未知骨骼，拒绝
            boneName = resolved;
        }

        String key = entityId + ":" + boneName;
        BoneState state = boneStates.computeIfAbsent(key, k -> new BoneState());
        state.targetRotation = new Vector3f(clampAngle(rx), clampAngle(ry), clampAngle(rz));
        state.fadeOutTick = -1;
        state.expireTick = (durationSec <= 0) ? -1
                : currentTick + Math.max(1, Math.round(durationSec * 20.0f));
        state.interpolationSpeed = 0.15f;
        return 1;
    }
```

替换 `updateBones`（`NpcBoneController.java:213-249`）为迭代器版本（支持过期移除）：

```java
    public static void updateBones(long currentTick) {
        var it = boneStates.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BoneState> entry = it.next();
            BoneState state = entry.getValue();

            // AI pose 过期：停止跟踪目标，回零淡出，归零后移除
            if (state.expireTick > 0 && currentTick > state.expireTick) {
                if (state.fadeOutTick < 0) {
                    state.fadeOutTick = currentTick;
                    state.targetPosition = null;
                    state.targetRotation = null;
                }
                long fadeTicks = Math.max(1, Math.round(POSE_FADE_OUT_SEC * 20.0f));
                boolean faded = (currentTick - state.fadeOutTick) >= fadeTicks;
                if (faded) {
                    it.remove();
                    continue;
                }
                state.rotation.lerp(new Vector3f(0, 0, 0), state.interpolationSpeed);
            }

            if (state.targetPosition != null) {
                state.position.lerp(state.targetPosition, state.interpolationSpeed);
                if (state.position.distance(state.targetPosition) < 0.001f) {
                    state.position.set(state.targetPosition);
                    state.targetPosition = null;
                }
            }
            if (state.targetRotation != null) {
                state.rotation.lerp(state.targetRotation, state.interpolationSpeed);
                if (state.rotation.distance(state.targetRotation) < 0.001f) {
                    state.rotation.set(state.targetRotation);
                    state.targetRotation = null;
                }
            }
        }

        for (Map.Entry<String, List<BoneKeyframe>> entry : animationQueues.entrySet()) {
            String boneKey = entry.getKey();
            List<BoneKeyframe> keyframes = entry.getValue();
            if (keyframes.isEmpty()) continue;

            int animTick = (int) (currentTick % keyframes.size());
            BoneKeyframe kf = keyframes.get(animTick);

            String[] parts = boneKey.split(":");
            if (parts.length == 2) {
                String entityId = parts[0];
                String boneName = parts[1];
                setBonePosition(entityId, boneName, kf.position);
                setBoneRotation(entityId, boneName, kf.rotation);
                setBoneScale(entityId, boneName, kf.scale);
            }
        }
    }
```

`clearEntity`（`NpcBoneController.java:135-139`）追加 entityBones 清理：

```java
    public static void clearEntity(String entityId) {
        String prefix = entityId + ":";
        boneStates.keySet().removeIf(k -> k.startsWith(prefix));
        animationQueues.keySet().removeIf(k -> k.startsWith(prefix));
        entityBones.remove(entityId);
    }
```

`clearAll`（`:251-254`）追加 `entityBones.clear();`。

在 `NpcEntityRenderer.render` 的骨骼循环前（`NpcEntityRenderer.java:119` `String entityId = ...` 之前）注册骨骼：

```java
            String entityId = entity.getStringUUID();
            NpcBoneController.registerBones(entityId, modelData.bones);
```

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.NpcBoneControllerTest"`
预期：PASS（5 个测试）

- [ ] **步骤 5：编译验证主代码**

运行：`.\gradlew.bat compileJava`
预期：BUILD SUCCESSFUL

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneController.java src/main/java/transferstation/transferstation_whimsicalideas/client/renderer/NpcEntityRenderer.java src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcBoneControllerTest.java
git commit -m "feat: AI bone pose channel with angle clamping and expiry"
```

---

### 任务 4：NpcEntity 手势 + AI pose API

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcEntity.java`

- [ ] **步骤 1：在 NpcEntity 添加 import 与常量**

```java
import transferstation.transferstation_whimsicalideas.client.animation.AnimationLayers;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;
```

常量（类字段区）：

```java
    /** AI 单次骨骼指令上限 */
    private static final int MAX_AI_POSES = 8;
```

- [ ] **步骤 2：新增 playGesture / playProceduralGesture / applyBonePose**

在 `setAnimation` 方法后（`NpcEntity.java:325-327`）新增：

```java
    /**
     * 播放手势到 overlay 层：优先 VMD 动画文件（CustomAnim 目录），
     * 缺失时 fallback 程序化手势。不打断 base 层动画。
     */
    public void playGesture(String gesture, float fadeTime) {
        if (gesture == null || gesture.isEmpty() || "idle".equals(gesture)) return;
        if (AnimationProcessor.getAnimation(gesture) != null) {
            AnimationLayers.play(this, AnimationLayers.OVERLAY, gesture, 1.0f,
                    AnimationLayers.BoneMaskType.UPPER_BODY, fadeTime);
        } else {
            playProceduralGesture(gesture);
        }
    }

    private void playProceduralGesture(String gesture) {
        String id = getStringUUID();
        SourceModelData modelData = JavaModelRenderer.getModelData(this);
        List<SourceModelData.BoneInfo> bones = modelData != null ? modelData.bones : null;
        switch (gesture) {
            case "wave" -> NpcBoneController.playWaveAnimation(id, bones);
            case "nod" -> NpcBoneController.playNodAnimation(id, bones);
            case "shake" -> NpcBoneController.playShakeAnimation(id, bones);
            case "dance" -> NpcBoneController.playDanceAnimation(id, bones);
            default -> NpcBoneController.resetAllBones(id);
        }
    }

    /**
     * AI 骨骼指令入口（服务端解析 / 客户端 packet 双端可调，幂等覆盖式）。
     * @return 实际接受的骨骼数
     */
    public int applyBonePose(Map<String, float[]> boneRotations, float duration) {
        if (boneRotations == null || boneRotations.isEmpty()) return 0;
        long tick = level().getGameTime();
        int accepted = 0;
        for (Map.Entry<String, float[]> entry : boneRotations.entrySet()) {
            if (accepted >= MAX_AI_POSES) {
                LOGGER.warn("[NpcEntity] AI pose exceeds {} bones, ignoring rest", MAX_AI_POSES);
                break;
            }
            float[] r = entry.getValue();
            if (r == null || r.length < 3) continue;
            accepted += NpcBoneController.applyAiPose(getStringUUID(), entry.getKey(),
                    r[0], r[1], r[2], duration, tick);
        }
        return accepted;
    }
```

（`NpcEntity` 需要 `LOGGER`：加 `private static final Logger LOGGER = LogUtils.getLogger();` 与 import `com.mojang.logging.LogUtils`、`org.slf4j.Logger`；`SourceModelData` 已在同包；`List` import 已有。）

- [ ] **步骤 3：改造 handleGesture**

`handleGesture`（`NpcEntity.java:345-356`）的 `setAnimation(gesture)` 改为：

```java
        playGesture(gesture, 0.15f);
```

- [ ] **步骤 4：remove() 清理层状态**

`remove`（`NpcEntity.java:56-60`）追加：

```java
    @Override
    public void remove(RemovalReason reason) {
        NpcBoneController.clearEntity(this.getStringUUID());
        AnimationLayers.clearEntity(this);
        super.remove(reason);
    }
```

- [ ] **步骤 5：编译验证**

运行：`.\gradlew.bat compileJava`
预期：BUILD SUCCESSFUL

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcEntity.java
git commit -m "feat: NPC gesture layering and AI bone pose API"
```

---

### 任务 5：AI 协议扩展（NpcChatHandler + ChatS2CPacket）

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandler.java`
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/network/ChatS2CPacket.java`
- 测试：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandlerTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
// src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandlerTest.java
package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NpcChatHandlerTest {

    @Test
    void parsePoseBonesExtractsValidPose() {
        var json = JsonParser.parseString("{\"pose\": {\"bones\": {" +
                "\"ValveBiped.Bip01_Head\": [0, 0.5, 0]," +
                "\"ValveBiped.Bip01_R_UpperArm\": [-0.8, 0, 0]}, " +
                "\"duration\": 2.0}}").getAsJsonObject();
        Map<String, float[]> bones = NpcChatHandler.parsePoseBones(json.getAsJsonObject("pose").getAsJsonObject("bones"));
        assertEquals(2, bones.size());
        assertEquals(0.5f, bones.get("ValveBiped.Bip01_Head")[1], 0.001f);
        assertEquals(-0.8f, bones.get("ValveBiped.Bip01_R_UpperArm")[0], 0.001f);
    }

    @Test
    void parsePoseBonesIgnoresMalformedEntries() {
        var json = JsonParser.parseString("{\"pose\": {\"bones\": {" +
                "\"good\": [1, 2, 3]," +
                "\"tooShort\": [1]," +
                "\"notArray\": \"oops\"," +
                "\"notNumber\": [\"a\", 2, 3]}, " +
                "\"duration\": 1.0}}").getAsJsonObject();
        Map<String, float[]> bones = NpcChatHandler.parsePoseBones(json.getAsJsonObject("pose").getAsJsonObject("bones"));
        assertEquals(1, bones.size());
        assertTrue(bones.containsKey("good"));
    }

    @Test
    void parsePoseBonesTruncatesAtEight() {
        StringBuilder sb = new StringBuilder("{\"pose\": {\"bones\": {");
        for (int i = 0; i < 12; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"bone").append(i).append("\": [0, 0, ").append(i).append("]");
        }
        sb.append("}, \"duration\": 1.0}}");
        var json = JsonParser.parseString(sb.toString()).getAsJsonObject();
        Map<String, float[]> bones = NpcChatHandler.parsePoseBones(json.getAsJsonObject("pose").getAsJsonObject("bones"));
        assertEquals(8, bones.size());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.NpcChatHandlerTest"`
预期：FAIL，编译错误 "cannot find symbol: method parsePoseBones"

- [ ] **步骤 3：NpcChatHandler 添加解析 + prompt + 打包**

类内新增常量与解析方法：

```java
    /** AI pose 单次指令骨骼上限 */
    static final int MAX_POSE_BONES = 8;

    /** 解析 AI 回复 JSON 的 pose.bones（骨骼名 → [rx, ry, rz]），非法条目忽略，超限截断。 */
    static Map<String, float[]> parsePoseBones(com.google.gson.JsonObject bonesObj) {
        Map<String, float[]> out = new java.util.HashMap<>();
        if (bonesObj == null) return out;
        for (var entry : bonesObj.entrySet()) {
            if (out.size() >= MAX_POSE_BONES) break;
            try {
                if (!entry.getValue().isJsonArray()) continue;
                var arr = entry.getValue().getAsJsonArray();
                if (arr.size() < 3) continue;
                float[] r = new float[3];
                boolean ok = true;
                for (int i = 0; i < 3; i++) {
                    if (!arr.get(i).isJsonPrimitive() || !arr.get(i).getAsJsonPrimitive().isNumber()) {
                        ok = false;
                        break;
                    }
                    r[i] = arr.get(i).getAsFloat();
                }
                if (ok) out.put(entry.getKey(), r);
            } catch (Exception e) {
                LOGGER.warn("[NpcChat] Skipping malformed pose bone '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return out;
    }
```

`buildSystemPrompt`（`NpcChatHandler.java:164-170`）的 JSON 说明块改为：

```java
        sb.append("IMPORTANT: When responding, you may optionally return a JSON object ");
        sb.append("with the format: {\"reply\": \"...\", \"emotion\": \"happy|angry|sad|neutral|scared\", ");
        sb.append("\"gesture\": \"wave|nod|shake|point|idle\", ");
        sb.append("\"action\": {\"type\": \"chop_wood|follow|stop|guard|emote\"}, ");
        sb.append("\"pose\": {\"bones\": {\"ValveBiped.Bip01_Head\": [0, 0.5, 0]}, \"duration\": 2.0}} ");
        sb.append("to control my expressions, actions and bones. ");
        sb.append("The 'action' and 'pose' fields are optional. ");
        sb.append("For 'pose', bones accepts Source engine bone names (e.g. \"ValveBiped.Bip01_Head\", \"ValveBiped.Bip01_R_UpperArm\", \"ValveBiped.Bip01_L_Hand\") or VMD-style names (\"Bip01 Head\"); ");
        sb.append("angles are in radians as [rx, ry, rz]; 'duration' is in seconds (0.5-10). ");
        sb.append("If you don't return JSON, I'll just use plain text.");
```

`processStructuredResponse`（`NpcChatHandler.java:283-336`）在 JSON 解析块内加 pose 解析，并在 setAnimation/handleGesture 后打包：

```java
        String emotion = "neutral";
        String gesture = "idle";
        String cleanReply = rawReply;
        Map<String, float[]> poseBones = null;
        float poseDuration = 2.0f;

        try {
            String trimmed = rawReply.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                JsonObject json = JsonParser.parseString(trimmed).getAsJsonObject();

                if (json.has("emotion")) emotion = json.get("emotion").getAsString();
                if (json.has("gesture")) gesture = json.get("gesture").getAsString();
                if (json.has("reply")) cleanReply = json.get("reply").getAsString();

                if (json.has("pose") && json.get("pose").isJsonObject()) {
                    JsonObject pose = json.getAsJsonObject("pose");
                    if (pose.has("bones") && pose.get("bones").isJsonObject()) {
                        poseBones = parsePoseBones(pose.getAsJsonObject("bones"));
                    }
                    if (pose.has("duration") && pose.get("duration").isJsonPrimitive()) {
                        poseDuration = pose.get("duration").getAsFloat();
                    }
                }

                if (json.has("action") && json.get("action").isJsonObject()) {
                    JsonObject action = json.getAsJsonObject("action");
                    String actionType = action.get("type").getAsString();
                    executeAiAction(npc, actionType, action);
                }
            }
        } catch (Exception e) {
            ...原逻辑不变...
        }
```

原 `npc.setAnimation(gesture); npc.handleGesture(emotion, gesture);` 改为：

```java
        npc.handleGesture(emotion, gesture);
        if (poseBones != null && !poseBones.isEmpty()) {
            npc.applyBonePose(poseBones, Math.max(0.5f, Math.min(10.0f, poseDuration)));
        }
```

S2C 发送（`NpcChatHandler.java:330-335`）改为：

```java
        if (player instanceof ServerPlayer sp) {
            NpcChatNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp),
                new ChatS2CPacket(npc.getUUID(), cleanReply, emotion, gesture, poseBones, poseDuration)
            );
        }
```

- [ ] **步骤 4：扩展 ChatS2CPacket**

`ChatS2CPacket.java` 全文替换：

```java
package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import transferstation.transferstation_whimsicalideas.client.NpcChatScreen;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * @param emotion happy/angry/sad/neutral/scared
 * @param gesture wave/nod/shake/point/idle
 * @param pose 骨骼名 → [rx, ry, rz]（弧度，可为 null）
 * @param poseDuration pose 保持秒数
 */
public record ChatS2CPacket(UUID npcUuid, String reply, String emotion, String gesture,
                            Map<String, float[]> pose, float poseDuration) {

    public static ChatS2CPacket decode(FriendlyByteBuf buf) {
        Map<String, float[]> pose = new HashMap<>();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            String boneName = buf.readUtf(64);
            int len = buf.readInt();
            float[] r = new float[len];
            for (int j = 0; j < len; j++) {
                r[j] = buf.readFloat();
            }
            pose.put(boneName, r);
        }
        return new ChatS2CPacket(
                buf.readUUID(),
                buf.readUtf(512),
                buf.readUtf(32),
                buf.readUtf(32),
                pose,
                buf.readFloat()
        );
    }

    public static void encode(ChatS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.pose != null ? packet.pose.size() : 0);
        if (packet.pose != null) {
            for (Map.Entry<String, float[]> entry : packet.pose.entrySet()) {
                buf.writeUtf(entry.getKey(), 64);
                float[] r = entry.getValue();
                buf.writeInt(r.length);
                for (float v : r) {
                    buf.writeFloat(v);
                }
            }
        }
        buf.writeUUID(packet.npcUuid);
        buf.writeUtf(packet.reply, 512);
        buf.writeUtf(packet.emotion, 32);
        buf.writeUtf(packet.gesture, 32);
        buf.writeFloat(packet.poseDuration);
    }

    public static void handle(ChatS2CPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            var level = mc.player.level();
            Entity foundEntity = null;
            if (level instanceof ClientLevel clientLevel) {
                for (var e : clientLevel.entitiesForRendering()) {
                    if (e.getUUID().equals(packet.npcUuid)) {
                        foundEntity = e;
                        break;
                    }
                }
            }
            if (foundEntity instanceof NpcEntity npc) {
                if (mc.screen instanceof NpcChatScreen screen) {
                    screen.onNpcReply(packet.reply, packet.emotion, packet.gesture);
                }
                npc.handleGesture(packet.emotion, packet.gesture);
                if (packet.pose != null && !packet.pose.isEmpty()) {
                    npc.applyBonePose(packet.pose, packet.poseDuration);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`.\gradlew.bat test --tests "transferstation.transferstation_whimsicalideas.client.model.NpcChatHandlerTest"`
预期：PASS（3 个测试）

- [ ] **步骤 6：编译验证主代码**

运行：`.\gradlew.bat compileJava`
预期：BUILD SUCCESSFUL

- [ ] **步骤 7：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandler.java src/main/java/transferstation/transferstation_whimsicalideas/network/ChatS2CPacket.java src/test/java/transferstation/transferstation_whimsicalideas/client/model/NpcChatHandlerTest.java
git commit -m "feat: AI pose protocol over chat S2C packet"
```

---

### 任务 6：全量验证

- [ ] **步骤 1：运行全部单元测试**

运行：`.\gradlew.bat test`
预期：BUILD SUCCESSFUL，全部测试通过（8 旧 + 16 新 = 24 个）

- [ ] **步骤 2：全量编译（含 mixin refmap）**

运行：`.\gradlew.bat build`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：回归检查**

确认 `NpcChatScreen.onNpcReply(String, String, String)` 调用点（`ChatS2CPacket.handle` 内）签名未变；`GameStateAnimationMapper` 未修改（explicitAnim 逻辑保留）；无其他 `new ChatS2CPacket(` 调用点遗漏。

- [ ] **步骤 4：Commit（如测试有修正）**

```bash
git add -A
git commit -m "test: verify animation layering suite"
```

---

## 自检记录

**规格覆盖度：**
- layer/weight/boneMask/crossfade → 任务 1（AnimationLayers）+ 任务 2（getBoneTransforms 混合）✓
- 帧间插值 → 任务 2（sampleTrackAtTime）✓
- NpcEntity.playGesture → 任务 4 ✓
- AI pose 通道（clamp/数量/未知名/duration/淡入淡出）→ 任务 3 + 任务 4 ✓
- AI 协议（prompt + JSON 解析 + ChatS2CPacket 同步）→ 任务 5 ✓
- 测试（规格 10 条）→ 任务 1/2/3/5 覆盖全部 10 条（nlerp 半权重、boneMask、fade、回归[无 overlay = blendDeltas(weight 0) 恒等]、帧间插值、clamp、数量超限、未知名、淡入、过期）✓
- YAGNI 清单 → 均未实现（多 overlay 层/编辑器 UI/MDL 序列播放/pose 平移）✓

**占位符扫描：** 无 TODO/待定；每个步骤含完整代码与预期输出。✓

**类型一致性：** `AnimationLayers.BoneMaskType`、`AnimationLayers.OVERLAY`、`fadeWeight`、`isMaskedOut`、`getActiveOverlay`、`tickFades`、`clearEntity` 在任务 1 定义、任务 2/4 使用一致；`NpcBoneController.applyAiPose/registerBones/clampAngle/getBoneState` 任务 3 定义、任务 4/5 使用一致；`ChatS2CPacket` 新 record 签名（5 参数）任务 5 定义，发送端同步更新；`NpcEntity.playGesture/applyBonePose` 任务 4 定义、任务 5 调用一致。✓

**已知边界：**
- 服务端 `processStructuredResponse` 也调用 `npc.applyBonePose`（服务端实体无渲染，无副作用；客户端经 packet 覆盖应用，幂等）。
- 程序化手势（playWaveAnimation 等）key 为 `entityId:boneName`（冒号分隔），与 AI pose 的 `entityId:boneName` 同格式，无冲突（骨骼不同时）；程序化手势 key 无 `expireTick`（默认 -1 不过期），`clearEntity` 一并清理。
- `applyAnimation`/`applyTrackAnimation`（PoseStack 旧路径，编辑器预览用）保留，不受重构影响。
