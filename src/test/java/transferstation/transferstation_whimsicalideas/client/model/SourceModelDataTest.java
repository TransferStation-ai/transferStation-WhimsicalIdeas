package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class SourceModelDataTest {

    private SourceModelData createSimpleModel() {
        SourceModelData model = new SourceModelData();
        model.name = "test_model";
        return model;
    }

    private SourceModelData.MeshData createTriangleMesh() {
        return new SourceModelData.MeshData.Builder()
            .vertices(new float[]{
                0, 0, 0,  0, 0, 1,  0, 0,  // v0
                1, 0, 0,  0, 0, 1,  1, 0,  // v1
                0, 1, 0,  0, 0, 1,  0, 1   // v2
            })
            .indices(new int[]{0, 1, 2})
            .build();
    }

    private SourceModelData.MeshData createQuadMesh() {
        return new SourceModelData.MeshData.Builder()
            .vertices(new float[]{
                0, 0, 0,  0, 0, 1,  0, 0,
                1, 0, 0,  0, 0, 1,  1, 0,
                1, 1, 0,  0, 0, 1,  1, 1,
                0, 1, 0,  0, 0, 1,  0, 1
            })
            .indices(new int[]{0, 1, 2, 0, 2, 3})
            .build();
    }

    @Test
    void emptyModel() {
        SourceModelData model = createSimpleModel();
        assertEquals(0, model.totalVertices());
        assertEquals(0, model.totalTriangles());
        assertFalse(model.hasReferencePose());
        assertFalse(model.hasAPose());
    }

    @Test
    void addMesh() {
        SourceModelData model = createSimpleModel();
        model.meshes.add(createTriangleMesh());
        assertEquals(3, model.totalVertices());
        assertEquals(1, model.totalTriangles());
    }

    @Test
    void addBone() {
        SourceModelData model = createSimpleModel();
        model.bones.add(new SourceModelData.BoneInfo("root", new float[]{0, 0, 0}, -1));
        model.bones.add(new SourceModelData.BoneInfo("child", new float[]{1, 0, 0}, 0));
        assertEquals(2, model.bones.size());
    }

    @Test
    void meshDataIsValid() {
        SourceModelData.MeshData mesh = createTriangleMesh();
        assertTrue(mesh.isValid());
        assertEquals(3, mesh.vertexCount());
        assertEquals(1, mesh.triangleCount());
    }

    @Test
    void meshDataInvalid() {
        SourceModelData.MeshData empty = new SourceModelData.MeshData.Builder().build();
        assertFalse(empty.isValid());

        SourceModelData.MeshData noIndices = new SourceModelData.MeshData.Builder()
            .vertices(new float[8])
            .build();
        assertFalse(noIndices.isValid());
    }

    @Test
    void totalVerticesAndTriangles() {
        SourceModelData model = createSimpleModel();
        model.meshes.add(createTriangleMesh());
        model.meshes.add(createQuadMesh());
        assertEquals(7, model.totalVertices());
        assertEquals(3, model.totalTriangles());
    }

    @Test
    void lod0ReturnsFullMesh() {
        SourceModelData model = createSimpleModel();
        model.meshes.add(createQuadMesh());
        SourceModelData lod = model.getMeshesForLod(0);
        assertEquals(1, lod.meshes.size());
        assertEquals(4, lod.meshes.get(0).vertexCount());
        assertEquals(2, lod.meshes.get(0).triangleCount());
    }

    @Test
    void lod1Decimates() {
        SourceModelData model = createSimpleModel();
        SourceModelData.MeshData bigMesh = new SourceModelData.MeshData.Builder()
            .vertices(createFloatArray(64 * 8))
            .indices(createIntArray(96))
            .build();
        model.meshes.add(bigMesh);
        SourceModelData lod = model.getMeshesForLod(1);
        assertTrue(lod.meshes.get(0).triangleCount() <= 48);
    }

    @Test
    void lod2Decimates() {
        SourceModelData model = createSimpleModel();
        SourceModelData.MeshData bigMesh = new SourceModelData.MeshData.Builder()
            .vertices(createFloatArray(64 * 8))
            .indices(createIntArray(96))
            .build();
        model.meshes.add(bigMesh);
        SourceModelData lod = model.getMeshesForLod(2);
        assertTrue(lod.meshes.get(0).triangleCount() <= 24);
    }

    @Test
    void lodPreservesDataShape() {
        SourceModelData model = createSimpleModel();
        SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
            .vertices(createFloatArray(32 * 8))
            .indices(createIntArray(48))
            .texture(null)
            .build();
        model.meshes.add(mesh);
        SourceModelData lod = model.getMeshesForLod(1);
        assertFalse(lod.meshes.isEmpty());
    }

    @Test
    void copyFullMeshDataPreservesProperties() {
        SourceModelData model = createSimpleModel();
        model.name = "original";
        model.modelScale = 2.0f;
        model.minX = -10; model.maxX = 10;
        model.meshes.add(createTriangleMesh());
        SourceModelData copy = model.getMeshesForLod(0);
        assertEquals("original", copy.name);
        assertEquals(2.0f, copy.modelScale);
    }

    @Test
    void skinTextureIndexHandlesEmptyTable() {
        SourceModelData model = createSimpleModel();
        assertEquals(5, model.getSkinTextureIndex(5, 0));
    }

    @Test
    void skinTextureIndexWithTable() {
        SourceModelData model = createSimpleModel();
        model.numSkinRef = 2;
        model.skinTable.add(10);
        model.skinTable.add(20);
        assertEquals(10, model.getSkinTextureIndex(0, 0));
        assertEquals(20, model.getSkinTextureIndex(1, 0));
    }

    @Test
    void referencePoseDetection() {
        SourceModelData model = createSimpleModel();
        assertFalse(model.hasReferencePose());
        model.referenceSequenceIndices.add(0);
        assertTrue(model.hasReferencePose());
    }

    @Test
    void aPoseDetection() {
        SourceModelData model = createSimpleModel();
        assertFalse(model.hasAPose());
        model.aPoseSequenceIndices.add(0);
        assertTrue(model.hasAPose());
    }

    @Test
    void getReferenceBonePos() {
        SourceModelData model = createSimpleModel();
        MdlDataTypes.SrcBoneTransform bt = new MdlDataTypes.SrcBoneTransform();
        bt.pos = new float[]{1, 2, 3};
        bt.quat = new float[]{0, 0, 0, 1};
        model.srcBoneTransforms.add(bt);
        float[] pos = model.getReferenceBonePos(0);
        assertNotNull(pos);
        assertEquals(1, pos[0]);
        assertEquals(2, pos[1]);
        assertEquals(3, pos[2]);
        assertNull(model.getReferenceBonePos(99));
    }

    @Test
    void bodyPartInfo() {
        SourceModelData.BodyPartInfo bpi = new SourceModelData.BodyPartInfo("head", 1, 0);
        assertEquals("head", bpi.name);
        assertEquals(1, bpi.numModels);
        assertEquals(0, bpi.baseIndex);
    }

    private float[] createFloatArray(int length) {
        float[] arr = new float[length];
        for (int i = 0; i < length; i++) arr[i] = (float) Math.random();
        return arr;
    }

    private int[] createIntArray(int length) {
        int[] arr = new int[length];
        for (int i = 0; i < length; i++) arr[i] = i / 3;
        return arr;
    }
}
