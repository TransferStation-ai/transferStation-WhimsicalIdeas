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

    static class ProceduralBonesParser {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

        private static final int STUDIO_PROC_AXISINTERP = 1;
        private static final int STUDIO_PROC_QUATINTERP = 2;
        private static final int STUDIO_PROC_AIMATBONE = 3;
        private static final int STUDIO_PROC_AIMATATTACH = 4;
        private static final int STUDIO_PROC_JIGGLE = 5;

        public static void parse(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
            if (result.bones == null || result.bones.isEmpty()) return;

            int boneSize = result.boneSize;
            int boneDataBase = result.header.boneindex;
            if (boneDataBase <= 0) return;

            for (int i = 0; i < result.bones.size(); i++) {
                MdlDataTypes.Bone bone = result.bones.get(i);
                if (bone.proctype <= 0 || bone.procindex <= 0) continue;
                if (bone.procindex >= bufferLimit) continue;

                int boneEntryOff = boneDataBase + i * boneSize;
                int procDataOff = boneEntryOff + bone.procindex;

                switch (bone.proctype) {
                    case STUDIO_PROC_AXISINTERP:
                        parseAxisInterp(buf, result, procDataOff, bufferLimit);
                        break;
                    case STUDIO_PROC_QUATINTERP:
                        parseQuatInterp(buf, result, procDataOff, bufferLimit);
                        break;
                    case STUDIO_PROC_AIMATBONE:
                    case STUDIO_PROC_AIMATATTACH:
                        parseAimAt(buf, result, procDataOff, bufferLimit);
                        break;
                    case STUDIO_PROC_JIGGLE:
                        parseJiggle(buf, result, procDataOff, bufferLimit);
                        break;
                    default:
                        LOGGER.debug("[MdlParser] Unknown procedural bone type {} for bone '{}'",
                            bone.proctype, bone.name);
                }
            }
        }

        private static void parseAxisInterp(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result,
                                             int dataOff, int bufferLimit) {
            if (dataOff + 44 > bufferLimit) return;
            AxisInterpBone ab = new AxisInterpBone();
            ab.control = buf.getInt(dataOff);
            ab.axis = buf.getInt(dataOff + 4);
            for (int j = 0; j < 6; j++) {
                int base = dataOff + 8 + j * 28;
                ab.pos[j][0] = buf.getFloat(base);
                ab.pos[j][1] = buf.getFloat(base + 4);
                ab.pos[j][2] = buf.getFloat(base + 8);
                ab.quat[j][0] = buf.getFloat(base + 12);
                ab.quat[j][1] = buf.getFloat(base + 16);
                ab.quat[j][2] = buf.getFloat(base + 20);
                ab.quat[j][3] = buf.getFloat(base + 24);
            }
            result.axisInterpBones.add(ab);
        }

        private static void parseQuatInterp(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result,
                                             int dataOff, int bufferLimit) {
            if (dataOff + 12 > bufferLimit) return;
            QuatInterpBone qb = new QuatInterpBone();
            qb.control = buf.getInt(dataOff);
            int numTriggers = buf.getInt(dataOff + 4);
            int triggerIndex = buf.getInt(dataOff + 8);

            if (numTriggers <= 0 || numTriggers > 64) return;
            if (triggerIndex <= 0) return;

            int absTriggerOff = dataOff + triggerIndex;
            if (absTriggerOff + numTriggers * 32 > bufferLimit) return;

            for (int t = 0; t < numTriggers; t++) {
                int toff = absTriggerOff + t * 32;
                QuatInterpTrigger qt = new QuatInterpTrigger();
                qt.invTolerance = buf.getFloat(toff);
                qt.trigger[0] = buf.getFloat(toff + 4);
                qt.trigger[1] = buf.getFloat(toff + 8);
                qt.trigger[2] = buf.getFloat(toff + 12);
                qt.trigger[3] = buf.getFloat(toff + 16);
                qt.pos[0] = buf.getFloat(toff + 20);
                qt.pos[1] = buf.getFloat(toff + 24);
                qt.pos[2] = buf.getFloat(toff + 28);
                qt.quat[0] = buf.getFloat(toff + 20);
                qt.quat[1] = buf.getFloat(toff + 24);
                qt.quat[2] = buf.getFloat(toff + 28);
                qt.quat[3] = 1.0f;
                qb.triggers.add(qt);
            }
            result.quatInterpBones.add(qb);
        }

        private static void parseAimAt(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result,
                                        int dataOff, int bufferLimit) {
            if (dataOff + 32 > bufferLimit) return;
            AimAtBone ab = new AimAtBone();
            ab.parent = buf.getInt(dataOff);
            ab.aim = buf.getInt(dataOff + 4);
            ab.aimvector[0] = buf.getFloat(dataOff + 8);
            ab.aimvector[1] = buf.getFloat(dataOff + 12);
            ab.aimvector[2] = buf.getFloat(dataOff + 16);
            ab.upvector[0] = buf.getFloat(dataOff + 20);
            ab.upvector[1] = buf.getFloat(dataOff + 24);
            ab.upvector[2] = buf.getFloat(dataOff + 28);
            result.aimAtBones.add(ab);
        }

        private static void parseJiggle(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result,
                                         int dataOff, int bufferLimit) {
            if (dataOff + 96 > bufferLimit) return;
            JiggleBone jb = new JiggleBone();
            int o = dataOff;
            jb.flags = buf.getInt(o); o += 4;
            jb.length = buf.getFloat(o); o += 4;
            jb.tipMass = buf.getFloat(o); o += 4;
            jb.yawStiffness = buf.getFloat(o); o += 4;
            jb.yawDamping = buf.getFloat(o); o += 4;
            jb.pitchStiffness = buf.getFloat(o); o += 4;
            jb.pitchDamping = buf.getFloat(o); o += 4;
            jb.alongStiffness = buf.getFloat(o); o += 4;
            jb.alongDamping = buf.getFloat(o); o += 4;
            jb.angleLimit = buf.getFloat(o); o += 4;
            jb.minYaw = buf.getFloat(o); o += 4;
            jb.maxYaw = buf.getFloat(o); o += 4;
            jb.yawFriction = buf.getFloat(o); o += 4;
            jb.yawBounce = buf.getFloat(o); o += 4;
            jb.minPitch = buf.getFloat(o); o += 4;
            jb.maxPitch = buf.getFloat(o); o += 4;
            jb.pitchFriction = buf.getFloat(o); o += 4;
            jb.pitchBounce = buf.getFloat(o); o += 4;
            jb.baseMass = buf.getFloat(o); o += 4;
            jb.baseStiffness = buf.getFloat(o); o += 4;
            jb.baseDamping = buf.getFloat(o); o += 4;
            jb.baseMinLeft = buf.getFloat(o); o += 4;
            jb.baseMaxLeft = buf.getFloat(o); o += 4;
            jb.baseLeftFriction = buf.getFloat(o); o += 4;
            jb.baseMinUp = buf.getFloat(o); o += 4;
            jb.baseMaxUp = buf.getFloat(o); o += 4;
            jb.baseUpFriction = buf.getFloat(o); o += 4;
            jb.baseMinForward = buf.getFloat(o); o += 4;
            jb.baseMaxForward = buf.getFloat(o); o += 4;
            jb.baseForwardFriction = buf.getFloat(o); o += 4;
            jb.boingImpactSpeed = buf.getFloat(o); o += 4;
            jb.boingImpactAngle = buf.getFloat(o); o += 4;
            jb.boingDampingRate = buf.getFloat(o); o += 4;
            jb.boingFrequency = buf.getFloat(o); o += 4;
            jb.boingAmplitude = buf.getFloat(o); o += 4;
            result.jiggleBones.add(jb);
        }
    }
}
