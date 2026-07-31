#include "glad/gl.h"
#include "gpu_skinning.h"
#include "gl_renderer.h"
#include <iostream>
#include <cstring>
#include <vector>
#include <algorithm>
#include <cstddef>

// GL types not provided by glad/gl.h
typedef ptrdiff_t GLsizeiptr;
typedef ptrdiff_t GLintptr;

constexpr unsigned int GL_COMPUTE_SHADER = 0x91B9;
constexpr unsigned int GL_DYNAMIC_READ = 0x88E9;
constexpr unsigned int GL_DYNAMIC_COPY = 0x88EC;
constexpr unsigned int GL_READ_ONLY = 0x88B8;
constexpr unsigned int GL_WRITE_ONLY = 0x88B9;
constexpr unsigned int GL_READ_WRITE = 0x88BA;
constexpr unsigned int GL_SHADER_STORAGE_BUFFER = 0x90D2;
constexpr unsigned int GL_INFO_LOG_LENGTH = 0x8B84;
constexpr unsigned int GL_CURRENT_PROGRAM = 0x8B8D;
constexpr unsigned int GL_ARRAY_BUFFER = 0x8892;
constexpr unsigned int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
constexpr unsigned int GL_STATIC_DRAW = 0x88E4;
constexpr unsigned int GL_DYNAMIC_DRAW = 0x88E8;
constexpr unsigned int GL_STREAM_DRAW = 0x88E0;
constexpr unsigned int GL_FLOAT = 0x1406;
constexpr unsigned int GL_UNSIGNED_INT = 0x1405;
constexpr unsigned int GL_TRIANGLES = 0x0004;
constexpr unsigned int GL_ALL_BARRIER_BITS = 0xFFFFFFFF;
constexpr unsigned int GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT = 0x00000001;
constexpr unsigned int GL_SHADER_STORAGE_BARRIER_BIT = 0x00002000;
constexpr unsigned int GL_MAP_READ_BIT = 0x0001;
constexpr unsigned int GL_MAP_WRITE_BIT = 0x0002;
constexpr unsigned int GL_TEXTURE_2D = 0x0DE1;
constexpr unsigned int GL_ACTIVE_TEXTURE = 0x84E0;
constexpr unsigned int GL_TEXTURE_BINDING_2D = 0x8069;

typedef void (GL_API* PFNGLDISPATCHCOMPUTE)(GLuint, GLuint, GLuint);
typedef void (GL_API* PFNGLMEMORYBARRIER)(GLbitfield);
typedef void (GL_API* PFNGLGENBUFFERS)(GLsizei, GLuint*);
typedef void (GL_API* PFNGLDELETEBUFFERS)(GLsizei, const GLuint*);
typedef void (GL_API* PFNGLBINDBUFFER)(GLenum, GLuint);
typedef void (GL_API* PFNGLBUFFERDATA)(GLenum, GLsizeiptr, const void*, GLenum);
typedef void (GL_API* PFNGLBUFFERSUBDATA)(GLenum, GLintptr, GLsizeiptr, const void*);
typedef void* (GL_API* PFNGLMAPBUFFERRANGE)(GLenum, GLintptr, GLsizeiptr, GLbitfield);
typedef GLboolean (GL_API* PFNGLUNMAPBUFFER)(GLenum);
typedef GLuint (GL_API* PFNGLCREATESHADER)(GLenum);
typedef void (GL_API* PFNGLSHADERSOURCE)(GLuint, GLsizei, const char**, const GLint*);
typedef void (GL_API* PFNGLCOMPILESHADER)(GLuint);
typedef void (GL_API* PFNGLGETSHADERIV)(GLuint, GLenum, GLint*);
typedef void (GL_API* PFNGLGETSHADERINFOLOG)(GLuint, GLsizei, GLsizei*, char*);
typedef GLuint (GL_API* PFNGLCREATEPROGRAM)(void);
typedef void (GL_API* PFNGLATTACHSHADER)(GLuint, GLuint);
typedef void (GL_API* PFNGLLINKPROGRAM)(GLuint);
typedef void (GL_API* PFNGLGETPROGRAMIV)(GLuint, GLenum, GLint*);
typedef void (GL_API* PFNGLGETPROGRAMINFOLOG)(GLuint, GLsizei, GLsizei*, char*);
typedef void (GL_API* PFNGLDELETESHADER)(GLuint);
typedef void (GL_API* PFNGLDELETEPROGRAM)(GLuint);
typedef void (GL_API* PFNGLUSEPROGRAM)(GLuint);
typedef GLint (GL_API* PFNGLGETUNIFORMLOCATION)(GLuint, const char*);
typedef void (GL_API* PFNGLUNIFORM1I)(GLint, GLint);
typedef void (GL_API* PFNGLUNIFORMMATRIX4FV)(GLint, GLsizei, GLboolean, const GLfloat*);
typedef void (GL_API* PFNGLBINDBUFFERBASE)(GLenum, GLuint, GLuint);
typedef void (GL_API* PFNGLENABLEVERTEXATTRIBARRAY)(GLuint);
typedef void (GL_API* PFNGLVERTEXATTRIBPOINTER)(GLuint, GLint, GLenum, GLboolean, GLsizei, const void*);
typedef void (GL_API* PFNGLGENVERTEXARRAYS)(GLsizei, GLuint*);
typedef void (GL_API* PFNGLBINDVERTEXARRAY)(GLuint);
typedef void (GL_API* PFNGLDELETEVERTEXARRAYS)(GLsizei, const GLuint*);
typedef void (GL_API* PFNGLDRAWELEMENTS)(GLenum, GLsizei, GLenum, const void*);
typedef void (GL_API* PFNGLACTIVETEXTURE)(GLenum);
typedef void (GL_API* PFNGLBINDTEXTURE)(GLenum, GLuint);
typedef void (GL_API* PFNGLGETINTEGERV)(GLenum, GLint*);
typedef void (GL_API* PFNGLUNIFORM3FV)(GLint, GLsizei, const GLfloat*);
typedef void (GL_API* PFNGLUNIFORM4FV)(GLint, GLsizei, const GLfloat*);
typedef void (GL_API* PFNGLUNIFORM1F)(GLint, GLfloat);

static PFNGLDISPATCHCOMPUTE glDispatchCompute = nullptr;
static PFNGLMEMORYBARRIER glMemoryBarrier = nullptr;
static PFNGLGENBUFFERS glGenBuffers = nullptr;
static PFNGLDELETEBUFFERS glDeleteBuffers = nullptr;
static PFNGLBINDBUFFER glBindBuffer = nullptr;
static PFNGLBUFFERDATA glBufferData = nullptr;
static PFNGLBUFFERSUBDATA glBufferSubData = nullptr;
static PFNGLMAPBUFFERRANGE glMapBufferRange = nullptr;
static PFNGLUNMAPBUFFER glUnmapBuffer = nullptr;
static PFNGLCREATESHADER glCreateShader = nullptr;
static PFNGLSHADERSOURCE glShaderSource = nullptr;
static PFNGLCOMPILESHADER glCompileShader = nullptr;
static PFNGLGETSHADERIV glGetShaderiv = nullptr;
static PFNGLGETSHADERINFOLOG glGetShaderInfoLog = nullptr;
static PFNGLCREATEPROGRAM glCreateProgram = nullptr;
static PFNGLATTACHSHADER glAttachShader = nullptr;
static PFNGLLINKPROGRAM glLinkProgram = nullptr;
static PFNGLGETPROGRAMIV glGetProgramiv = nullptr;
static PFNGLGETPROGRAMINFOLOG glGetProgramInfoLog = nullptr;
static PFNGLDELETESHADER glDeleteShader = nullptr;
static PFNGLDELETEPROGRAM glDeleteProgram = nullptr;
static PFNGLUSEPROGRAM glUseProgram2 = nullptr;
static PFNGLGETUNIFORMLOCATION glGetUniformLocation2 = nullptr;
static PFNGLUNIFORM1I glUniform1i2 = nullptr;
static PFNGLUNIFORMMATRIX4FV glUniformMatrix4fv2 = nullptr;
static PFNGLBINDBUFFERBASE glBindBufferBase = nullptr;
static PFNGLENABLEVERTEXATTRIBARRAY glEnableVertexAttribArray2 = nullptr;
static PFNGLVERTEXATTRIBPOINTER glVertexAttribPointer2 = nullptr;
static PFNGLGENVERTEXARRAYS glGenVertexArrays2 = nullptr;
static PFNGLBINDVERTEXARRAY glBindVertexArray2 = nullptr;
static PFNGLDELETEVERTEXARRAYS glDeleteVertexArrays2 = nullptr;
static PFNGLDRAWELEMENTS glDrawElements2 = nullptr;
static PFNGLACTIVETEXTURE glActiveTexture2 = nullptr;
static PFNGLBINDTEXTURE glBindTexture2 = nullptr;
static PFNGLGETINTEGERV glGetIntegerv2 = nullptr;
static PFNGLUNIFORM3FV glUniform3fv2 = nullptr;
static PFNGLUNIFORM4FV glUniform4fv2 = nullptr;
static PFNGLUNIFORM1F glUniform1f2 = nullptr;

bool GpuSkinning::s_available = false;
bool GpuSkinning::s_initialized = false;
uint32_t GpuSkinning::s_computeProgram = 0;
uint32_t GpuSkinning::s_renderProgram = 0;

static const char* COMPUTE_SHADER_SOURCE = R"(
#version 430 core

layout(local_size_x = 256) in;

struct SkinnedVertex {
    vec3 position;
    vec3 normal;
    vec2 texcoord;
    uvec4 boneIndices;
    vec4 boneWeights;
};

layout(std430, binding = 0) buffer InputBuffer {
    SkinnedVertex inputVertices[];
};

layout(std430, binding = 1) buffer OutputBuffer {
    vec4 outputVertices[];
};

layout(std430, binding = 2) buffer BoneBuffer {
    mat4 boneMatrices[];
};

uniform mat4 u_modelMatrix;

void main() {
    uint idx = gl_GlobalInvocationID.x;
    if (idx >= inputVertices.length()) return;

    SkinnedVertex vert = inputVertices[idx];
    
    mat4 skinMatrix = mat4(0.0);
    for (int i = 0; i < 4; i++) {
        if (vert.boneWeights[i] > 0.0) {
            skinMatrix += boneMatrices[vert.boneIndices[i]] * vert.boneWeights[i];
        }
    }
    
    if (skinMatrix[3][3] < 0.001) {
        skinMatrix = mat4(1.0);
    }

    vec4 skinnedPos = skinMatrix * vec4(vert.position, 1.0);
    vec3 skinnedNormal = normalize(mat3(skinMatrix) * vert.normal);

    vec4 worldPos = u_modelMatrix * skinnedPos;

    outputVertices[idx * 2] = vec4(worldPos.xyz, 1.0);
    outputVertices[idx * 2 + 1] = vec4(skinnedNormal, 0.0);
}
)";

static const char* RENDER_VERTEX_SOURCE = R"(
#version 430 core
layout(location = 0) in vec3 in_position;
layout(location = 1) in vec3 in_normal;
layout(location = 2) in vec2 in_texcoord;

uniform mat4 u_projMatrix;
out vec2 v_texcoord;
out vec3 v_normal;

void main() {
    v_texcoord = in_texcoord;
    v_normal = normalize(mat3(u_projMatrix) * in_normal);
    gl_Position = u_projMatrix * vec4(in_position, 1.0);
}
)";

static const char* RENDER_FRAGMENT_SOURCE = R"(
#version 430 core
in vec2 v_texcoord;
in vec3 v_normal;

uniform sampler2D u_texture;
uniform vec3 u_lightDir;
uniform float u_ambient;
uniform vec4 u_colorTint;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(u_texture, v_texcoord);
    float NdotL = max(dot(normalize(v_normal), normalize(u_lightDir)), 0.0);
    float lighting = u_ambient + (1.0 - u_ambient) * NdotL;
    fragColor = vec4(texColor.rgb * u_colorTint.rgb * lighting, texColor.a * u_colorTint.a);
}
)";

static int s_prevProgram = 0;
static int s_prevTex = 0;
static int s_prevActiveTex = 0;
static int s_prevTexBinding = 0;

static void pushGlState() {
    if (glGetIntegerv2) {
        glGetIntegerv2(GL_CURRENT_PROGRAM, &s_prevProgram);
        glGetIntegerv2(GL_ACTIVE_TEXTURE, &s_prevActiveTex);
        // Query the 2D texture binding for the currently active unit.
        glGetIntegerv2(GL_TEXTURE_BINDING_2D, &s_prevTexBinding);
    }
}

static void popGlState() {
    if (s_prevProgram && glUseProgram2) {
        glUseProgram2(static_cast<GLuint>(s_prevProgram));
    }
    if (glActiveTexture2) {
        glActiveTexture2(static_cast<GLenum>(s_prevActiveTex));
    }
    if (glBindTexture2) {
        glBindTexture2(GL_TEXTURE_2D, static_cast<GLuint>(s_prevTexBinding));
    }
}

bool GpuSkinning::loadExtensions() {
    if (!glPlatformLoadProc("glGetString")) return false;

    #define LOAD(name, var) \
        var = reinterpret_cast<decltype(var)>(glPlatformLoadProc(#name));

    LOAD(glDispatchCompute, glDispatchCompute);
    LOAD(glMemoryBarrier, glMemoryBarrier);
    LOAD(glGenBuffers, glGenBuffers);
    LOAD(glDeleteBuffers, glDeleteBuffers);
    LOAD(glBindBuffer, glBindBuffer);
    LOAD(glBufferData, glBufferData);
    LOAD(glBufferSubData, glBufferSubData);
    LOAD(glMapBufferRange, glMapBufferRange);
    LOAD(glUnmapBuffer, glUnmapBuffer);
    LOAD(glCreateShader, glCreateShader);
    LOAD(glShaderSource, glShaderSource);
    LOAD(glCompileShader, glCompileShader);
    LOAD(glGetShaderiv, glGetShaderiv);
    LOAD(glGetShaderInfoLog, glGetShaderInfoLog);
    LOAD(glCreateProgram, glCreateProgram);
    LOAD(glAttachShader, glAttachShader);
    LOAD(glLinkProgram, glLinkProgram);
    LOAD(glGetProgramiv, glGetProgramiv);
    LOAD(glGetProgramInfoLog, glGetProgramInfoLog);
    LOAD(glDeleteShader, glDeleteShader);
    LOAD(glDeleteProgram, glDeleteProgram);
    LOAD(glUseProgram, glUseProgram2);
    LOAD(glGetUniformLocation, glGetUniformLocation2);
    LOAD(glUniform1i, glUniform1i2);
    LOAD(glUniformMatrix4fv, glUniformMatrix4fv2);
    LOAD(glBindBufferBase, glBindBufferBase);
    LOAD(glEnableVertexAttribArray, glEnableVertexAttribArray2);
    LOAD(glVertexAttribPointer, glVertexAttribPointer2);
    LOAD(glGenVertexArrays, glGenVertexArrays2);
    LOAD(glBindVertexArray, glBindVertexArray2);
    LOAD(glDeleteVertexArrays, glDeleteVertexArrays2);
    LOAD(glDrawElements, glDrawElements2);
    LOAD(glActiveTexture, glActiveTexture2);
    LOAD(glBindTexture, glBindTexture2);
    LOAD(glGetIntegerv, glGetIntegerv2);
    LOAD(glUniform3fv, glUniform3fv2);
    LOAD(glUniform4fv, glUniform4fv2);
    LOAD(glUniform1f, glUniform1f2);

    #undef LOAD

    return glDispatchCompute != nullptr && glMemoryBarrier != nullptr
        && glGenBuffers != nullptr && glBindBufferBase != nullptr
        && glUseProgram2 != nullptr && glGenVertexArrays2 != nullptr;
}

bool GpuSkinning::isAvailable() {
    return s_available;
}

bool GpuSkinning::initialize() {
    if (s_initialized) return true;

    if (!loadExtensions()) {
        std::cout << "[GpuSkinning] OpenGL 4.3 compute shader extensions not available" << std::endl;
        return false;
    }

    uint32_t cs = compileComputeShader(COMPUTE_SHADER_SOURCE);
    if (!cs) return false;

    s_computeProgram = glCreateProgram();
    glAttachShader(s_computeProgram, cs);
    glLinkProgram(s_computeProgram);

    int linked = 0;
    glGetProgramiv(s_computeProgram, GL_LINK_STATUS, &linked);
    if (!linked) {
        int logLen = 0;
        glGetProgramiv(s_computeProgram, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            std::vector<char> log(logLen);
            glGetProgramInfoLog(s_computeProgram, logLen, nullptr, log.data());
            std::cerr << "[GpuSkinning] Compute program link error: " << log.data() << std::endl;
        }
        glDeleteProgram(s_computeProgram);
        glDeleteShader(cs);
        return false;
    }

    glDeleteShader(cs);

    uint32_t vs = compileRenderShader(RENDER_VERTEX_SOURCE, RENDER_FRAGMENT_SOURCE);
    if (!vs) {
        std::cerr << "[GpuSkinning] Failed to compile render shader" << std::endl;
        glDeleteProgram(s_computeProgram);
        return false;
    }
    s_renderProgram = vs;

    s_available = true;
    s_initialized = true;
    std::cout << "[GpuSkinning] Initialized successfully (compute=" << s_computeProgram
              << " render=" << s_renderProgram << ")" << std::endl;
    return true;
}

uint32_t GpuSkinning::compileComputeShader(const char* source) {
    uint32_t shader = glCreateShader(GL_COMPUTE_SHADER);
    if (!shader) return 0;

    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    int compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        int logLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            std::vector<char> log(logLen);
            glGetShaderInfoLog(shader, logLen, nullptr, log.data());
            std::cerr << "[GpuSkinning] Compute shader compile error: " << log.data() << std::endl;
        }
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

uint32_t GpuSkinning::compileRenderShader(const char* vertexSrc, const char* fragmentSrc) {
    uint32_t vs = glCreateShader(GL_VERTEX_SHADER);
    if (!vs) return 0;
    glShaderSource(vs, 1, &vertexSrc, nullptr);
    glCompileShader(vs);
    int compiled = 0;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        int logLen = 0;
        glGetShaderiv(vs, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            std::vector<char> log(logLen);
            glGetShaderInfoLog(vs, logLen, nullptr, log.data());
            std::cerr << "[GpuSkinning] Vertex shader compile error: " << log.data() << std::endl;
        }
        glDeleteShader(vs);
        return 0;
    }

    uint32_t fs = glCreateShader(GL_FRAGMENT_SHADER);
    if (!fs) { glDeleteShader(vs); return 0; }
    glShaderSource(fs, 1, &fragmentSrc, nullptr);
    glCompileShader(fs);
    compiled = 0;
    glGetShaderiv(fs, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        int logLen = 0;
        glGetShaderiv(fs, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            std::vector<char> log(logLen);
            glGetShaderInfoLog(fs, logLen, nullptr, log.data());
            std::cerr << "[GpuSkinning] Fragment shader compile error: " << log.data() << std::endl;
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }

    uint32_t program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);

    int linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        int logLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            std::vector<char> log(logLen);
            glGetProgramInfoLog(program, logLen, nullptr, log.data());
            std::cerr << "[GpuSkinning] Render program link error: " << log.data() << std::endl;
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        glDeleteProgram(program);
        return 0;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);
    return program;
}

GpuSkinning::SkinnedMesh GpuSkinning::createSkinnedMesh(
    const std::vector<float>& vertices,
    const std::vector<uint32_t>& indices,
    const std::vector<uint8_t>& boneIndices,
    const std::vector<float>& boneWeights)
{
    SkinnedMesh mesh = {};
    if (!s_available || vertices.empty() || indices.empty()) return mesh;

    int vertCount = static_cast<int>(vertices.size() / 8);
    mesh.vertexCount = vertCount;
    mesh.indexCount = static_cast<int>(indices.size());
    mesh.computeProgram = s_computeProgram;

    int inputSize = vertCount * sizeof(SkinnedVertex);
    std::vector<SkinnedVertex> inputVerts(vertCount);

    for (int i = 0; i < vertCount; i++) {
        int srcIdx = i * 8;
        inputVerts[i].position[0] = vertices[srcIdx];
        inputVerts[i].position[1] = vertices[srcIdx + 1];
        inputVerts[i].position[2] = vertices[srcIdx + 2];
        inputVerts[i].normal[0] = vertices[srcIdx + 3];
        inputVerts[i].normal[1] = vertices[srcIdx + 4];
        inputVerts[i].normal[2] = vertices[srcIdx + 5];
        inputVerts[i].texcoord[0] = vertices[srcIdx + 6];
        inputVerts[i].texcoord[1] = vertices[srcIdx + 7];

        if (boneIndices.size() >= static_cast<size_t>((i + 1) * 4)) {
            inputVerts[i].boneIndices[0] = boneIndices[i * 4];
            inputVerts[i].boneIndices[1] = boneIndices[i * 4 + 1];
            inputVerts[i].boneIndices[2] = boneIndices[i * 4 + 2];
            inputVerts[i].boneIndices[3] = boneIndices[i * 4 + 3];
        } else {
            inputVerts[i].boneIndices[0] = 0;
            inputVerts[i].boneIndices[1] = 0;
            inputVerts[i].boneIndices[2] = 0;
            inputVerts[i].boneIndices[3] = 0;
        }

        if (boneWeights.size() >= static_cast<size_t>((i + 1) * 4)) {
            inputVerts[i].boneWeights[0] = boneWeights[i * 4];
            inputVerts[i].boneWeights[1] = boneWeights[i * 4 + 1];
            inputVerts[i].boneWeights[2] = boneWeights[i * 4 + 2];
            inputVerts[i].boneWeights[3] = boneWeights[i * 4 + 3];
        } else {
            inputVerts[i].boneWeights[0] = 1.0f;
            inputVerts[i].boneWeights[1] = 0.0f;
            inputVerts[i].boneWeights[2] = 0.0f;
            inputVerts[i].boneWeights[3] = 0.0f;
        }
    }

    glGenBuffers(1, &mesh.ssboInput);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, mesh.ssboInput);
    glBufferData(GL_SHADER_STORAGE_BUFFER, inputSize, inputVerts.data(), GL_STATIC_DRAW);

    int outputSize = vertCount * 2 * 4 * sizeof(float);
    glGenBuffers(1, &mesh.ssboOutput);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, mesh.ssboOutput);
    glBufferData(GL_SHADER_STORAGE_BUFFER, outputSize, nullptr, GL_DYNAMIC_DRAW);

    glGenBuffers(1, &mesh.ssboBones);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, mesh.ssboBones);
    int maxBones = 128;
    glBufferData(GL_SHADER_STORAGE_BUFFER, maxBones * sizeof(BoneMatrix), nullptr, GL_DYNAMIC_DRAW);

    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

    glGenVertexArrays2(1, &mesh.renderVao);
    glBindVertexArray2(mesh.renderVao);

    // Use ssboOutput directly as the render VBO (buffer aliasing) for skinned pos+normal
    glBindBuffer(GL_ARRAY_BUFFER, mesh.ssboOutput);

    glVertexAttribPointer2(0, 3, GL_FLOAT, GL_FALSE, 8 * sizeof(float),
        reinterpret_cast<const void*>(0));
    glEnableVertexAttribArray2(0);

    glVertexAttribPointer2(1, 3, GL_FLOAT, GL_FALSE, 8 * sizeof(float),
        reinterpret_cast<const void*>(4 * sizeof(float)));
    glEnableVertexAttribArray2(1);

    // Separate VBO for static texcoords (never change)
    std::vector<float> texcoords(vertCount * 2);
    for (int i = 0; i < vertCount; i++) {
        int srcIdx = i * 8;
        texcoords[i * 2 + 0] = vertices[srcIdx + 6];
        texcoords[i * 2 + 1] = vertices[srcIdx + 7];
    }
    uint32_t texcoordVbo = 0;
    glGenBuffers(1, &texcoordVbo);
    glBindBuffer(GL_ARRAY_BUFFER, texcoordVbo);
    glBufferData(GL_ARRAY_BUFFER, texcoords.size() * sizeof(float), texcoords.data(), GL_STATIC_DRAW);

    glVertexAttribPointer2(2, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float),
        reinterpret_cast<const void*>(0));
    glEnableVertexAttribArray2(2);

    uint32_t ebo = 0;
    glGenBuffers(1, &ebo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
        indices.size() * sizeof(uint32_t), indices.data(), GL_STATIC_DRAW);

    glBindVertexArray2(0);

    mesh.inputVbo = 0;         // No separate input VBO (ssboOutput is aliased as render VBO)
    mesh.outputVbo = ebo;
    mesh.texcoordVbo = texcoordVbo;

    mesh.valid = true;
    std::cout << "[GpuSkinning] Created skinned mesh with " << vertCount
              << " vertices, " << (indices.size() / 3) << " triangles" << std::endl;
    return mesh;
}

void GpuSkinning::destroySkinnedMesh(SkinnedMesh& mesh) {
    if (!mesh.valid) return;

    if (mesh.ssboInput) glDeleteBuffers(1, &mesh.ssboInput);
    if (mesh.ssboOutput) {
        // Also used as render VBO via buffer aliasing; only delete once
        GLuint buf = mesh.ssboOutput;
        glDeleteBuffers(1, &buf);
    }
    if (mesh.ssboBones) glDeleteBuffers(1, &mesh.ssboBones);
    if (mesh.texcoordVbo) glDeleteBuffers(1, &mesh.texcoordVbo);
    if (mesh.inputVbo) glDeleteBuffers(1, &mesh.inputVbo);
    if (mesh.outputVbo) {
        GLuint ebo = mesh.outputVbo;
        glDeleteBuffers(1, &ebo);
    }
    if (mesh.renderVao) {
        glDeleteVertexArrays2(1, &mesh.renderVao);
    }

    mesh.valid = false;
    mesh.vertexCount = 0;
    mesh.ssboInput = 0;
    mesh.ssboOutput = 0;
    mesh.ssboBones = 0;
    mesh.inputVbo = 0;
    mesh.outputVbo = 0;
    mesh.renderVao = 0;
}

void GpuSkinning::skinMesh(
    SkinnedMesh& mesh,
    const BoneMatrix* boneMatrices,
    int boneCount,
    const float* modelMatrix,
    int packedLight)
{
    if (!mesh.valid || !s_available) return;

    glUseProgram2(s_computeProgram);

    glUniformMatrix4fv2(glGetUniformLocation2(s_computeProgram, "u_modelMatrix"),
        1, GL_FALSE, modelMatrix);

    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, mesh.ssboInput);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, mesh.ssboOutput);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, mesh.ssboBones);

    if (boneMatrices && boneCount > 0) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, mesh.ssboBones);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0,
            boneCount * sizeof(BoneMatrix), boneMatrices);
    }

    int groupCount = (mesh.vertexCount + 255) / 256;
    glDispatchCompute(static_cast<GLuint>(groupCount), 1, 1);

    glMemoryBarrier(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);

    glUseProgram2(0);
}

void GpuSkinning::renderSkinnedMesh(
    SkinnedMesh& mesh,
    uint32_t textureId,
    const float* modelMatrix,
    int packedLight,
    const float* colorTint)
{
    if (!mesh.valid || !s_available) return;

    pushGlState();

    glUseProgram2(s_renderProgram);

    int mvpLoc = glGetUniformLocation2(s_renderProgram, "u_projMatrix");
    if (mvpLoc >= 0) glUniformMatrix4fv2(mvpLoc, 1, GL_FALSE, modelMatrix);

    int texLoc = glGetUniformLocation2(s_renderProgram, "u_texture");
    if (texLoc >= 0) glUniform1i2(texLoc, 0);

    int lightLoc = glGetUniformLocation2(s_renderProgram, "u_lightDir");
    if (lightLoc >= 0) {
        float lightDir[] = { 0.2f, 0.8f, 0.3f };
        glUniform3fv2(lightLoc, 1, lightDir);
    }

    int ambLoc = glGetUniformLocation2(s_renderProgram, "u_ambient");
    if (ambLoc >= 0) glUniform1f2(ambLoc, 0.4f);

    int tintLoc = glGetUniformLocation2(s_renderProgram, "u_colorTint");
    if (tintLoc >= 0) {
        if (colorTint) {
            glUniform4fv2(tintLoc, 1, colorTint);
        } else {
            float white[] = {1.0f, 1.0f, 1.0f, 1.0f};
            glUniform4fv2(tintLoc, 1, white);
        }
    }

    if (textureId && glActiveTexture2 && glBindTexture2) {
        glActiveTexture2(0x84C0);
        glBindTexture2(0x0DE1, textureId);
    }

    GLuint vao = mesh.renderVao;
    int indexCount = mesh.indexCount > 0 ? mesh.indexCount : mesh.vertexCount * 3;
    if (glBindVertexArray2 && glDrawElements2 && vao) {
        glBindVertexArray2(vao);
        glDrawElements2(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, nullptr);
        glBindVertexArray2(0);
    }

    popGlState();
}
