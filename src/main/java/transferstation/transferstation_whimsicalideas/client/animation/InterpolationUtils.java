package transferstation.transferstation_whimsicalideas.client.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Cubic Hermite spline and improved quaternion interpolation utilities.
 * Replaces linear lerp/nlerp with smoother curves for animation playback.
 */
public final class InterpolationUtils {

    private InterpolationUtils() {
    }

    /**
     * Cubic Hermite spline interpolation for a scalar value.
     * Uses tangents derived from neighboring keyframes for C1 continuity.
     *
     * @param p0 value at previous keyframe
     * @param p1 value at current keyframe
     * @param p2 value at next keyframe
     * @param p3 value at keyframe after next
     * @param t  interpolation factor [0,1] between p1 and p2
     * @return interpolated value
     */
    public static float hermite(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2 * t3 - 3 * t2 + 1;
        float h10 = t3 - 2 * t2 + t;
        float h01 = -2 * t3 + 3 * t2;
        float h11 = t3 - t2;
        return h00 * p1 + h10 * (p2 - p0) * 0.5f + h01 * p2 + h11 * (p3 - p1) * 0.5f;
    }

    /**
     * Cubic Hermite interpolation for a 3-component vector (translation or scale).
     */
    public static void hermiteVec3(float[] out, float[] p0, float[] p1, float[] p2, float[] p3, float t) {
        out[0] = hermite(p0[0], p1[0], p2[0], p3[0], t);
        out[1] = hermite(p0[1], p1[1], p2[1], p3[1], t);
        out[2] = hermite(p0[2], p1[2], p2[2], p3[2], t);
    }

    /**
     * Cubic Hermite interpolation for scale (may be 3 components).
     */
    public static void hermiteScale(float[] out, float[] p0, float[] p1, float[] p2, float[] p3, float t) {
        int len = Math.min(Math.min(p0.length, p1.length), Math.min(p2.length, p3.length));
        for (int i = 0; i < len; i++) {
            out[i] = hermite(p0[i], p1[i], p2[i], p3[i], t);
        }
    }

    /**
     * Squad (Spherical and Quadrangle) interpolation for quaternions.
     * Provides C2 continuity (smooth acceleration) through quaternion keyframes.
     * Input quaternions are [x, y, z, w] format.
     */
    public static float[] squad(float[] q0, float[] q1, float[] q2, float[] q3, float t) {
        Quaternionf a = squadIntermediate(q0, q1, q2);
        Quaternionf b = squadIntermediate(q1, q2, q3);

        Quaternionf q1q = new Quaternionf(q1[0], q1[1], q1[2], q1[3]);
        Quaternionf q2q = new Quaternionf(q2[0], q2[1], q2[2], q2[3]);

        Quaternionf slerpA = slerpQuatJoml(q1q, a, t);
        Quaternionf slerpB = slerpQuatJoml(q2q, b, t);

        Quaternionf result = slerpQuatJoml(slerpA, slerpB, 2.0f * t * (1.0f - t));
        return new float[]{result.x, result.y, result.z, result.w};
    }

    /**
     * Compute the intermediate "control" quaternion for squad.
     */
    private static Quaternionf squadIntermediate(float[] qNeg, float[] q, float[] qPos) {
        Quaternionf qn = new Quaternionf(qNeg[0], qNeg[1], qNeg[2], qNeg[3]);
        Quaternionf qq = new Quaternionf(q[0], q[1], q[2], q[3]);
        Quaternionf qp = new Quaternionf(qPos[0], qPos[1], qPos[2], qPos[3]);

        Quaternionf qInv = new Quaternionf(qq).conjugate();
        Quaternionf ln1 = logQuat(multiplyQuat(qInv, qn));
        Quaternionf ln2 = logQuat(multiplyQuat(qInv, qp));

        Quaternionf sum = new Quaternionf(ln1).add(ln2).mul(-0.25f);
        Quaternionf exp = expQuat(sum);
        return multiplyQuat(qq, exp);
    }

    /**
     * Quaternion exponential map.
     */
    private static Quaternionf expQuat(Quaternionf q) {
        float len = (float) Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
        float sinLen = (float) Math.sin(len);
        float expW = (float) Math.exp(q.w);

        Quaternionf result = new Quaternionf();
        if (len > 1e-6f) {
            result.x = expW * sinLen / len * q.x;
            result.y = expW * sinLen / len * q.y;
            result.z = expW * sinLen / len * q.z;
        }
        result.w = expW * (float) Math.cos(len);
        return result;
    }

    /**
     * Quaternion logarithmic map.
     */
    private static Quaternionf logQuat(Quaternionf q) {
        float len = (float) Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z);
        float aLen = (float) Math.acos(Math.max(-1.0f, Math.min(1.0f, q.w)));

        Quaternionf result = new Quaternionf();
        if (len > 1e-6f) {
            float t = aLen / len;
            result.x = t * q.x;
            result.y = t * q.y;
            result.z = t * q.z;
        }
        result.w = (float) Math.log(Math.max(1e-10f, q.w * q.w + len * len)) * 0.5f;
        return result;
    }

    /**
     * Quaternion multiplication (Hamilton product).
     */
    private static Quaternionf multiplyQuat(Quaternionf a, Quaternionf b) {
        return new Quaternionf(
                a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
                a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
                a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
                a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
        );
    }

    /**
     * Spherical linear interpolation (SLERP) for quaternions using JOML.
     * Falls back to nlerp for very close quaternions.
     */
    public static Quaternionf slerpQuatJoml(Quaternionf a, Quaternionf b, float t) {
        float dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;

        Quaternionf bAdj = new Quaternionf(b);
        if (dot < 0) {
            bAdj.set(-b.x, -b.y, -b.z, -b.w);
            dot = -dot;
        }

        if (dot > 0.9995f) {
            return nlerpQuatJoml(a, bAdj, t);
        }

        float theta = (float) Math.acos(Math.min(1.0f, dot));
        float sinTheta = (float) Math.sin(theta);
        float wa = (float) Math.sin((1 - t) * theta) / sinTheta;
        float wb = (float) Math.sin(t * theta) / sinTheta;

        return new Quaternionf(
                a.x * wa + bAdj.x * wb,
                a.y * wa + bAdj.y * wb,
                a.z * wa + bAdj.z * wb,
                a.w * wa + bAdj.w * wb
        );
    }

    /**
     * Normalized linear interpolation fallback for nearly-identical quaternions.
     */
    private static Quaternionf nlerpQuatJoml(Quaternionf a, Quaternionf b, float t) {
        Quaternionf result = new Quaternionf();
        result.x = a.x + (b.x - a.x) * t;
        result.y = a.y + (b.y - a.y) * t;
        result.z = a.z + (b.z - a.z) * t;
        result.w = a.w + (b.w - a.w) * t;
        return result.normalize();
    }

    /**
     * Smooth ease-in-out (Hermite basis function h00 with zero tangents).
     * Maps [0,1] to [0,1] with zero slope at endpoints.
     */
    public static float smoothStep(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Smoother ease-in-out (quintic). C2 continuous (zero 2nd derivative at endpoints).
     */
    public static float smootherStep(float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
    }
}
