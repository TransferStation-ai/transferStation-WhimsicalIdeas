package transferstation.transferstation_whimsicalideas.client.animation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationLayersTest {

    // --- BoneMaskType 匹配 ---
    @Test
    void upperBodyMatchesTorsoAndHead() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_Spine4"));
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_Head"));
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_R_UpperArm"));
    }

    @Test
    void upperBodyExcludesLegs() {
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_L_Foot"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.UPPER_BODY, "ValveBiped.Bip01_Pelvis"));
    }

    @Test
    void lowerBodyMatchesLegsOnly() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.LOWER_BODY, "ValveBiped.Bip01_L_Thigh"));
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.LOWER_BODY, "ValveBiped.Bip01_R_Foot"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.LOWER_BODY, "ValveBiped.Bip01_Head"));
    }

    @Test
    void headMaskMatchesNeckAndHead() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.HEAD, "ValveBiped.Bip01_Head"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.HEAD, "ValveBiped.Bip01_R_UpperArm"));
    }

    @Test
    void armsMaskMatchesArmsAndHands() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.ARMS, "ValveBiped.Bip01_L_Hand"));
        assertFalse(AnimationLayers.matches(AnimationLayers.BoneMaskType.ARMS, "ValveBiped.Bip01_Head"));
    }

    @Test
    void allMaskMatchesEverything() {
        assertTrue(AnimationLayers.matches(AnimationLayers.BoneMaskType.ALL, "anything"));
    }

    // --- fade 权重 ---
    @Test
    void fadeInWeightRampsZeroToOne() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadeTime = 1.0f;
        s.fadeElapsed = 0.5f;
        assertEquals(0.5f, AnimationLayers.fadeWeight(s), 0.001f);
        s.fadeElapsed = 2.0f;
        assertEquals(1.0f, AnimationLayers.fadeWeight(s), 0.001f);
    }

    @Test
    void fadeOutWeightRampsOneToZero() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadeTime = 1.0f;
        s.fadingOut = true;
        s.fadeElapsed = 0.25f;
        assertEquals(0.75f, AnimationLayers.fadeWeight(s), 0.001f);
        s.fadeElapsed = 5.0f;
        assertEquals(0.0f, AnimationLayers.fadeWeight(s), 0.001f);
    }

    @Test
    void zeroFadeTimeMeansFullWeight() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadeTime = 0;
        assertEquals(1.0f, AnimationLayers.fadeWeight(s), 0.001f);
    }

    @Test
    void stopWithZeroFadeTimeRemovesLayerImmediately() {
        // 语义验证：fadeTime=0 的 stop 必须被 tickFades 立即移除（防幽灵层）
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadingOut = true;
        s.fadeTime = 0;
        assertTrue(AnimationLayers.shouldRemove(s), "fadeTime=0 的 stop 应立即移除");
    }

    @Test
    void stopWithPositiveFadeTimeKeepsLayerUntilFaded() {
        AnimationLayers.LayerState s = new AnimationLayers.LayerState("overlay");
        s.fadingOut = true;
        s.fadeTime = 1.0f;
        s.fadeElapsed = 0.25f;
        assertFalse(AnimationLayers.shouldRemove(s), "淡出未完成不应移除");
        s.fadeElapsed = 1.5f;
        assertTrue(AnimationLayers.shouldRemove(s), "淡出完成后应移除");
    }

    // --- delta 采样与混合（纯函数） ---
    @Test
    void sampleTrackAtTimeInterpolatesMidFrame() {
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("bone");
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{0, 0, 0},
                new float[]{0, 0, 0, 1}, null));
        track.addKeyFrame(new AnimationData.KeyFrame(10, new float[]{1, 0, 0},
                new float[]{0, (float) Math.sin(Math.PI / 4), 0, (float) Math.cos(Math.PI / 4)}, null));

        float[] d = AnimationProcessor.sampleTrackAtTime(track, 5.0f);
        // 布局 {tx,ty,tz, qx,qy,qz,qw, sx,sy,sz}
        assertEquals(0.5f, d[0], 0.001f); // 平移 lerp 中点
        assertEquals(0.0f, d[3], 0.001f);
        assertEquals(0.3826834f, d[4], 0.01f); // sin(22.5°)，nlerp 45° 半程
        assertEquals(0.9238795f, d[6], 0.01f); // cos(22.5°)
    }

    @Test
    void sampleTrackAtTimeClampsOutsideRange() {
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("bone");
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{0, 0, 0},
                new float[]{0, 0, 0, 1}, null));
        track.addKeyFrame(new AnimationData.KeyFrame(10, new float[]{1, 0, 0},
                new float[]{0, 0, 0, 1}, null));

        float[] before = AnimationProcessor.sampleTrackAtTime(track, -3.0f);
        float[] after = AnimationProcessor.sampleTrackAtTime(track, 25.0f);
        assertEquals(0.0f, before[0], 0.001f);
        assertEquals(1.0f, after[0], 0.001f);
    }

    @Test
    void sampleTrackAtTimeSingleKeyFrameIsConstant() {
        AnimationData.AnimationTrack track = new AnimationData.AnimationTrack("bone");
        track.addKeyFrame(new AnimationData.KeyFrame(0, new float[]{7, 0, 0},
                new float[]{0, 0, 0, 1}, null));
        float[] d = AnimationProcessor.sampleTrackAtTime(track, 42.0f);
        assertEquals(7.0f, d[0], 0.001f);
    }

    @Test
    void blendDeltasHalfWeightGivesFortyFiveDegrees() {
        // base = 0° 绕 Y，overlay = 90° 绕 Y，权重 0.5 → 45°
        float[] base = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] overlay = {1, 0, 0, 0, (float) Math.sin(Math.PI / 4), 0, (float) Math.cos(Math.PI / 4), 1, 1, 1};
        float[] out = AnimationProcessor.blendDeltas(base, overlay, 0.5f);
        assertEquals(0.5f, out[0], 0.001f); // 平移 lerp
        assertEquals((float) Math.sin(Math.PI / 8), out[4], 0.01f); // sin(22.5°)
        assertEquals((float) Math.cos(Math.PI / 8), out[6], 0.01f);
    }

    @Test
    void blendDeltasZeroWeightReturnsBase() {
        float[] base = {3, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] overlay = {9, 0, 0, 0, 0, 1, 0, 1, 1, 1};
        float[] out = AnimationProcessor.blendDeltas(base, overlay, 0.0f);
        assertArrayEquals(base, out, 0.001f);
    }

    @Test
    void blendDeltasClampsWeightAboveOne() {
        float[] base = {0, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] overlay = {4, 0, 0, 0, 0, 0, 1, 1, 1, 1};
        float[] out = AnimationProcessor.blendDeltas(base, overlay, 5.0f);
        assertEquals(4.0f, out[0], 0.001f);
    }

    @Test
    void nlerpHandlesOppositeQuaternions() {
        float[] a = {0, 0, 0, 1};
        float[] b = {0, 0, 0, -1}; // 相反方向 → 应翻转后归一化
        float[] out = AnimationProcessor.nlerpQuat(a, b, 0.5f);
        assertEquals(1.0f, out[3], 0.01f);
    }
}
