package transferstation.transferstation_whimsicalideas.client.model;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NpcBoneControllerTest {

    private final String entityId = "test-entity-1";

    @AfterEach
    void cleanup() {
        NpcBoneController.clearEntity(entityId);
    }

    private List<SourceModelData.BoneInfo> valveBipedBones() {
        List<SourceModelData.BoneInfo> bones = new ArrayList<>();
        bones.add(bone("ValveBiped.Bip01_Pelvis", -1));
        bones.add(bone("ValveBiped.Bip01_Spine4", 0));
        bones.add(bone("ValveBiped.Bip01_Head", 1));
        bones.add(bone("ValveBiped.Bip01_R_UpperArm", 1));
        return bones;
    }

    private SourceModelData.BoneInfo bone(String name, int parent) {
        return new SourceModelData.BoneInfo(name, null, null, null, parent);
    }

    @Test
    void clampAngleLimitsTo120Degrees() {
        assertEquals(2.094395f, NpcBoneController.clampAngle(3.0f), 0.001f);
        assertEquals(-2.094395f, NpcBoneController.clampAngle(-3.0f), 0.001f);
        assertEquals(0.5f, NpcBoneController.clampAngle(0.5f), 0.001f);
    }

    @Test
    void applyAiPoseMapsFriendlyNameToMdlBone() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        int accepted = NpcBoneController.applyAiPose(entityId, "Bip01 Head", 0, 0.5f, 0, 1.0f, 0);
        assertEquals(1, accepted);
        Matrix4f m = NpcBoneController.getBoneTransform(entityId, "ValveBiped.Bip01_Head");
        Matrix4f identity = new Matrix4f().identity();
        assertFalse(m.equals(identity, 0.001f), "head bone should be rotated");
    }

    @Test
    void applyAiPoseRejectsUnknownBone() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        int accepted = NpcBoneController.applyAiPose(entityId, "nonexistent", 0, 0.5f, 0, 1.0f, 0);
        assertEquals(0, accepted);
    }

    @Test
    void applyAiPoseClampsExtremeAngles() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        NpcBoneController.applyAiPose(entityId, "head", 10.0f, 0, 0, 1.0f, 0);
        // 目标被 clamp 到 ±120°，插值推进后 rotation.x 不可能超过 2.1 rad
        NpcBoneController.updateBones(1);
        NpcBoneController.BoneState state = NpcBoneController.getBoneState(entityId, "ValveBiped.Bip01_Head");
        assertNotNull(state);
        assertTrue(state.targetRotation.x <= 2.1f);
    }

    @Test
    void poseExpiresAndFadesOut() {
        NpcBoneController.registerBones(entityId, valveBipedBones());
        NpcBoneController.applyAiPose(entityId, "head", 0, 1.0f, 0, 1.0f, 0); // duration 1s = 20 ticks
        NpcBoneController.updateBones(20); // 到期：停止目标插值，开始回零
        NpcBoneController.BoneState state = NpcBoneController.getBoneState(entityId, "ValveBiped.Bip01_Head");
        assertNotNull(state);
        assertNull(state.targetRotation, "expired pose should stop tracking target");
        for (int t = 21; t <= 30; t++) {
            NpcBoneController.updateBones(t); // 淡出 0.3s = 6 ticks
        }
        assertNull(NpcBoneController.getBoneState(entityId, "ValveBiped.Bip01_Head"),
                "faded-out pose should be removed");
    }
}
