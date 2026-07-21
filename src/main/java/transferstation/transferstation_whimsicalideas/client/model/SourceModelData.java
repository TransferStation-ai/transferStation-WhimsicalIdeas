package transferstation.transferstation_whimsicalideas.client.model;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SourceModelData {

    public static class BoneInfo {
        public final String name;
        public final float[] pos;
        public final float[] quat;
        public final float[] rot;
        public final int parent;

        public BoneInfo(String name, float[] pos, float[] quat, float[] rot, int parent) {
            this.name = name;
            this.pos = pos;
            this.quat = quat;
            this.rot = rot;
            this.parent = parent;
        }

        public BoneInfo(String name, float[] pos, int parent) {
            this(name, pos, null, null, parent);
        }
    }

    public static class BodyPartInfo {
        public final String name;
        public final int numModels;
        public final int baseIndex;
        public final List<String> modelNames = new ArrayList<>();

        public BodyPartInfo(String name, int numModels, int baseIndex) {
            this.name = name;
            this.numModels = numModels;
            this.baseIndex = baseIndex;
        }
    }

    public static class MeshTextureInfo {
        public static MeshTextureInfo simple(ResourceLocation texture, String vtfKey) {
            return new MeshTextureInfo(texture, null, null, null, null, null, null,
                false, false, false, false, false, false, 0f, null, null,
                vtfKey, null, 1f, null, 0);
        }

        public static MeshTextureInfo simple(ResourceLocation texture, ResourceLocation normalMap,
                                              boolean translucent, boolean alphaTest, boolean noCull,
                                              String vtfKey, float[] colorTint) {
            return new MeshTextureInfo(texture, normalMap, null, null, null, null, null,
                translucent, alphaTest, noCull, false, false, false, 0f, null, null,
                vtfKey, colorTint, 1f, null, 0);
        }

        public final ResourceLocation texture;
        public final ResourceLocation normalMap;
        public final ResourceLocation ssbumpMap;
        public final ResourceLocation envMapMask;
        public final ResourceLocation parallaxMap;
        public final ResourceLocation detailMap;
        public final ResourceLocation selfIllumMask;
        public final boolean translucent;
        public final boolean alphaTest;
        public final boolean noCull;
        public final boolean selfIllum;
        public final boolean hasPhong;
        public final boolean halfLambert;
        public final float phongBoost;
        public final float[] phongFresnelRanges;
        public final ResourceLocation phongExponentTexture;
        public final String vtfKey;
        public float[] colorTint;
        public float alpha;
        public final String surfaceProp;
        public final int detailBlendMode;

        public MeshTextureInfo(ResourceLocation texture, ResourceLocation normalMap,
                                ResourceLocation ssbumpMap, ResourceLocation envMapMask,
                                ResourceLocation parallaxMap, ResourceLocation detailMap,
                                ResourceLocation selfIllumMask,
                                boolean translucent, boolean alphaTest, boolean noCull,
                                boolean selfIllum, boolean hasPhong, boolean halfLambert,
                                float phongBoost, float[] phongFresnelRanges,
                                ResourceLocation phongExponentTexture,
                                String vtfKey, float[] colorTint, float alpha,
                                String surfaceProp, int detailBlendMode) {
            this.texture = texture;
            this.normalMap = normalMap;
            this.ssbumpMap = ssbumpMap;
            this.envMapMask = envMapMask;
            this.parallaxMap = parallaxMap;
            this.detailMap = detailMap;
            this.selfIllumMask = selfIllumMask;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.selfIllum = selfIllum;
            this.hasPhong = hasPhong;
            this.halfLambert = halfLambert;
            this.phongBoost = phongBoost;
            this.phongFresnelRanges = phongFresnelRanges;
            this.phongExponentTexture = phongExponentTexture;
            this.vtfKey = vtfKey;
            this.colorTint = colorTint;
            this.alpha = alpha;
            this.surfaceProp = surfaceProp;
            this.detailBlendMode = detailBlendMode;
        }
    }

    public static class MeshData {
        public final float[] vertices;
        public final int[] indices;
        // Bone weight data for GPU skinning (4 weights + 4 bone indices per vertex)
        public final float[] boneWeights;
        public final int[] boneIndices;
        public final ResourceLocation texture;
        public final ResourceLocation normalMap;
        public final ResourceLocation ssbumpMap;
        public final ResourceLocation envMapMask;
        public final ResourceLocation parallaxMap;
        public final ResourceLocation detailMap;
        public final ResourceLocation selfIllumMask;
        public final boolean translucent;
        public final boolean alphaTest;
        public final boolean noCull;
        public final boolean selfIllum;
        public final boolean hasPhong;
        public final boolean halfLambert;
        public final float phongBoost;
        public final float[] phongFresnelRanges;
        public final ResourceLocation phongExponentTexture;
        public final int bodyPartIndex;
        public final int modelIndex;
        public final int materialIndex;
        public final String vtfKey;
        public float[] colorTint;
        public float alpha;
        public final String surfaceProp;
        public final int detailBlendMode;

        private MeshData(Builder builder) {
            this.vertices = builder.vertices;
            this.indices = builder.indices;
            this.boneWeights = builder.boneWeights;
            this.boneIndices = builder.boneIndices;
            this.texture = builder.texture;
            this.normalMap = builder.normalMap;
            this.ssbumpMap = builder.ssbumpMap;
            this.envMapMask = builder.envMapMask;
            this.parallaxMap = builder.parallaxMap;
            this.detailMap = builder.detailMap;
            this.selfIllumMask = builder.selfIllumMask;
            this.translucent = builder.translucent;
            this.alphaTest = builder.alphaTest;
            this.noCull = builder.noCull;
            this.selfIllum = builder.selfIllum;
            this.hasPhong = builder.hasPhong;
            this.halfLambert = builder.halfLambert;
            this.phongBoost = builder.phongBoost;
            this.phongFresnelRanges = builder.phongFresnelRanges;
            this.phongExponentTexture = builder.phongExponentTexture;
            this.bodyPartIndex = builder.bodyPartIndex;
            this.modelIndex = builder.modelIndex;
            this.materialIndex = builder.materialIndex;
            this.vtfKey = builder.vtfKey;
            this.colorTint = builder.colorTint;
            this.alpha = builder.alpha;
            this.surfaceProp = builder.surfaceProp;
            this.detailBlendMode = builder.detailBlendMode;
        }

        public int vertexCount() { return vertices.length / 8; }
        public int indexCount() { return indices.length; }

        // Check if vertex and index arrays contain valid data
        public boolean isValid() {
            return vertices != null && vertices.length >= 8 && indices != null && indices.length >= 3;
        }

        // Estimate triangle count for LOD weighting
        public int triangleCount() { return indices.length / 3; }

        public static class Builder {
            private float[] vertices;
            private int[] indices;
            private float[] boneWeights;
            private int[] boneIndices;
            private ResourceLocation texture;
            private ResourceLocation normalMap;
            private ResourceLocation ssbumpMap;
            private ResourceLocation envMapMask;
            private ResourceLocation parallaxMap;
            private ResourceLocation detailMap;
            private ResourceLocation selfIllumMask;
            private boolean translucent;
            private boolean alphaTest;
            private boolean noCull;
            private boolean selfIllum;
            private boolean hasPhong;
            private boolean halfLambert;
            private float phongBoost;
            private float[] phongFresnelRanges;
            private ResourceLocation phongExponentTexture;
            private int bodyPartIndex = -1;
            private int modelIndex = -1;
            private int materialIndex = -1;
            private String vtfKey;
            private float[] colorTint;
            private float alpha = 1.0f;
            private String surfaceProp;
            private int detailBlendMode;

            public Builder vertices(float[] vertices) { this.vertices = vertices; return this; }
            public Builder indices(int[] indices) { this.indices = indices; return this; }
            public Builder boneWeights(float[] boneWeights) { this.boneWeights = boneWeights; return this; }
            public Builder boneIndices(int[] boneIndices) { this.boneIndices = boneIndices; return this; }
            public Builder texture(ResourceLocation texture) { this.texture = texture; return this; }
            public Builder normalMap(ResourceLocation normalMap) { this.normalMap = normalMap; return this; }
            public Builder ssbumpMap(ResourceLocation ssbumpMap) { this.ssbumpMap = ssbumpMap; return this; }
            public Builder envMapMask(ResourceLocation envMapMask) { this.envMapMask = envMapMask; return this; }
            public Builder parallaxMap(ResourceLocation parallaxMap) { this.parallaxMap = parallaxMap; return this; }
            public Builder detailMap(ResourceLocation detailMap) { this.detailMap = detailMap; return this; }
            public Builder selfIllumMask(ResourceLocation selfIllumMask) { this.selfIllumMask = selfIllumMask; return this; }
            public Builder translucent(boolean translucent) { this.translucent = translucent; return this; }
            public Builder alphaTest(boolean alphaTest) { this.alphaTest = alphaTest; return this; }
            public Builder noCull(boolean noCull) { this.noCull = noCull; return this; }
            public Builder selfIllum(boolean selfIllum) { this.selfIllum = selfIllum; return this; }
            public Builder hasPhong(boolean hasPhong) { this.hasPhong = hasPhong; return this; }
            public Builder halfLambert(boolean halfLambert) { this.halfLambert = halfLambert; return this; }
            public Builder phongBoost(float phongBoost) { this.phongBoost = phongBoost; return this; }
            public Builder phongFresnelRanges(float[] phongFresnelRanges) { this.phongFresnelRanges = phongFresnelRanges; return this; }
            public Builder phongExponentTexture(ResourceLocation phongExponentTexture) { this.phongExponentTexture = phongExponentTexture; return this; }
            public Builder bodyPartIndex(int bodyPartIndex) { this.bodyPartIndex = bodyPartIndex; return this; }
            public Builder modelIndex(int modelIndex) { this.modelIndex = modelIndex; return this; }
            public Builder materialIndex(int materialIndex) { this.materialIndex = materialIndex; return this; }
            public Builder vtfKey(String vtfKey) { this.vtfKey = vtfKey; return this; }
            public Builder colorTint(float[] colorTint) { this.colorTint = colorTint; return this; }
            public Builder alpha(float alpha) { this.alpha = alpha; return this; }
            public Builder surfaceProp(String surfaceProp) { this.surfaceProp = surfaceProp; return this; }
            public Builder detailBlendMode(int detailBlendMode) { this.detailBlendMode = detailBlendMode; return this; }

            public MeshData build() {
                return new MeshData(this);
            }
        }
    }

    public final List<MeshData> meshes = new ArrayList<>();
    public float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
    public float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    public float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    public float modelScale = 1.0f;
    public String name = "";

    // Id of the soft-body simulation registered with PhysicsSimulationManager when this
    // model was loaded (null if no .phy file / physics disabled). Unregistered on cache clear
    // to avoid leaking simulations that are never fed back into rendering.
    public String physicsSimId = null;

    public final List<BoneInfo> bones = new ArrayList<>();
    public final List<BodyPartInfo> bodyParts = new ArrayList<>();
    public int numSkinRef = 0;
    public int numSkinFamilies = 0;
    public final List<Integer> skinTable = new ArrayList<>();
    public int currentSkinFamily = 0;

    // LOD mesh lists - populated with reduced-detail versions for each level
    public final List<MeshData> lodMeshes1 = new ArrayList<>();
    public final List<MeshData> lodMeshes2 = new ArrayList<>();
    public final List<MeshData> lodMeshes3 = new ArrayList<>();

    // Extended model metadata
    public final List<MdlDataTypes.Attachment> attachments = new ArrayList<>();
    public final List<MdlDataTypes.BoneController> boneControllers = new ArrayList<>();
    public final List<MdlDataTypes.HitboxSet> hitboxSets = new ArrayList<>();
    public final List<MdlDataTypes.SeqDesc> sequences = new ArrayList<>();
    public final List<MdlDataTypes.IKChain> ikChains = new ArrayList<>();
    public final List<MdlDataTypes.FlexDesc> flexDescs = new ArrayList<>();
    public final List<MdlDataTypes.FlexController> flexControllers = new ArrayList<>();
    public final List<MdlDataTypes.FlexRule> flexRules = new ArrayList<>();

    // Extended parsing data
    public final List<MdlDataTypes.LocalAnim> localAnims = new ArrayList<>();
    public final List<MdlDataTypes.PoseParam> poseParams = new ArrayList<>();
    public final List<MdlDataTypes.LocalNode> localNodes = new ArrayList<>();
    public final List<MdlDataTypes.IKAutoplayLock> ikAutoplayLocks = new ArrayList<>();
    public final List<MdlDataTypes.Mouth> mouths = new ArrayList<>();
    public String keyValues;
    public String surfaceProp;

    // Skin replacement data
    public MdlDataTypes.Hdr2 hdr2;

    // Reference and A-pose sequence data
    public final List<MdlDataTypes.SrcBoneTransform> srcBoneTransforms = new ArrayList<>();
    public final List<MdlDataTypes.SequenceAnimData> sequenceAnimData = new ArrayList<>();
    public final List<Integer> referenceSequenceIndices = new ArrayList<>();
    public final List<Integer> aPoseSequenceIndices = new ArrayList<>();

    public boolean hasReferencePose() {
        return !referenceSequenceIndices.isEmpty() || !srcBoneTransforms.isEmpty();
    }

    public boolean hasAPose() {
        return !aPoseSequenceIndices.isEmpty();
    }

    public MdlDataTypes.AnimFrameData getReferenceFrameData() {
        for (int idx : referenceSequenceIndices) {
            if (idx >= 0 && idx < sequenceAnimData.size()) {
                MdlDataTypes.SequenceAnimData sa = sequenceAnimData.get(idx);
                MdlDataTypes.AnimFrameData f = sa.getFrame(0);
                if (f != null && !f.boneTransforms.isEmpty()) return f;
            }
        }
        // Fallback: use first sequence that has frame data
        for (MdlDataTypes.SequenceAnimData sa : sequenceAnimData) {
            MdlDataTypes.AnimFrameData f = sa.getFrame(0);
            if (f != null && !f.boneTransforms.isEmpty()) return f;
        }
        return null;
    }

    public MdlDataTypes.AnimFrameData getAPoseFrameData() {
        for (int idx : aPoseSequenceIndices) {
            if (idx >= 0 && idx < sequenceAnimData.size()) {
                MdlDataTypes.SequenceAnimData sa = sequenceAnimData.get(idx);
                MdlDataTypes.AnimFrameData f = sa.getFrame(0);
                if (f != null && !f.boneTransforms.isEmpty()) return f;
            }
        }
        return null;
    }

    public float[] getReferenceBonePos(int boneIndex) {
        if (boneIndex >= 0 && boneIndex < srcBoneTransforms.size()) {
            return srcBoneTransforms.get(boneIndex).pos;
        }
        return null;
    }

    public float[] getReferenceBoneQuat(int boneIndex) {
        if (boneIndex >= 0 && boneIndex < srcBoneTransforms.size()) {
            return srcBoneTransforms.get(boneIndex).quat;
        }
        return null;
    }

    public List<String> getReferenceSequenceNames() {
        List<String> names = new ArrayList<>();
        for (int idx : referenceSequenceIndices) {
            if (idx >= 0 && idx < sequences.size()) {
                names.add(sequences.get(idx).label);
            }
        }
        return names;
    }

    public List<String> getAPoseSequenceNames() {
        List<String> names = new ArrayList<>();
        for (int idx : aPoseSequenceIndices) {
            if (idx >= 0 && idx < sequences.size()) {
                names.add(sequences.get(idx).label);
            }
        }
        return names;
    }

    public int totalVertices() {
        int count = 0;
        for (MeshData m : meshes) count += m.vertexCount();
        return count;
    }

    public int totalTriangles() {
        int count = 0;
        for (MeshData m : meshes) count += m.indices.length / 3;
        return count;
    }

    /**
     * Returns a LOD-reduced copy of this model data.
     * LOD 1 = remove 50% of triangles, LOD 2 = remove 75%, LOD 3 = remove 90%.
     */
    public SourceModelData getMeshesForLod(int lodLevel) {
        if (lodLevel <= 0) {
            return copyFullMeshData();
        }

        // Use pre-built LOD lists if available
        if (lodLevel == 1 && !lodMeshes1.isEmpty()) {
            return copyWithMeshes(lodMeshes1);
        }
        if (lodLevel == 2 && !lodMeshes2.isEmpty()) {
            return copyWithMeshes(lodMeshes2);
        }
        if (lodLevel == 3 && !lodMeshes3.isEmpty()) {
            return copyWithMeshes(lodMeshes3);
        }

        // Dynamically reduce triangle count by decimation
        SourceModelData result = copyFullMeshData();
        result.meshes.clear();
        for (MeshData mesh : this.meshes) {
            MeshData reduced = decimateMesh(mesh, lodLevel);
            if (reduced != null) {
                result.meshes.add(reduced);
            }
        }
        return result;
    }

    private SourceModelData copyFullMeshData() {
        SourceModelData result = new SourceModelData();
        result.name = this.name;
        result.modelScale = this.modelScale;
        result.minX = this.minX; result.maxX = this.maxX;
        result.minY = this.minY; result.maxY = this.maxY;
        result.minZ = this.minZ; result.maxZ = this.maxZ;
        result.bones.addAll(this.bones);
        result.bodyParts.addAll(this.bodyParts);
        result.numSkinRef = this.numSkinRef;
        result.numSkinFamilies = this.numSkinFamilies;
        result.skinTable.addAll(this.skinTable);
        result.currentSkinFamily = this.currentSkinFamily;
        result.attachments.addAll(this.attachments);
        result.boneControllers.addAll(this.boneControllers);
        result.hitboxSets.addAll(this.hitboxSets);
        result.sequences.addAll(this.sequences);
        result.ikChains.addAll(this.ikChains);
        result.flexDescs.addAll(this.flexDescs);
        result.flexControllers.addAll(this.flexControllers);
        result.flexRules.addAll(this.flexRules);
        result.localAnims.addAll(this.localAnims);
        result.poseParams.addAll(this.poseParams);
        result.localNodes.addAll(this.localNodes);
        result.ikAutoplayLocks.addAll(this.ikAutoplayLocks);
        result.mouths.addAll(this.mouths);
        result.keyValues = this.keyValues;
        result.surfaceProp = this.surfaceProp;
        result.hdr2 = this.hdr2;
        result.meshes.addAll(this.meshes);
        result.srcBoneTransforms.addAll(this.srcBoneTransforms);
        result.sequenceAnimData.addAll(this.sequenceAnimData);
        result.referenceSequenceIndices.addAll(this.referenceSequenceIndices);
        result.aPoseSequenceIndices.addAll(this.aPoseSequenceIndices);
        return result;
    }

    private SourceModelData copyWithMeshes(List<MeshData> lodMeshes) {
        SourceModelData result = copyFullMeshData();
        result.meshes.clear();
        result.meshes.addAll(lodMeshes);
        return result;
    }

    /**
     * Simple mesh decimation: keep every Nth triangle.
     * LOD 1: keep 50% (stride 2), LOD 2: keep 25% (stride 4), LOD 3: keep 10% (stride 10).
     */
    private static MeshData decimateMesh(MeshData original, int lodLevel) {
        int stride = switch (lodLevel) {
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 10;
            default -> 1;
        };

        if (stride <= 1) return original;

        int triCount = original.indices.length / 3;
        int newTriCount = (triCount + stride - 1) / stride;

        if (newTriCount < 1) return null;

        // Build a set of vertex indices used in kept triangles
        boolean[] vertexUsed = new boolean[original.vertexCount()];
        int[] newIndices = new int[newTriCount * 3];
        int outIdx = 0;
        for (int t = 0; t < triCount; t += stride) {
            int i0 = original.indices[t * 3];
            int i1 = original.indices[t * 3 + 1];
            int i2 = original.indices[t * 3 + 2];
            if (i0 >= 0 && i0 < vertexUsed.length &&
                i1 >= 0 && i1 < vertexUsed.length &&
                i2 >= 0 && i2 < vertexUsed.length) {
                vertexUsed[i0] = true;
                vertexUsed[i1] = true;
                vertexUsed[i2] = true;
                newIndices[outIdx++] = i0;
                newIndices[outIdx++] = i1;
                newIndices[outIdx++] = i2;
            }
        }

        if (outIdx < 3) return null;

        // Compact vertex array
        int[] vertexRemap = new int[original.vertexCount()];
        int newVertCount = 0;
        for (int i = 0; i < original.vertexCount(); i++) {
            if (vertexUsed[i]) {
                vertexRemap[i] = newVertCount++;
            } else {
                vertexRemap[i] = -1;
            }
        }

        float[] newVerts = new float[newVertCount * 8];
        for (int i = 0; i < original.vertexCount(); i++) {
            if (vertexUsed[i]) {
                System.arraycopy(original.vertices, i * 8, newVerts, vertexRemap[i] * 8, 8);
            }
        }

        // Remap indices
        int[] finalIndices = new int[outIdx];
        for (int i = 0; i < outIdx; i++) {
            finalIndices[i] = vertexRemap[newIndices[i]];
        }

        // Compact bone weight data (4 weights + 4 bone indices per vertex)
        float[] newBoneWeights = null;
        int[] newBoneIndices = null;
        if (original.boneWeights != null && original.boneIndices != null) {
            newBoneWeights = new float[newVertCount * 4];
            newBoneIndices = new int[newVertCount * 4];
            for (int i = 0; i < original.vertexCount(); i++) {
                if (vertexUsed[i]) {
                    int newIdx = vertexRemap[i];
                    System.arraycopy(original.boneWeights, i * 4, newBoneWeights, newIdx * 4, 4);
                    System.arraycopy(original.boneIndices, i * 4, newBoneIndices, newIdx * 4, 4);
                }
            }
        }

        return new MeshData.Builder()
            .vertices(newVerts)
            .indices(finalIndices)
            .boneWeights(newBoneWeights)
            .boneIndices(newBoneIndices)
            .texture(original.texture)
            .normalMap(original.normalMap)
            .ssbumpMap(original.ssbumpMap)
            .envMapMask(original.envMapMask)
            .parallaxMap(original.parallaxMap)
            .detailMap(original.detailMap)
            .selfIllumMask(original.selfIllumMask)
            .translucent(original.translucent)
            .alphaTest(original.alphaTest)
            .noCull(original.noCull)
            .selfIllum(original.selfIllum)
            .hasPhong(original.hasPhong)
            .halfLambert(original.halfLambert)
            .phongBoost(original.phongBoost)
            .phongFresnelRanges(original.phongFresnelRanges)
            .phongExponentTexture(original.phongExponentTexture)
            .bodyPartIndex(original.bodyPartIndex)
            .modelIndex(original.modelIndex)
            .materialIndex(original.materialIndex)
            .vtfKey(original.vtfKey)
            .colorTint(original.colorTint)
            .alpha(original.alpha)
            .surfaceProp(original.surfaceProp)
            .detailBlendMode(original.detailBlendMode)
            .build();
    }

    public int getSkinTextureIndex(int materialIdx, int skinFamily) {
        if (skinTable.isEmpty() || numSkinRef <= 0) return materialIdx;
        int wrapped = materialIdx >= 0 ? materialIdx % numSkinRef : 0;
        int tableIdx = skinFamily * numSkinRef + wrapped;
        if (tableIdx >= 0 && tableIdx < skinTable.size()) {
            int baseTex = skinTable.get(tableIdx);
            if (hdr2 != null && hdr2.hasData && hdr2.skinReplacementTables != null
                && materialIdx >= 0 && materialIdx < numSkinRef) {
                for (int r = 0; r < hdr2.skinReplacementTables.length; r += 2) {
                    if (hdr2.skinReplacementTables[r] == materialIdx) {
                        return hdr2.skinReplacementTables[r + 1];
                    }
                }
            }
            return baseTex;
        }
        return materialIdx;
    }
}
