package transferstation.transferstation_whimsicalideas.client.model;

public class MdlProceduralBones {

    public static class AxisInterpBone {
        public int control;
        public int axis;
        public float[][] pos = new float[6][3];
        public float[][] quat = new float[6][4];
    }

    public static class QuatInterpTrigger {
        public float invTolerance;
        public float[] trigger = new float[4];
        public float[] pos = new float[3];
        public float[] quat = new float[4];
    }

    public static class QuatInterpBone {
        public int control;
        public java.util.List<QuatInterpTrigger> triggers = new java.util.ArrayList<>();
    }

    public static class JiggleBone {
        public static final int JIGGLE_IS_FLEXIBLE        = 0x01;
        public static final int JIGGLE_IS_RIGID            = 0x02;
        public static final int JIGGLE_HAS_YAW_CONSTRAINT       = 0x04;
        public static final int JIGGLE_HAS_PITCH_CONSTRAINT     = 0x08;
        public static final int JIGGLE_HAS_ANGLE_CONSTRAINT     = 0x10;
        public static final int JIGGLE_HAS_LENGTH_CONSTRAINT    = 0x20;
        public static final int JIGGLE_HAS_BASE_SPRING   = 0x40;
        public static final int JIGGLE_IS_BOING          = 0x80;

        public int flags;
        public float length;
        public float tipMass;
        public float yawStiffness, yawDamping;
        public float pitchStiffness, pitchDamping;
        public float alongStiffness, alongDamping;
        public float angleLimit;
        public float minYaw, maxYaw, yawFriction, yawBounce;
        public float minPitch, maxPitch, pitchFriction, pitchBounce;
        public float baseMass, baseStiffness, baseDamping;
        public float baseMinLeft, baseMaxLeft, baseLeftFriction;
        public float baseMinUp, baseMaxUp, baseUpFriction;
        public float baseMinForward, baseMaxForward, baseForwardFriction;
        public float boingImpactSpeed, boingImpactAngle;
        public float boingDampingRate, boingFrequency, boingAmplitude;
    }

    public static class AimAtBone {
        public int parent;
        public int aim;
        public float[] aimvector = new float[3];
        public float[] upvector = new float[3];
        public float[] basepos = new float[3];
    }
}
