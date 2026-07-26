package transferstation.transferstation_whimsicalideas.client.model;

public class MdlSequenceData {

    public static class IKRule {
        public int chain;
        public int bone;
        public int slot;
        public int type;
        public float height;
        public float radius;
        public float floor;
        public float[] pos = new float[3];
        public float[] quat = new float[4];
        public float start, peak, tail, end;
        public float contact, drop, top;
        public String attachment;
        public float[] errorScale = new float[6];
        public short[] errorOffset = new short[6];
    }

    public static class Autolayer {
        public short sequence;
        public short pose;
        public int flags;
        public float start, peak, tail, end;
    }

    public static class ActivityModifier {
        public String name;
    }

    public static class Movement {
        public int endframe;
        public int motionflags;
        public float v0, v1;
        public float angle;
        public float[] vector = new float[3];
        public float[] position = new float[3];
    }

    public static class LocalHierarchy {
        public int bone;
        public int newParent;
        public float start, peak, tail, end;
        public int startFrame;
    }
}
