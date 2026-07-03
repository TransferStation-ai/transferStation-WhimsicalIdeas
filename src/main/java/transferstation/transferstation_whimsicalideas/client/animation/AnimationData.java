// AnimationData.java - core animation data model
package transferstation.transferstation_whimsicalideas.client.animation;

import java.util.List;

public class AnimationData {

    public String name;
    public List<AnimationTrack> tracks;
    public float fps;
    public int frameCount;
    public boolean loop;

    public AnimationData() {
    }

    public AnimationData(String name, float fps, int frameCount, boolean loop) {
        this.name = name;
        this.fps = fps;
        this.frameCount = frameCount;
        this.loop = loop;
        this.tracks = new java.util.ArrayList<>();
    }

    public void addTrack(AnimationTrack track) {
        tracks.add(track);
    }

    public float getDuration() {
        return frameCount / fps;
    }

    public static class AnimationTrack {
        public String boneName;
        public List<KeyFrame> keyFrames;

        public AnimationTrack() {
        }

        public AnimationTrack(String boneName) {
            this.boneName = boneName;
            this.keyFrames = new java.util.ArrayList<>();
        }

        public void addKeyFrame(KeyFrame kf) {
            keyFrames.add(kf);
        }
    }

    public static class KeyFrame {
        public int frame;
        public float[] translation;
        public float[] rotation;
        public float[] scale;

        public KeyFrame() {
        }

        public KeyFrame(int frame, float[] translation, float[] rotation, float[] scale) {
            this.frame = frame;
            this.translation = translation;
            this.rotation = rotation;
            this.scale = scale;
        }
    }
}