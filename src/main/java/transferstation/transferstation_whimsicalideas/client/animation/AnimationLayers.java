package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.*;

/**
 * 动画层混合系统。
  混合两套执行方式以便让aI和玩家都具有多个肢体反馈和动作
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
        return switch (mask) {
            case UPPER_BODY -> containsAny(name, "spine", "chest", "neck", "head", "arm", "hand", "clavicle", "finger");
            case LOWER_BODY -> containsAny(name, "hip", "pelvis", "leg", "foot", "thigh");
            case HEAD -> containsAny(name, "neck", "head");
            case ARMS -> containsAny(name, "arm", "hand", "clavicle", "finger");
            default -> true;
        };
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
        LayerState s = layers.computeIfAbsent(layerId, LayerState::new);
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
            return shouldRemove(s);
        });
    }

    /** tickFades 的移除判定：淡出完成（或 fadeTime<=0 的瞬停）即移除。 */
    static boolean shouldRemove(LayerState s) {
        return s.fadingOut && (s.fadeTime <= 0 || fadeWeight(s) <= 0);
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
