package transferstation.transferstation_whimsicalideas.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleRampTest {

    @Test
    void samplesPiecewiseLinearCurve() {
        // 曲线段：(0.0,0.0), (0.5,1.0), (1.0,0.0) —— 尖峰在 t=0.5
        ParticleRamp ramp = new ParticleRamp(new float[]{0f, 0f, 0.5f, 1f, 1f, 0f});
        assertEquals(0f, ramp.sample(0f), 0.001f);
        assertEquals(0.5f, ramp.sample(0.25f), 0.001f);  // 0→0.5 中点
        assertEquals(1f, ramp.sample(0.5f), 0.001f);
        assertEquals(0.5f, ramp.sample(0.75f), 0.001f);  // 0.5→1.0 中点
        assertEquals(0f, ramp.sample(1f), 0.001f);
    }

    @Test
    void clampsOutsideRange() {
        ParticleRamp ramp = new ParticleRamp(new float[]{0f, 0f, 1f, 2f});
        assertEquals(0f, ramp.sample(-1f), 0.001f);   // 低于起点 → 首值
        assertEquals(2f, ramp.sample(5f), 0.001f);    // 超出终点 → 末值
    }

    @Test
    void singlePointReturnsConstant() {
        ParticleRamp ramp = new ParticleRamp(new float[]{0.5f, 3f});
        assertEquals(3f, ramp.sample(0f), 0.001f);
        assertEquals(3f, ramp.sample(1f), 0.001f);
    }
}
