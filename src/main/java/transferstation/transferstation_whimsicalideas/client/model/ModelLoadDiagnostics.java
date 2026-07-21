package transferstation.transferstation_whimsicalideas.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 模型加载完成后生成的诊断信息。
 * 包含从 MDL/VVD/VTX 解析出的元数据，用于调试和监控。
 */
public class ModelLoadDiagnostics {
    public final String modelName;
    public final int mdlVersion;
    public final int numBones;
    public final int numBodyParts;
    public final int numModels;
    public final int numMeshes;
    public final int numTextures;
    public final int numCdTextures;
    public final int numVertices;
    public final int numTriangles;
    public final int numSequences;
    public final int numAnimations;
    public final int numIncludeModels;
    public final int checksumMdl;
    public final int checksumVvd;
    public final int checksumVtx;
    public final String parserStrategy;
    public final long loadTimeMs;
    public final boolean success;
    public final List<String> bodyPartNames;
    public final List<String> textureNames;
    public final List<String> warnings;

    private ModelLoadDiagnostics(Builder b) {
        this.modelName = b.modelName;
        this.mdlVersion = b.mdlVersion;
        this.numBones = b.numBones;
        this.numBodyParts = b.numBodyParts;
        this.numModels = b.numModels;
        this.numMeshes = b.numMeshes;
        this.numTextures = b.numTextures;
        this.numCdTextures = b.numCdTextures;
        this.numVertices = b.numVertices;
        this.numTriangles = b.numTriangles;
        this.numSequences = b.numSequences;
        this.numAnimations = b.numAnimations;
        this.numIncludeModels = b.numIncludeModels;
        this.checksumMdl = b.checksumMdl;
        this.checksumVvd = b.checksumVvd;
        this.checksumVtx = b.checksumVtx;
        this.parserStrategy = b.parserStrategy;
        this.loadTimeMs = b.loadTimeMs;
        this.success = b.success;
        this.bodyPartNames = b.bodyPartNames != null ? List.copyOf(b.bodyPartNames) : List.of();
        this.textureNames = b.textureNames != null ? List.copyOf(b.textureNames) : List.of();
        this.warnings = b.warnings != null ? List.copyOf(b.warnings) : List.of();
    }

    public static class Builder {
        String modelName = "";
        int mdlVersion;
        int numBones;
        int numBodyParts;
        int numModels;
        int numMeshes;
        int numTextures;
        int numCdTextures;
        int numVertices;
        int numTriangles;
        int numSequences;
        int numAnimations;
        int numIncludeModels;
        int checksumMdl = -1;
        int checksumVvd = -1;
        int checksumVtx = -1;
        String parserStrategy = "";
        long loadTimeMs;
        boolean success;
        List<String> bodyPartNames;
        List<String> textureNames;
        List<String> warnings;

        public Builder modelName(String v) { this.modelName = v; return this; }
        public Builder mdlVersion(int v) { this.mdlVersion = v; return this; }
        public Builder numBones(int v) { this.numBones = v; return this; }
        public Builder numBodyParts(int v) { this.numBodyParts = v; return this; }
        public Builder numModels(int v) { this.numModels = v; return this; }
        public Builder numMeshes(int v) { this.numMeshes = v; return this; }
        public Builder numTextures(int v) { this.numTextures = v; return this; }
        public Builder numCdTextures(int v) { this.numCdTextures = v; return this; }
        public Builder numVertices(int v) { this.numVertices = v; return this; }
        public Builder numTriangles(int v) { this.numTriangles = v; return this; }
        public Builder numSequences(int v) { this.numSequences = v; return this; }
        public Builder numAnimations(int v) { this.numAnimations = v; return this; }
        public Builder numIncludeModels(int v) { this.numIncludeModels = v; return this; }
        public Builder checksumMdl(int v) { this.checksumMdl = v; return this; }
        public Builder checksumVvd(int v) { this.checksumVvd = v; return this; }
        public Builder checksumVtx(int v) { this.checksumVtx = v; return this; }
        public Builder parserStrategy(String v) { this.parserStrategy = v; return this; }
        public Builder loadTimeMs(long v) { this.loadTimeMs = v; return this; }
        public Builder success(boolean v) { this.success = v; return this; }
        public Builder bodyPartNames(List<String> v) { this.bodyPartNames = v; return this; }
        public Builder textureNames(List<String> v) { this.textureNames = v; return this; }
        public Builder warnings(List<String> v) { this.warnings = v; return this; }
        public ModelLoadDiagnostics build() { return new ModelLoadDiagnostics(this); }
    }

    public String toSummaryString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(modelName);
        sb.append(" v").append(mdlVersion);
        sb.append(" | ").append(numBones).append(" bones");
        sb.append(", ").append(numBodyParts).append(" bodyparts");
        sb.append(", ").append(numMeshes).append(" meshes");
        sb.append(", ").append(numVertices).append(" verts");
        sb.append(", ").append(numTriangles).append(" tris");
        sb.append(", ").append(numTextures).append(" textures");
        sb.append(" | ").append(parserStrategy);
        sb.append(" | ").append(loadTimeMs).append("ms");
        if (!success) sb.append(" | FAILED");
        return sb.toString();
    }
}
