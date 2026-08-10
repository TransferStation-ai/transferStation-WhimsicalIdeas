package transferstation.transferstation_whimsicalideas.client.particle;

import transferstation.transferstation_whimsicalideas.client.particle.PcfParticleSystemDef.RendererDef;

import java.util.Set;

/**
 * 纯函数决策类（无 MC 渲染 API 依赖，JUnit 可测）：
 * 把 Valve/Source 粒子渲染语义映射到 MC 渲染行为。
 */
public final class McParticleSemantics {
    /** Source 重力 op 默认 400 单位/s² */
    public static final float SOURCE_GRAVITY_UNITS = 400f;
    /** MC 渲染语义使用 -0.04 blocks/tick²（20 tps 下约等于 0.04×400 = 16 blocks/s² 之一） */
    public static final float MC_GRAVITY_BLOCKS_PER_TICK2 = -0.04f;

    private static final Set<String> ADDITIVE = Set.of("particle/fire", "particle/smoke", "particle/glow");
    private static final Set<String> FULLBRIGHT = Set.of("particle/fire", "particle/glow");

    public static boolean useAdditiveBlend(RendererDef r) {
        if (r.additive) return true;
        return r.materialPath != null && ADDITIVE.stream().anyMatch(r.materialPath::contains);
    }

    public static boolean isFullBright(RendererDef r) {
        return r.materialPath != null && FULLBRIGHT.stream().anyMatch(r.materialPath::contains);
    }

    /** Source 重力（单位/s²）→ MC 方块单位每 tick²（20 tps）换算 */
    public static float sourceGravityToMcPerTick2(float sourceUnits) {
        return sourceUnits / (20f * 20f);
    }

    private McParticleSemantics() {}
}
