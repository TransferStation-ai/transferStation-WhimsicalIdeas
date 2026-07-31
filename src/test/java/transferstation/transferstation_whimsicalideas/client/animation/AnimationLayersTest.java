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
}
