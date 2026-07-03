package transferstation.transferstation_whimsicalideas.client.model;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

public class SourceModelData {

    public static class BodyPartInfo {
        public String name;
        public int numModels;
        public int baseIndex;
        public List<String> modelNames = new ArrayList<>();

        public BodyPartInfo(String name, int numModels, int baseIndex) {
            this.name = name;
            this.numModels = numModels;
            this.baseIndex = baseIndex;
        }
    }

    public static class MeshTextureInfo {
        public ResourceLocation texture;
        public boolean translucent;
        public boolean alphaTest;
        public boolean noCull;
        public String vtfKey;
        public float[] colorTint;

        public MeshTextureInfo(ResourceLocation texture, boolean translucent, boolean alphaTest, boolean noCull) {
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.colorTint = null;
        }

        public MeshTextureInfo(ResourceLocation texture, boolean translucent, boolean alphaTest, boolean noCull, String vtfKey) {
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.vtfKey = vtfKey;
            this.colorTint = null;
        }

        public MeshTextureInfo(ResourceLocation texture, boolean translucent, boolean alphaTest, boolean noCull, String vtfKey, float[] colorTint) {
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.vtfKey = vtfKey;
            this.colorTint = colorTint;
        }
    }

    public static class MeshData {
        public float[] vertices;
        public int[] indices;
        public ResourceLocation texture;
        public boolean translucent;
        public boolean alphaTest;
        public boolean noCull;
        public int bodyPartIndex;
        public int modelIndex;
        public int materialIndex;
        public String vtfKey;
        public float[] colorTint;

        public MeshData(float[] vertices, int[] indices, ResourceLocation texture, boolean translucent) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = false;
            this.noCull = false;
            this.bodyPartIndex = -1;
            this.modelIndex = -1;
            this.materialIndex = -1;
            this.colorTint = null;
        }

        public MeshData(float[] vertices, int[] indices, ResourceLocation texture, boolean translucent,
                        int bodyPartIndex, int modelIndex, int materialIndex) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = false;
            this.noCull = false;
            this.bodyPartIndex = bodyPartIndex;
            this.modelIndex = modelIndex;
            this.materialIndex = materialIndex;
            this.colorTint = null;
        }

        public MeshData(float[] vertices, int[] indices, ResourceLocation texture,
                        boolean translucent, boolean alphaTest, boolean noCull,
                        int bodyPartIndex, int modelIndex, int materialIndex) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.bodyPartIndex = bodyPartIndex;
            this.modelIndex = modelIndex;
            this.materialIndex = materialIndex;
            this.colorTint = null;
        }

        public MeshData(float[] vertices, int[] indices, ResourceLocation texture,
                        boolean translucent, boolean alphaTest, boolean noCull,
                        int bodyPartIndex, int modelIndex, int materialIndex, String vtfKey) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.bodyPartIndex = bodyPartIndex;
            this.modelIndex = modelIndex;
            this.materialIndex = materialIndex;
            this.vtfKey = vtfKey;
            this.colorTint = null;
        }

        public MeshData(float[] vertices, int[] indices, ResourceLocation texture,
                        boolean translucent, boolean alphaTest, boolean noCull,
                        int bodyPartIndex, int modelIndex, int materialIndex, String vtfKey,
                        float[] colorTint) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.translucent = translucent;
            this.alphaTest = alphaTest;
            this.noCull = noCull;
            this.bodyPartIndex = bodyPartIndex;
            this.modelIndex = modelIndex;
            this.materialIndex = materialIndex;
            this.vtfKey = vtfKey;
            this.colorTint = colorTint;
        }

        public int vertexCount() { return vertices.length / 8; }
        public int indexCount() { return indices.length; }
    }

    public List<MeshData> meshes = new ArrayList<>();
    public float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
    public float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    public float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    public float modelScale = 1.0f;
    public String name = "";

    public List<BodyPartInfo> bodyParts = new ArrayList<>();
    public int numSkinRef = 0;
    public int numSkinFamilies = 0;
    public List<Integer> skinTable = new ArrayList<>();
    public int currentSkinFamily = 0;

    public List<MeshData> lodMeshes1 = new ArrayList<>();
    public List<MeshData> lodMeshes2 = new ArrayList<>();
    public List<MeshData> lodMeshes3 = new ArrayList<>();

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

    public SourceModelData getMeshesForLod(int lodLevel) {
        SourceModelData result = new SourceModelData();
        result.name = this.name;
        result.modelScale = this.modelScale;
        result.minX = this.minX; result.maxX = this.maxX;
        result.minY = this.minY; result.maxY = this.maxY;
        result.minZ = this.minZ; result.maxZ = this.maxZ;
        result.bodyParts.addAll(this.bodyParts);
        result.numSkinRef = this.numSkinRef;
        result.numSkinFamilies = this.numSkinFamilies;
        result.skinTable.addAll(this.skinTable);
        result.currentSkinFamily = this.currentSkinFamily;

        // LOD mesh lists are not populated during loading (Java path doesn't use LOD).
        // Always use base meshes as fallback since native renderer handles its own LOD.
        result.meshes.addAll(this.meshes);
        return result;
    }

    public int getSkinTextureIndex(int materialIdx, int skinFamily) {
        if (skinTable.isEmpty() || numSkinRef <= 0) return materialIdx;
        int wrapped = materialIdx >= 0 ? materialIdx % numSkinRef : 0;
        int tableIdx = skinFamily * numSkinRef + wrapped;
        if (tableIdx >= 0 && tableIdx < skinTable.size()) {
            return skinTable.get(tableIdx);
        }
        return materialIdx;
    }
}
