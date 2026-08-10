package transferstation.transferstation_whimsicalideas.client.particle;

import java.util.Arrays;

/**
 * Source `_ramp` 曲线：分段线性插值。
 * knots = {t0, v0, t1, v1, ...}（time 单调递增，time/value 成对）。
 * t 超出范围时钳制到端点值；单点退化为常量。
 */
public class ParticleRamp {
    private final float[] times;
    private final float[] values;

    public ParticleRamp(float[] knots) {
        int n = knots.length / 2;
        this.times = new float[n];
        this.values = new float[n];
        for (int i = 0; i < n; i++) {
            times[i] = knots[i * 2];
            values[i] = knots[i * 2 + 1];
        }
    }

    /** 分段线性插值；t 超出范围时钳制到端点值 */
    public float sample(float t) {
        int n = times.length;
        if (n == 0) return 0f;
        if (t <= times[0]) return values[0];
        if (t >= times[n - 1]) return values[n - 1];
        int idx = Arrays.binarySearch(times, t);
        if (idx >= 0) return values[idx];
        idx = -idx - 1; // 第一个 > t 的下标
        float t0 = times[idx - 1], t1 = times[idx];
        float span = t1 - t0;
        if (span <= 0f) return values[idx];
        float u = (t - t0) / span;
        return values[idx - 1] + (values[idx] - values[idx - 1]) * u;
    }
}
