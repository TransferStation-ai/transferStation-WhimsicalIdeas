#ifndef GL_RENDERER_H
#define GL_RENDERER_H

#include "studio_types.h"
#include <cstdint>
#include <vector>
#include <unordered_map>

class GlRenderer {
public:
    struct GlMesh {
        uint32_t vao;
        uint32_t vbo;
        uint32_t ebo;
        int indexCount;
        uint32_t textureId;
        bool valid;
    };

    static bool initialize();

    static uint32_t buildMesh(const std::vector<MeshVertex>& vertices, const std::vector<uint32_t>& indices);
    static void destroyMesh(uint32_t vao);

    static uint32_t uploadTexture(const std::vector<uint8_t>& rgbaData, int width, int height);
    static void destroyTexture(uint32_t textureId);

    static void renderMesh(uint32_t vao, int indexCount, uint32_t textureId,
                           const float* modelMatrix, int packedLight,
                           const float* colorTint = nullptr);

    static void shutdown();

private:
    static const char* VERTEX_SHADER_SOURCE;
    static const char* FRAGMENT_SHADER_SOURCE;
    static uint32_t s_program;
    static bool s_initialized;

    static uint32_t compileShader(uint32_t type, const char* source);
    static uint32_t linkProgram(uint32_t vs, uint32_t fs);
};

#endif // GL_RENDERER_H
