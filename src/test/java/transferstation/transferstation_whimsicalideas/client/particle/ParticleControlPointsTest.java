package transferstation.transferstation_whimsicalideas.client.particle;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleControlPointsTest {

    @Test
    void defaultsToOrigin() {
        ParticleControlPoints cps = new ParticleControlPoints();
        assertArrayEquals(new float[]{0, 0, 0},
            new float[]{cps.get(0).x, cps.get(0).y, cps.get(0).z}, 0.001f);
    }

    @Test
    void setAndGetRoundTrip() {
        ParticleControlPoints cps = new ParticleControlPoints();
        cps.set(3, new Vector3f(10, 20, 30));
        assertEquals(10f, cps.get(3).x, 0.001f);
        assertEquals(30f, cps.get(3).z, 0.001f);
    }

    @Test
    void copyFromParentPreservesValues() {
        ParticleControlPoints parent = new ParticleControlPoints();
        parent.set(2, new Vector3f(5, 6, 7));
        parent.set(10, new Vector3f(1, 2, 3));

        ParticleControlPoints child = new ParticleControlPoints();
        child.copyFrom(parent);
        assertEquals(5f, child.get(2).x, 0.001f);
        assertEquals(6f, child.get(2).y, 0.001f);
        assertEquals(7f, child.get(2).z, 0.001f);
        assertEquals(1f, child.get(10).x, 0.001f);
        assertEquals(2f, child.get(10).y, 0.001f);
        assertEquals(3f, child.get(10).z, 0.001f);
        // 修改父副本不影响子
        parent.set(2, new Vector3f(99, 99, 99));
        assertEquals(5f, child.get(2).x, 0.001f);
    }

    @Test
    void setAllToMakesEverySlotSame() {
        ParticleControlPoints cps = new ParticleControlPoints();
        cps.set(1, new Vector3f(4, 4, 4));
        cps.setAllTo(new Vector3f(7, 8, 9));
        assertEquals(7f, cps.get(0).x, 0.001f);
        assertEquals(7f, cps.get(1).x, 0.001f);
        assertEquals(9f, cps.get(63).z, 0.001f);
    }
}
