#ifndef GPU_SKINNING_H
#define GPU_SKINNING_H

#include <cstdint>
#include <vector>

class GpuSkinning {
public:
    struct BoneMatrix {
        float data[16];
    };

    struct SkinnedVertex {
        float position[3];
        float normal[3];
        float texcoord[2];
        uint8_t boneIndices[4];
        float boneWeights[4];
    };

    struct SkinnedMesh {
        uint32_t inputVbo;       // VBO for input vertex data (positions, normals, texcoords, bone data)
        uint32_t outputVbo;      // VBO for output skinned data (positions + normals)
        uint32_t texcoordVbo;    // VBO for static texcoords
        uint32_t renderVao;      // VAO for rendering the skinned output
        int vertexCount;
        int indexCount;       // Real number of indices in the render EBO
        uint32_t ssboInput;      // SSBO for input SkinnedVertex data
        uint32_t ssboOutput;     // SSBO for output vec4 (pos + normal)
        uint32_t ssboBones;      // SSBO for bone matrices
        uint32_t computeProgram;
        uint32_t textureId;      // GL texture id for the skinned mesh
        bool valid;
    };

    static bool isAvailable();
    static bool initialize();

    static SkinnedMesh createSkinnedMesh(
        const std::vector<float>& vertices,
        const std::vector<uint32_t>& indices,
        const std::vector<uint8_t>& boneIndices,
        const std::vector<float>& boneWeights
    );

    static void destroySkinnedMesh(SkinnedMesh& mesh);

    static void skinMesh(
        SkinnedMesh& mesh,
        const BoneMatrix* boneMatrices,
        int boneCount,
        const float* modelMatrix,
        int packedLight
    );

    static void renderSkinnedMesh(
        SkinnedMesh& mesh,
        uint32_t textureId,
        const float* modelMatrix,
        int packedLight,
        const float* colorTint = nullptr
    );

private:
    static bool s_available;
    static bool s_initialized;
    static uint32_t s_computeProgram;
    static uint32_t s_renderProgram;

    static bool loadExtensions();
    static uint32_t compileComputeShader(const char* source);
    static uint32_t compileRenderShader(const char* vertexSrc, const char* fragmentSrc);
};

#endif // GPU_SKINNING_H
