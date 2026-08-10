package transferstation.transferstation_whimsicalideas.client.particle;

import org.joml.Vector3f;

/**
 * Source 语义的 64 槽控制点（MAX_PARTICLE_CONTROL_POINTS = 64）。
 * 粒子系统可通过 m_bControlPointBased/m_nControlPoint 引用这些位置。
 */
public class ParticleControlPoints {
    public static final int MAX_CP = 64;
    private final Vector3f[] slots = new Vector3f[MAX_CP];

    public ParticleControlPoints() {
        for (int i = 0; i < MAX_CP; i++) slots[i] = new Vector3f();
    }

    public Vector3f get(int index) {
        return slots[index];
    }

    public void set(int index, Vector3f v) {
        slots[index].set(v);
    }

    /** 全部槽位设为同一位置（Source 中未显式设置的 CP 默认 = CP0） */
    public void setAllTo(Vector3f p) {
        for (var s : slots) s.set(p);
    }

    /** 从父系统复制所有槽位（子发射器继承父 CP） */
    public void copyFrom(ParticleControlPoints other) {
        for (int i = 0; i < MAX_CP; i++) {
            slots[i].set(other.slots[i]);
        }
    }
}
