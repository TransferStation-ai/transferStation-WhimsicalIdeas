package transferstation.transferstation_whimsicalideas.client.model;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

public class SourceModelData {

    public static class MeshData {
        public float[] vertices;
        public int[] indices;
        public ResourceLocation texture;
        public boolean translucent;

        public MeshData(float[] vertices, int[] indices, ResourceLocation texture, boolean translucent) {
            this.vertices = vertices;
            this.indices = indices;
            this.texture = texture;
            this.translucent = translucent;
        }

        public int vertexCount() { return vertices.length / 8; }
        public int indexCount() { return indices.length; }
    }

    public List<MeshData> meshes = new ArrayList<>();
    public float minZ = Float.MAX_VALUE;
    public float maxZ = -Float.MAX_VALUE;
    public float modelScale = 1.0f;
    public String name = "";

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
        
        if (lodLevel == 0) {
            result.meshes.addAll(this.meshes);
        } else if (lodLevel == 1) {
            result.meshes.addAll(this.lodMeshes1);
        } else if (lodLevel == 2) {
            result.meshes.addAll(this.lodMeshes2);
        } else if (lodLevel == 3) {
            result.meshes.addAll(this.lodMeshes3);
        }
        return result;
    }
}
