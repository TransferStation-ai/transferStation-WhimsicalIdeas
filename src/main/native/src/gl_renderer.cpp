#include "gl_renderer.h"
#include <iostream>
#include <cstring>
#include <cstdio>
#include <unordered_map>
#include <utility>

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>

// OpenGL function pointer types
typedef void (APIENTRY* GL_GENVERTEXARRAYS)(int, uint32_t*);
typedef void (APIENTRY* GL_DELETEVERTEXARRAYS)(int, const uint32_t*);
typedef void (APIENTRY* GL_BINDVERTEXARRAY)(uint32_t);
typedef void (APIENTRY* GL_GENBUFFERS)(int, uint32_t*);
typedef void (APIENTRY* GL_DELETEBUFFERS)(int, const uint32_t*);
typedef void (APIENTRY* GL_BINDBUFFER)(uint32_t, uint32_t);
typedef void (APIENTRY* GL_BUFFERDATA)(uint32_t, intptr_t, const void*, uint32_t);
typedef void (APIENTRY* GL_ENABLEVERTEXATTRIBARRAY)(uint32_t);
typedef void (APIENTRY* GL_VERTEXATTRIBPOINTER)(uint32_t, int, uint32_t, int, int, const void*);
typedef uint32_t (APIENTRY* GL_CREATESHADER)(uint32_t);
typedef void (APIENTRY* GL_SHADERSOURCE)(uint32_t, int, const char**, const int*);
typedef void (APIENTRY* GL_COMPILESHADER)(uint32_t);
typedef void (APIENTRY* GL_GETSHADERIV)(uint32_t, uint32_t, int*);
typedef void (APIENTRY* GL_GETSHADERINFOLOG)(uint32_t, int, int*, char*);
typedef uint32_t (APIENTRY* GL_CREATEPROGRAM)(void);
typedef void (APIENTRY* GL_ATTACHSHADER)(uint32_t, uint32_t);
typedef void (APIENTRY* GL_LINKPROGRAM)(uint32_t);
typedef void (APIENTRY* GL_GETPROGRAMIV)(uint32_t, uint32_t, int*);
typedef void (APIENTRY* GL_GETPROGRAMINFOLOG)(uint32_t, int, int*, char*);
typedef void (APIENTRY* GL_DELETESHADER)(uint32_t);
typedef void (APIENTRY* GL_USEPROGRAM)(uint32_t);
typedef void (APIENTRY* GL_UNIFORMMATRIX4FV)(int, int, int, const float*);
typedef int (APIENTRY* GL_GETUNIFORMLOCATION)(uint32_t, const char*);
typedef void (APIENTRY* GL_UNIFORM1I)(int, int);
typedef void (APIENTRY* GL_UNIFORM1F)(int, float);
typedef void (APIENTRY* GL_UNIFORM3FV)(int, int, const float*);
typedef void (APIENTRY* GL_UNIFORM4FV)(int, int, const float*);
typedef void (APIENTRY* GL_VIEWPORT)(int, int, int, int);
typedef void (APIENTRY* GL_ENABLE)(uint32_t);
typedef void (APIENTRY* GL_DISABLE)(uint32_t);
typedef void (APIENTRY* GL_DEPTHFUNC)(uint32_t);
typedef void (APIENTRY* GL_BLENDFUNC)(uint32_t, uint32_t);
typedef void (APIENTRY* GL_DRAWELEMENTS)(uint32_t, int, uint32_t, const void*);
typedef void (APIENTRY* GL_ACTIVETEXTURE)(uint32_t);
typedef void (APIENTRY* GL_BINDTEXTURE)(uint32_t, uint32_t);
typedef void (APIENTRY* GL_GENTEXTURES)(int, uint32_t*);
typedef void (APIENTRY* GL_DELETETEXTURES)(int, const uint32_t*);
typedef void (APIENTRY* GL_TEXIMAGE2D)(uint32_t, int, int, int, int, int, uint32_t, uint32_t, const void*);
typedef void (APIENTRY* GL_TEXPARAMETERI)(uint32_t, uint32_t, int);
typedef void (APIENTRY* GL_GETINTEGERV)(uint32_t, int*);

// Global function pointers (loaded once during initialize)
static GL_GENVERTEXARRAYS     glGenVertexArrays = nullptr;
static GL_DELETEVERTEXARRAYS  glDeleteVertexArrays = nullptr;
static GL_BINDVERTEXARRAY     glBindVertexArray = nullptr;
static GL_GENBUFFERS          glGenBuffers = nullptr;
static GL_DELETEBUFFERS       glDeleteBuffers = nullptr;
static GL_BINDBUFFER          glBindBuffer = nullptr;
static GL_BUFFERDATA          glBufferData = nullptr;
static GL_ENABLEVERTEXATTRIBARRAY glEnableVertexAttribArray = nullptr;
static GL_VERTEXATTRIBPOINTER glVertexAttribPointer = nullptr;
static GL_CREATESHADER        glCreateShader = nullptr;
static GL_SHADERSOURCE        glShaderSource = nullptr;
static GL_COMPILESHADER       glCompileShader = nullptr;
static GL_GETSHADERIV         glGetShaderiv = nullptr;
static GL_GETSHADERINFOLOG    glGetShaderInfoLog = nullptr;
static GL_CREATEPROGRAM       glCreateProgram = nullptr;
static GL_ATTACHSHADER        glAttachShader = nullptr;
static GL_LINKPROGRAM         glLinkProgram = nullptr;
static GL_GETPROGRAMIV        glGetProgramiv = nullptr;
static GL_GETPROGRAMINFOLOG   glGetProgramInfoLog = nullptr;
static GL_DELETESHADER        glDeleteShader = nullptr;
static GL_USEPROGRAM          glUseProgram = nullptr;
static GL_UNIFORMMATRIX4FV    glUniformMatrix4fv = nullptr;
static GL_GETUNIFORMLOCATION  glGetUniformLocation = nullptr;
static GL_UNIFORM1I           glUniform1i = nullptr;
static GL_UNIFORM1F           glUniform1f = nullptr;
static GL_UNIFORM3FV          glUniform3fv = nullptr;
static GL_UNIFORM4FV          glUniform4fv = nullptr;
static GL_VIEWPORT            glViewport = nullptr;
static GL_ENABLE              glEnable = nullptr;
static GL_DISABLE             glDisable = nullptr;
static GL_DEPTHFUNC           glDepthFunc = nullptr;
static GL_BLENDFUNC           glBlendFunc = nullptr;
static GL_DRAWELEMENTS        glDrawElements = nullptr;
static GL_ACTIVETEXTURE       glActiveTexture = nullptr;
static GL_BINDTEXTURE         glBindTexture = nullptr;
static GL_GENTEXTURES         glGenTextures = nullptr;
static GL_DELETETEXTURES      glDeleteTextures = nullptr;
static GL_TEXIMAGE2D          glTexImage2D = nullptr;
static GL_TEXPARAMETERI       glTexParameteri = nullptr;
static GL_GETINTEGERV         glGetIntegerv = nullptr;

#define LOAD_GL_FUNC(name, var) \
    do { \
        const char* funcName = #name; \
        var = reinterpret_cast<decltype(var)>(wglGetProcAddress(funcName)); \
        if (!var) { \
            var = reinterpret_cast<decltype(var)>(GetProcAddress(glModule, funcName)); \
        } \
        if (!var) { \
            std::cerr << "[GL] Failed to load " << funcName << std::endl; \
            return false; \
        } \
    } while(0)

// OpenGL constants
constexpr uint32_t GL_ARRAY_BUFFER = 0x8892;
constexpr uint32_t GL_ELEMENT_ARRAY_BUFFER = 0x8893;
constexpr uint32_t GL_STATIC_DRAW = 0x88E4;
constexpr uint32_t GL_FALSE = 0;
constexpr uint32_t GL_FLOAT = 0x1406;
constexpr uint32_t GL_UNSIGNED_INT = 0x1405;
constexpr uint32_t GL_TRIANGLES = 0x0004;
constexpr uint32_t GL_VERTEX_SHADER = 0x8B31;
constexpr uint32_t GL_FRAGMENT_SHADER = 0x8B30;
constexpr uint32_t GL_COMPILE_STATUS = 0x8B81;
constexpr uint32_t GL_LINK_STATUS = 0x8B82;
constexpr uint32_t GL_INFO_LOG_LENGTH = 0x8B84;
constexpr uint32_t GL_TEXTURE0 = 0x84C0;
constexpr uint32_t GL_TEXTURE_2D = 0x0DE1;
constexpr uint32_t GL_RGBA8 = 0x8058;
constexpr uint32_t GL_RGBA = 0x1908;
constexpr uint32_t GL_UNSIGNED_BYTE = 0x1401;
constexpr uint32_t GL_TEXTURE_MIN_FILTER = 0x2801;
constexpr uint32_t GL_TEXTURE_MAG_FILTER = 0x2800;
constexpr uint32_t GL_LINEAR = 0x2601;
constexpr uint32_t GL_DEPTH_TEST = 0x0B71;
constexpr uint32_t GL_LEQUAL = 0x0203;
constexpr uint32_t GL_BLEND = 0x0BE2;
constexpr uint32_t GL_SRC_ALPHA = 0x0302;
constexpr uint32_t GL_ONE_MINUS_SRC_ALPHA = 0x0303;
constexpr int GL_CURRENT_PROGRAM = 0x8B8D;
constexpr int GL_ACTIVE_TEXTURE = 0x84E0;
constexpr int GL_TEXTURE_BINDING_2D = 0x8069;

const char* GlRenderer::VERTEX_SHADER_SOURCE = R"(
#version 150 core
in vec3 in_position;
in vec3 in_normal;
in vec2 in_texcoord;

uniform mat4 u_modelViewProjection;

out vec2 v_texcoord;
out vec3 v_normal;

void main() {
    v_texcoord = in_texcoord;
    v_normal = mat3(u_modelViewProjection) * in_normal;
    gl_Position = u_modelViewProjection * vec4(in_position, 1.0);
}
)";

const char* GlRenderer::FRAGMENT_SHADER_SOURCE = R"(
#version 150 core
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

uint32_t GlRenderer::s_program = 0;
bool GlRenderer::s_initialized = false;
static std::unordered_map<uint32_t, std::pair<uint32_t, uint32_t>> s_meshBuffers;

bool GlRenderer::initialize() {
    if (s_initialized) return true;

    HMODULE glModule = GetModuleHandleA("opengl32.dll");
    if (!glModule) {
        std::cerr << "[GL] opengl32.dll not loaded" << std::endl;
        return false;
    }

    LOAD_GL_FUNC(glGenVertexArrays, glGenVertexArrays);
    LOAD_GL_FUNC(glDeleteVertexArrays, glDeleteVertexArrays);
    LOAD_GL_FUNC(glBindVertexArray, glBindVertexArray);
    LOAD_GL_FUNC(glGenBuffers, glGenBuffers);
    LOAD_GL_FUNC(glDeleteBuffers, glDeleteBuffers);
    LOAD_GL_FUNC(glBindBuffer, glBindBuffer);
    LOAD_GL_FUNC(glBufferData, glBufferData);
    LOAD_GL_FUNC(glEnableVertexAttribArray, glEnableVertexAttribArray);
    LOAD_GL_FUNC(glVertexAttribPointer, glVertexAttribPointer);
    LOAD_GL_FUNC(glCreateShader, glCreateShader);
    LOAD_GL_FUNC(glShaderSource, glShaderSource);
    LOAD_GL_FUNC(glCompileShader, glCompileShader);
    LOAD_GL_FUNC(glGetShaderiv, glGetShaderiv);
    LOAD_GL_FUNC(glGetShaderInfoLog, glGetShaderInfoLog);
    LOAD_GL_FUNC(glCreateProgram, glCreateProgram);
    LOAD_GL_FUNC(glAttachShader, glAttachShader);
    LOAD_GL_FUNC(glLinkProgram, glLinkProgram);
    LOAD_GL_FUNC(glGetProgramiv, glGetProgramiv);
    LOAD_GL_FUNC(glGetProgramInfoLog, glGetProgramInfoLog);
    LOAD_GL_FUNC(glDeleteShader, glDeleteShader);
    LOAD_GL_FUNC(glUseProgram, glUseProgram);
    LOAD_GL_FUNC(glUniformMatrix4fv, glUniformMatrix4fv);
    LOAD_GL_FUNC(glGetUniformLocation, glGetUniformLocation);
    LOAD_GL_FUNC(glUniform1i, glUniform1i);
    LOAD_GL_FUNC(glUniform1f, glUniform1f);
    LOAD_GL_FUNC(glUniform3fv, glUniform3fv);
    LOAD_GL_FUNC(glUniform4fv, glUniform4fv);
    LOAD_GL_FUNC(glEnable, glEnable);
    LOAD_GL_FUNC(glDisable, glDisable);
    LOAD_GL_FUNC(glDepthFunc, glDepthFunc);
    LOAD_GL_FUNC(glBlendFunc, glBlendFunc);
    LOAD_GL_FUNC(glDrawElements, glDrawElements);
    LOAD_GL_FUNC(glActiveTexture, glActiveTexture);
    LOAD_GL_FUNC(glBindTexture, glBindTexture);
    LOAD_GL_FUNC(glGenTextures, glGenTextures);
    LOAD_GL_FUNC(glDeleteTextures, glDeleteTextures);
    LOAD_GL_FUNC(glTexImage2D, glTexImage2D);
    LOAD_GL_FUNC(glTexParameteri, glTexParameteri);
    LOAD_GL_FUNC(glGetIntegerv, glGetIntegerv);

    // Compile and link shader program
    uint32_t vs = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
    if (!vs) return false;

    uint32_t fs = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE);
    if (!fs) {
        glDeleteShader(vs);
        return false;
    }

    s_program = linkProgram(vs, fs);
    glDeleteShader(vs);
    glDeleteShader(fs);

    if (!s_program) return false;

    s_initialized = true;
    std::cout << "[GL] Renderer initialized, program=" << s_program << std::endl;
    return true;
}

uint32_t GlRenderer::compileShader(uint32_t type, const char* source) {
    uint32_t shader = glCreateShader(type);
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
            std::cerr << "[GL] Shader compile error: " << log.data() << std::endl;
        }
        glDeleteShader(shader);
        return 0;
    }

    return shader;
}

uint32_t GlRenderer::linkProgram(uint32_t vs, uint32_t fs) {
    uint32_t program = glCreateProgram();
    if (!program) return 0;

    glAttachShader(program, vs);
    glAttachShader(program, fs);

    // Bind attribute locations
    // These must match the shader source "in" declarations
    // in_position = 0, in_normal = 1, in_texcoord = 2

    glLinkProgram(program);

    int linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        int logLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &logLen);
        if (logLen > 0) {
            std::vector<char> log(logLen);
            glGetProgramInfoLog(program, logLen, nullptr, log.data());
            std::cerr << "[GL] Program link error: " << log.data() << std::endl;
        }
        glDeleteShader(program);
        return 0;
    }

    return program;
}

uint32_t GlRenderer::buildMesh(const std::vector<MeshVertex>& vertices, const std::vector<uint32_t>& indices) {
    if (vertices.empty() || indices.empty()) return 0;

    uint32_t vao = 0;
    uint32_t vbo = 0;
    uint32_t ebo = 0;

    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);

    // VBO: interleaved vertex data (8 floats = 32 bytes per vertex)
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER,
        static_cast<intptr_t>(vertices.size() * sizeof(MeshVertex)),
        vertices.data(), GL_STATIC_DRAW);

    // Position (location 0)
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(MeshVertex),
        reinterpret_cast<const void*>(offsetof(MeshVertex, x)));
    glEnableVertexAttribArray(0);

    // Normal (location 1)
    glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(MeshVertex),
        reinterpret_cast<const void*>(offsetof(MeshVertex, nx)));
    glEnableVertexAttribArray(1);

    // Texcoord (location 2)
    glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(MeshVertex),
        reinterpret_cast<const void*>(offsetof(MeshVertex, u)));
    glEnableVertexAttribArray(2);

    // EBO
    glGenBuffers(1, &ebo);
    glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,
        static_cast<intptr_t>(indices.size() * sizeof(uint32_t)),
        indices.data(), GL_STATIC_DRAW);

    glBindVertexArray(0);

    s_meshBuffers[vao] = {vbo, ebo};

    std::cout << "[GL] Built mesh vao=" << vao << " vbo=" << vbo
              << " ebo=" << ebo << " verts=" << vertices.size()
              << " indices=" << indices.size() << std::endl;

    return vao;
}

void GlRenderer::destroyMesh(uint32_t vao) {
    if (!vao) return;
    auto it = s_meshBuffers.find(vao);
    if (it != s_meshBuffers.end()) {
        if (it->second.first && glDeleteBuffers) glDeleteBuffers(1, &it->second.first);
        if (it->second.second && glDeleteBuffers) glDeleteBuffers(1, &it->second.second);
        s_meshBuffers.erase(it);
    }
    if (glDeleteVertexArrays) glDeleteVertexArrays(1, &vao);
}

uint32_t GlRenderer::uploadTexture(const std::vector<uint8_t>& rgbaData, int width, int height) {
    if (rgbaData.empty() || width <= 0 || height <= 0 || !glGenTextures) return 0;

    uint32_t textureId = 0;
    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_2D, textureId);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, rgbaData.data());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glBindTexture(GL_TEXTURE_2D, 0);

    std::cout << "[GL] Uploaded texture id=" << textureId << " " << width << "x" << height << std::endl;
    return textureId;
}

void GlRenderer::destroyTexture(uint32_t textureId) {
    if (!textureId || !glDeleteTextures) return;
    glDeleteTextures(1, &textureId);
}

void GlRenderer::renderMesh(uint32_t vao, int indexCount, uint32_t textureId,
                             const float* modelMatrix, int packedLight,
                             const float* colorTint) {
    if (!vao || indexCount <= 0 || !s_program) return;
    if (!glUseProgram || !glBindVertexArray || !glDrawElements) return;

    // Save OpenGL state
    int prevProgram = 0;
    glGetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);

    int prevTex = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &prevTex);

    int prevActiveTex = 0;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &prevActiveTex);

    // Use our shader
    glUseProgram(s_program);

    // Set uniforms
    int mvpLoc = glGetUniformLocation(s_program, "u_modelViewProjection");
    if (mvpLoc >= 0) glUniformMatrix4fv(mvpLoc, 1, GL_FALSE, modelMatrix);

    int texLoc = glGetUniformLocation(s_program, "u_texture");
    if (texLoc >= 0) glUniform1i(texLoc, 0);

    int lightLoc = glGetUniformLocation(s_program, "u_lightDir");
    if (lightLoc >= 0) {
        float lightDir[] = { 0.2f, 0.8f, 0.3f };
        glUniform3fv(lightLoc, 1, lightDir);
    }

    int ambLoc = glGetUniformLocation(s_program, "u_ambient");
    if (ambLoc >= 0) {
        glUniform1f(ambLoc, 0.4f);
    }

    int tintLoc = glGetUniformLocation(s_program, "u_colorTint");
    if (tintLoc >= 0) {
        if (colorTint) {
            glUniform4fv(tintLoc, 1, colorTint);
        } else {
            float white[] = {1.0f, 1.0f, 1.0f, 1.0f};
            glUniform4fv(tintLoc, 1, white);
        }
    }

    // Bind texture
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, textureId);

    // Set rendering state
    glEnable(GL_DEPTH_TEST);
    glDepthFunc(GL_LEQUAL);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Draw
    glBindVertexArray(vao);
    glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, nullptr);
    glBindVertexArray(0);

    // Restore state
    glUseProgram(static_cast<uint32_t>(prevProgram));
    glActiveTexture(static_cast<uint32_t>(prevActiveTex));
    glBindTexture(GL_TEXTURE_2D, static_cast<uint32_t>(prevTex));
}

void GlRenderer::shutdown() {
    // Clean up all remaining mesh buffers
    for (auto& [vao, bufs] : s_meshBuffers) {
        if (bufs.first && glDeleteBuffers) glDeleteBuffers(1, &bufs.first);
        if (bufs.second && glDeleteBuffers) glDeleteBuffers(1, &bufs.second);
    }
    s_meshBuffers.clear();

    if (s_program) {
        // s_program is a program object, not a shader — use glDeleteProgram
        typedef void (APIENTRY* GL_DELETEPROGRAM)(uint32_t);
        static GL_DELETEPROGRAM pDeleteProgram = nullptr;
        if (!pDeleteProgram) {
            pDeleteProgram = reinterpret_cast<GL_DELETEPROGRAM>(wglGetProcAddress("glDeleteProgram"));
            if (!pDeleteProgram) pDeleteProgram = reinterpret_cast<GL_DELETEPROGRAM>(GetProcAddress(GetModuleHandleA("opengl32.dll"), "glDeleteProgram"));
        }
        if (pDeleteProgram) pDeleteProgram(s_program);
        s_program = 0;
    }
    s_initialized = false;
}
