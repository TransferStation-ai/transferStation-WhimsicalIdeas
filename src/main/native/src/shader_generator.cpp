#include "shader_generator.h"
#include "glad/gl.h"

// Redirect GL function calls to glad function pointers
#define glCreateShader glad_glCreateShader
#define glShaderSource glad_glShaderSource
#define glCompileShader glad_glCompileShader
#define glGetShaderiv glad_glGetShaderiv
#define glGetShaderInfoLog glad_glGetShaderInfoLog
#define glDeleteShader glad_glDeleteShader
#define glCreateProgram glad_glCreateProgram
#define glAttachShader glad_glAttachShader
#define glLinkProgram glad_glLinkProgram
#define glGetProgramiv glad_glGetProgramiv
#define glGetProgramInfoLog glad_glGetProgramInfoLog
#define glDeleteProgram glad_glDeleteProgram
#define glGetUniformLocation glad_glGetUniformLocation
#include <cstdio>
#include <sstream>
#include <algorithm>
#include <cstring>

// ===================== 通用着色器模板 =====================

static const char* VERTEX_TEMPLATE = R"(
#version 330 core
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoord;

uniform mat4 uModelMatrix;
uniform mat4 uViewMatrix;
uniform mat4 uProjectionMatrix;

out vec2 vTexCoord;
out vec3 vWorldPos;
out vec3 vWorldNormal;

void main() {
    vec4 worldPos = uModelMatrix * vec4(aPosition, 1.0);
    vWorldPos = worldPos.xyz;
    vWorldNormal = normalize(mat3(uModelMatrix) * aNormal);
    vTexCoord = aTexCoord;
    gl_Position = uProjectionMatrix * uViewMatrix * worldPos;
}
)";

// 基础片段着色器 — Base Diffuse
static const char* FRAGMENT_BASE = R"(
#version 330 core
uniform sampler2D uBaseTexture;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbientColor;
uniform vec3 uCameraPos;

in vec2 vTexCoord;
in vec3 vWorldPos;
in vec3 vWorldNormal;

out vec4 fragColor;

void main() {
    vec3 baseColor = texture(uBaseTexture, vTexCoord).rgb;
    vec3 N = normalize(vWorldNormal);
    vec3 L = normalize(uLightDir);
    float diff = max(dot(N, -L), 0.0);
    vec3 lighting = uAmbientColor + diff * uLightColor;
    fragColor = vec4(baseColor * lighting, 1.0);
}
)";

// Phong 片段着色器
static const char* FRAGMENT_PHONG = R"(
#version 330 core
uniform sampler2D uBaseTexture;
uniform sampler2D uBumpTexture;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbientColor;
uniform vec3 uCameraPos;
uniform float uPhongBoost;
uniform float uPhongExponent;

in vec2 vTexCoord;
in vec3 vWorldPos;
in vec3 vWorldNormal;

out vec4 fragColor;

void main() {
    vec3 baseColor = texture(uBaseTexture, vTexCoord).rgb;
    
    // Normal from bump map
    vec3 bumpNormal = texture(uBumpTexture, vTexCoord).xyz * 2.0 - 1.0;
    vec3 N = normalize(vWorldNormal);
    // Simple bump: perturb normal (full TBN would need tangent data from mesh)
    N = normalize(N + bumpNormal * 0.3);
    
    vec3 L = normalize(uLightDir);
    float diff = max(dot(N, -L), 0.0);
    
    // Blinn-Phong specular
    vec3 V = normalize(uCameraPos - vWorldPos);
    vec3 H = normalize(-L + V);
    float spec = pow(max(dot(N, H), 0.0), uPhongExponent) * uPhongBoost;
    
    vec3 lighting = uAmbientColor + diff * uLightColor + spec * uLightColor;
    fragColor = vec4(baseColor * lighting, 1.0);
}
)";

// ===================== 实现 =====================

std::vector<std::string> ShaderGenerator::determineVariants(
    const std::unordered_map<std::string, std::string>& properties)
{
    std::vector<std::string> variants;
    variants.push_back("base");
    
    auto hasParam = [&](const std::string& key) {
        auto it = properties.find(key);
        if (it == properties.end()) return false;
        std::string v = it->second;
        std::transform(v.begin(), v.end(), v.begin(), ::tolower);
        return !v.empty() && v != "0" && v != "false" && v != "no";
    };
    
    if (hasParam("$bumpmap")) variants.push_back("bump");
    if (hasParam("$phong")) variants.push_back("phong");
    if (properties.count("$envmap")) variants.push_back("envmap");
    if (properties.count("$detail")) variants.push_back("detail");
    
    return variants;
}

ShaderGenerator::ShaderSource ShaderGenerator::generateSource(
    const std::unordered_map<std::string, std::string>& properties,
    const std::string& variant)
{
    ShaderSource source;
    source.vertexSource = VERTEX_TEMPLATE;
    
    if (variant == "base") {
        source.fragmentSource = FRAGMENT_BASE;
    } else if (variant == "phong" || variant == "bump") {
        source.fragmentSource = FRAGMENT_PHONG;
    } else {
        // Default to base for unknown variants
        source.fragmentSource = FRAGMENT_BASE;
    }
    
    return source;
}

uint32_t ShaderGenerator::compileShader(uint32_t type, const std::string& source) {
    uint32_t shader = glCreateShader(type);
    const char* src = source.c_str();
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    
    GLint status;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (status != GL_TRUE) {
        char log[1024];
        GLsizei len;
        glGetShaderInfoLog(shader, sizeof(log), &len, log);
        fprintf(stderr, "[ShaderGenerator] Compile error (%s): %.*s\n",
            type == GL_VERTEX_SHADER ? "VS" : "FS", len, log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

ShaderGenerator::ShaderProgram ShaderGenerator::compile(const ShaderSource& source) {
    ShaderProgram prog;
    
    uint32_t vs = compileShader(GL_VERTEX_SHADER, source.vertexSource);
    if (!vs) return prog;
    
    uint32_t fs = compileShader(GL_FRAGMENT_SHADER, source.fragmentSource);
    if (!fs) {
        glDeleteShader(vs);
        return prog;
    }
    
    prog.programId = glCreateProgram();
    glAttachShader(prog.programId, vs);
    glAttachShader(prog.programId, fs);
    glLinkProgram(prog.programId);
    
    GLint status;
    glGetProgramiv(prog.programId, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        char log[1024];
        GLsizei len;
        glGetProgramInfoLog(prog.programId, sizeof(log), &len, log);
        fprintf(stderr, "[ShaderGenerator] Link error: %.*s\n", len, log);
        glDeleteProgram(prog.programId);
        prog.programId = 0;
    }
    
    glDeleteShader(vs);
    glDeleteShader(fs);
    
    if (prog.programId) {
        // Cache uniform locations
        prog.uModelMatrix = glGetUniformLocation(prog.programId, "uModelMatrix");
        prog.uViewMatrix = glGetUniformLocation(prog.programId, "uViewMatrix");
        prog.uProjectionMatrix = glGetUniformLocation(prog.programId, "uProjectionMatrix");
        prog.uLightDir = glGetUniformLocation(prog.programId, "uLightDir");
        prog.uLightColor = glGetUniformLocation(prog.programId, "uLightColor");
        prog.uAmbientColor = glGetUniformLocation(prog.programId, "uAmbientColor");
        prog.uCameraPos = glGetUniformLocation(prog.programId, "uCameraPos");
        prog.uBaseTexture = glGetUniformLocation(prog.programId, "uBaseTexture");
        prog.uBumpTexture = glGetUniformLocation(prog.programId, "uBumpTexture");
        prog.uPhongBoost = glGetUniformLocation(prog.programId, "uPhongBoost");
        prog.uPhongExponent = glGetUniformLocation(prog.programId, "uPhongExponent");
    }
    
    return prog;
}

void ShaderGenerator::destroy(ShaderProgram& program) {
    if (program.programId) {
        glDeleteProgram(program.programId);
        program.programId = 0;
    }
}