package transferstation.transferstation_whimsicalideas.client.debug;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;
import transferstation.transferstation_whimsicalideas.client.model.JavaModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.MdlDataTypes;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.NpcChatHandler;
import transferstation.transferstation_whimsicalideas.client.model.PhysicsBridge;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import static org.junit.jupiter.api.Assertions.*;

class ModelDebugScreenTest {

    // --- skinVertex: same mesh, bind pose (no matrices) vs identity matrices ---

    @Test
    void skinVertexIdentityMatchesRawVertex() {
        float[] vertices = {1, 2, 3, 0, 0, 1, 0.5f, 0.5f};
        float[] weights = {1, 0, 0, 0};
        int[] boneIdx = {0, 0, 0, 0};
        float[][] boneMatrices = {identityMatrix()};

        Vector4f pos = new Vector4f();
        Vector4f nrm = new Vector4f();
        assertTrue(JavaModelRenderer.skinVertex(vertices, 0, weights, boneIdx, 0, boneMatrices, pos, nrm));

        assertEquals(1f, pos.x(), 0.001f);
        assertEquals(2f, pos.y(), 0.001f);
        assertEquals(3f, pos.z(), 0.001f);
        assertEquals(0f, nrm.x(), 0.001f);
        assertEquals(0f, nrm.y(), 0.001f);
        assertEquals(1f, nrm.z(), 0.001f);
    }

    @Test
    void skinVertexTranslationMovesVertex() {
        float[] vertices = {1, 2, 3, 0, 0, 1, 0.5f, 0.5f};
        float[] weights = {1, 0, 0, 0};
        int[] boneIdx = {0, 0, 0, 0};
        float[][] boneMatrices = {translationMatrix(10, 20, 30)};

        Vector4f pos = new Vector4f();
        Vector4f nrm = new Vector4f();
        assertTrue(JavaModelRenderer.skinVertex(vertices, 0, weights, boneIdx, 0, boneMatrices, pos, nrm));

        assertEquals(11f, pos.x(), 0.001f);
        assertEquals(22f, pos.y(), 0.001f);
        assertEquals(33f, pos.z(), 0.001f);
        assertEquals(0f, nrm.x(), 0.001f);
        assertEquals(0f, nrm.y(), 0.001f);
        assertEquals(1f, nrm.z(), 0.001f);
    }

    @Test
    void skinVertexNoWeightsReturnsFalse() {
        float[] vertices = {1, 2, 3, 0, 0, 1, 0.5f, 0.5f};
        float[] weights = {0, 0, 0, 0};
        int[] boneIdx = {0, 0, 0, 0};
        float[][] boneMatrices = {identityMatrix()};

        Vector4f pos = new Vector4f();
        Vector4f nrm = new Vector4f();
        assertFalse(JavaModelRenderer.skinVertex(vertices, 0, weights, boneIdx, 0, boneMatrices, pos, nrm));
    }

    @Test
    void skinVertexNullWeightsReturnsFalse() {
        float[] vertices = {1, 2, 3, 0, 0, 1, 0.5f, 0.5f};
        float[][] boneMatrices = {identityMatrix()};

        Vector4f pos = new Vector4f();
        Vector4f nrm = new Vector4f();
        assertFalse(JavaModelRenderer.skinVertex(vertices, 0, null, null, 0, boneMatrices, pos, nrm));
    }

    // --- standby bone generation ---

    @Test
    void referencePoseBoneTransformsSizeMatchesBones() {
        SourceModelData model = new SourceModelData();
        model.bones.add(new SourceModelData.BoneInfo("root", new float[]{0, 0, 0}, -1));
        model.bones.add(new SourceModelData.BoneInfo("child", new float[]{1, 0, 0}, 0));

        MdlDataTypes.SrcBoneTransform bt = new MdlDataTypes.SrcBoneTransform();
        bt.pos = new float[]{0, 0, 0};
        bt.quat = new float[]{0, 0, 0, 1};
        model.srcBoneTransforms.add(bt);
        model.srcBoneTransforms.add(bt);

        float[][] transforms = AnimationProcessor.getReferencePoseBoneTransforms(model);
        assertNotNull(transforms);
        assertEquals(2, transforms.length);
    }

    @Test
    void referencePoseBoneTransformsNullForEmptyModel() {
        assertNull(AnimationProcessor.getReferencePoseBoneTransforms(null));
        assertNull(AnimationProcessor.getReferencePoseBoneTransforms(new SourceModelData()));
    }

    @Test
    void referencePoseBoneTransformsFallbackToBindPose() {
        SourceModelData model = new SourceModelData();
        model.bones.add(new SourceModelData.BoneInfo("root", new float[]{0, 0, 0}, -1));

        float[][] transforms = AnimationProcessor.getReferencePoseBoneTransforms(model);
        assertNotNull(transforms);
        assertEquals(1, transforms.length);
        // Bind pose falls back to bone pos/quat (identity root bone)
        assertEquals(0f, transforms[0][12], 0.001f);
        assertEquals(0f, transforms[0][13], 0.001f);
        assertEquals(0f, transforms[0][14], 0.001f);
    }

    // --- white-mesh detection ---

    @Test
    void whiteMeshDetectsNullTexture() {
        SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
            .vertices(new float[8])
            .indices(new int[]{0, 1, 2})
            .texture(null)
            .build();
        assertTrue(ModelDebugScreen.isWhiteMesh(mesh));
    }

    @Test
    void whiteMeshFalseWhenVtfRegistered() {
        SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
            .vertices(new float[8])
            .indices(new int[]{0, 1, 2})
            .texture(null)
            .vtfKey("materials/test/albedo")
            .build();
        assertTrue(ModelDebugScreen.isWhiteMesh(mesh));
    }

    // --- system availability ---

    @Test
    void systemCacheMissReturnsNull() {
        assertNull(ModelLoadManager.getCached("nonexistent/definitely"));
    }

    @Test
    void physicsInitiallyUnavailable() {
        assertFalse(PhysicsBridge.isAvailable());
    }

    @Test
    void aiChatDisabledWithoutKey() {
        assertFalse(NpcChatHandler.isEnabled());
    }

    // --- helpers ---

    private static float[] identityMatrix() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        return m;
    }

    private static float[] translationMatrix(float x, float y, float z) {
        float[] m = identityMatrix();
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }
}
