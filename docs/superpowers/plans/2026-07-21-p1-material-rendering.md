# P1: VMT 材质渲染管线 — 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 实现与 Source Engine VMT 兼容的材质渲染管线，支持多 Pass 渲染（Base/Bump/Phong/Envmap），在 native-renderer C++ 层生成 GLSL Shader。

**架构：** 混合架构。Java 侧（VmtParser + MaterialTree + MaterialCompiler）负责 VMT 解析和材质组合；C++ 侧（ShaderGenerator + MultiPassRenderer）负责 GLSL 生成和多 Pass 渲染。通过 JNI 传递序列化材质配置。

**技术栈：** Java 17、C++20、OpenGL 3.3+、GLSL、JNI、Forge 1.20.1

---

### 任务 1：创建 MaterialConfig.java — 材质配置 POJO

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MaterialConfig.java`
- 相关：`src/main/java/.../client/model/VmtParser.java`

- [ ] **步骤 1：创建 MaterialConfig.java**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import java.util.Map;

/**
 * 编译后的材质配置，表示 VMT 着色器类型 + 参数表的编译结果。
 * 由 MaterialCompiler 从 VmtParser.VmtMaterial 转换而来，
 * 通过 JNI 序列化为 JSON 传给 C++ 侧进行 Shader 编译。
 */
public class MaterialConfig {
    public String shaderType;                // VertexLitGeneric, UnlitGeneric...
    public Map<String, String> parameters;   // 原始 VMT 参数表
    public int renderPassCount;              // 编译后确定的 Pass 数量
    public RenderPassConfig[] passes;        // 每个 Pass 的渲染配置
    public boolean translucent, alphaTest, additive, noCull;

    public static class RenderPassConfig {
        public String shaderVariant;         // "base", "bump", "phong", "envmap", "detail"
        public String textureName;           // 此 Pass 绑定的纹理名
        public int blendMode;                // 0=NORMAL, 1=ADDITIVE, 2=ALPHA_TEST
        public int cullMode;                 // 0=BACK, 1=FRONT, 2=NONE
        public boolean depthWrite;
        public String[] defines;             // 此 Pass 的 GLSL define
    }

    /** 根据 VMT 参数确定渲染 Pass 数量 */
    public static int computePassCount(Map<String, String> params) {
        int passes = 1; // base diffuse 始终存在
        if (parseBool(params.get("$bumpmap"))) passes++;
        if (parseBool(params.get("$phong"))) passes++;
        if (params.containsKey("$envmap")) passes++;
        if (params.containsKey("$detail")) passes++;
        return passes;
    }

    private static boolean parseBool(String val) {
        if (val == null) return false;
        val = val.trim().toLowerCase();
        return val.equals("1") || val.equals("true") || val.equals("yes") || val.equals("on");
    }

    public static MaterialConfig fromVmt(VmtParser.VmtMaterial vmt) {
        MaterialConfig cfg = new MaterialConfig();
        cfg.shaderType = vmt.shader;
        cfg.parameters = vmt.parameters;
        cfg.translucent = vmt.isTransparent();
        cfg.alphaTest = vmt.isAlphaTest();
        cfg.additive = parseBool(vmt.parameters.get("$additive"));
        cfg.noCull = vmt.isNoCull();
        cfg.renderPassCount = computePassCount(vmt.parameters);
        // RenderPassConfig[] 由 MaterialCompiler 填充
        return cfg;
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew compileJava 2>&1 | findstr "error"`
预期：编译无错误（相关引用尚未建立，但此文件自身应编译通过）

---

### 任务 2：增强 VmtParser — 支持 `%includematerial` 继承链

**文件：**
- 修改：`src/main/java/.../client/model/VmtParser.java`
- 依赖：MaterialConfig (Task 1)

- [ ] **步骤 1：在 VmtParser 中添加 VmtIncludeResolver**

```java
// 添加到 VmtParser.java 末尾，在 VmtMaterial 类之后

/**
 * 解析 %includematerial 继承链。
 * Source Engine 的 VMT 可以继承另一个 VMT 的参数，类似 CSS 的 @import。
 * 此解析器按以下优先级合并参数：
 *   1. 子 VMT 显式定义的参数（最高）
 *   2. 父 VMT（被 include 的 VMT）的参数
 *   3. 着色器默认值（最低）
 */
public static class VmtIncludeResolver {
    private final java.util.function.Function<String, VmtMaterial> materialLoader;
    
    public VmtIncludeResolver(java.util.function.Function<String, VmtMaterial> materialLoader) {
        this.materialLoader = materialLoader;
    }
    
    /**
     * 解析一个 VMT 材质，追踪其 %includematerial 链并合并参数。
     * @param vmt 已解析的 VmtMaterial 对象
     * @param maxDepth 最大继承深度（防止循环引用）
     * @return 合并了所有父材质参数的新 VmtMaterial
     */
    public VmtMaterial resolve(VmtMaterial vmt, int maxDepth) {
        if (maxDepth <= 0) return vmt;
        
        String include = vmt.parameters.get("%includematerial");
        if (include == null || include.isEmpty()) return vmt;
        
        VmtMaterial parent = materialLoader.apply(include);
        if (parent == null) return vmt;
        
        VmtMaterial resolved = resolve(parent, maxDepth - 1);
        
        // 合并：子 VMT 的参数覆盖父 VMT
        VmtMaterial result = new VmtMaterial();
        result.shader = vmt.shader != null ? vmt.shader : resolved.shader;
        result.parameters.putAll(resolved.parameters);
        result.parameters.putAll(vmt.parameters); // 子覆盖父
        return result;
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew compileJava 2>&1 | findstr "error"`
预期：编译无错误

---

### 任务 3：创建 MaterialTree.java — 材质树管理器

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MaterialTree.java`

- [ ] **步骤 1：创建 VMT 材质树管理器**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VMT 材质树管理器。
 * 负责按路径加载、缓存、继承解析 .vmt 材质文件。
 * 线程安全（ConcurrentHashMap 缓存）。
 */
public class MaterialTree {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // 材质缓存：材质路径 → 编译后的 MaterialConfig
    private final Map<String, MaterialConfig> materialCache = new ConcurrentHashMap<>();
    
    // 原始 VMT 缓存：材质路径 → 解析后的 VmtMaterial
    private final Map<String, VmtParser.VmtMaterial> vmtCache = new ConcurrentHashMap<>();
    
    // 材质基础搜索路径列表
    private final Path[] searchPaths;
    
    private final VmtParser.VmtIncludeResolver resolver;
    
    public MaterialTree(Path... searchPaths) {
        this.searchPaths = searchPaths;
        this.resolver = new VmtParser.VmtIncludeResolver(this::loadRawVmt);
    }
    
    /**
     * 加载并编译材质。
     * 如果已缓存则直接返回。
     */
    public MaterialConfig getOrCompile(String materialPath) {
        return materialCache.computeIfAbsent(normalizePath(materialPath), path -> {
            try {
                VmtParser.VmtMaterial vmt = loadRawVmt(path);
                if (vmt == null) return null;
                VmtParser.VmtMaterial resolved = resolver.resolve(vmt, 8);
                MaterialConfig config = MaterialConfig.fromVmt(resolved);
                LOGGER.debug("[MaterialTree] Compiled material '{}': {} shader, {} passes",
                    path, config.shaderType, config.renderPassCount);
                return config;
            } catch (Exception e) {
                LOGGER.warn("[MaterialTree] Failed to compile material '{}': {}", path, e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * 从搜索路径中加载原始 .vmt 文件。
     */
    private VmtParser.VmtMaterial loadRawVmt(String materialPath) {
        // 先查缓存
        VmtParser.VmtMaterial cached = vmtCache.get(materialPath);
        if (cached != null) return cached;
        
        // 在搜索路径中查找 .vmt 文件
        String vmtPath = materialPath;
        if (!vmtPath.endsWith(".vmt")) vmtPath += ".vmt";
        vmtPath = vmtPath.replace('\\', '/');
        
        for (Path base : searchPaths) {
            Path file = base.resolve(vmtPath);
            if (Files.exists(file)) {
                try {
                    byte[] data = Files.readAllBytes(file);
                    VmtParser.VmtMaterial vmt = VmtParser.parse(data);
                    vmtCache.put(materialPath, vmt);
                    return vmt;
                } catch (IOException e) {
                    LOGGER.warn("[MaterialTree] Failed to read VMT '{}': {}", file, e.getMessage());
                }
            }
        }
        return null;
    }
    
    /**
     * 通过材质名在搜索路径中找到对应的纹理文件。
     * 返回最佳匹配路径，或 null。
     */
    public Path resolveTexturePath(String textureName) {
        if (textureName == null || textureName.isEmpty()) return null;
        String tex = textureName.replace('\\', '/').toLowerCase();
        if (!tex.endsWith(".vtf")) tex += ".vtf";
        
        for (Path base : searchPaths) {
            // 尝试 materials/ + texture 路径
            Path candidate = base.resolve("materials/" + tex);
            if (Files.exists(candidate)) return candidate;
            // 尝试直接路径
            candidate = base.resolve(tex);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }
    
    public void clearCache() {
        materialCache.clear();
        vmtCache.clear();
    }
    
    private static String normalizePath(String path) {
        if (path == null) return null;
        return path.replace('\\', '/').toLowerCase();
    }
}
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew compileJava 2>&1 | findstr "error"`
预期：编译无错误

---

### 任务 4：创建 MaterialCompiler.java — 材质编译器桥接

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MaterialCompiler.java`
- 依赖：MaterialConfig (Task 1), GmodNativeBridge (已有)

- [ ] **步骤 1：创建 MaterialConfig → JSON → JNI 编译桥接**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 材质编译器：将 MaterialConfig 序列化为 JSON，通过 JNI 传给 C++ 侧编译 Shader。
 * 缓存编译结果（materialId），避免重复编译。
 */
public class MaterialCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    
    // 材质路径 → materialId 缓存
    private static final Map<String, Integer> compiledMaterialCache = new ConcurrentHashMap<>();
    // materialId → 材质路径反向映射（用于引用计数释放）
    private static final Map<Integer, String> materialIdToPath = new ConcurrentHashMap<>();
    
    private static int nextMaterialId = 1;
    
    /**
     * 编译一个材质并返回 materialId。
     * 如果 C++ 编译不可用或失败，返回 0（表示用 Java 回退）。
     */
    public static int compile(MaterialConfig config, String materialPath) {
        if (config == null) return 0;
        
        // 检查缓存
        Integer cached = compiledMaterialCache.get(materialPath);
        if (cached != null) return cached;
        
        // 如果原生桥不可用，返回 0（Java 回退）
        if (!GmodNativeBridge.isAvailable()) return 0;
        
        try {
            // 序列化为 JSON
            String materialJson = GSON.toJson(config);
            
            // 通过 JNI 编译
            int materialId = nativeCompileMaterial(materialJson);
            if (materialId > 0) {
                compiledMaterialCache.put(materialPath, materialId);
                materialIdToPath.put(materialId, materialPath);
                LOGGER.debug("[MaterialCompiler] Compiled material '{}' → id={}", materialPath, materialId);
            }
            return materialId;
        } catch (Exception e) {
            LOGGER.warn("[MaterialCompiler] Compile failed for '{}': {}", materialPath, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 释放材质。
     */
    public static void release(String materialPath) {
        Integer id = compiledMaterialCache.remove(materialPath);
        if (id != null && id > 0 && GmodNativeBridge.isAvailable()) {
            materialIdToPath.remove(id);
            nativeReleaseMaterial(id);
        }
    }
    
    /**
     * 释放所有材质。
     */
    public static void releaseAll() {
        if (GmodNativeBridge.isAvailable()) {
            for (int id : compiledMaterialCache.values()) {
                nativeReleaseMaterial(id);
            }
        }
        compiledMaterialCache.clear();
        materialIdToPath.clear();
    }
    
    // JNI native methods — 实现在 jni_bridge.cpp 中
    private static native int nativeCompileMaterial(String materialJson);
    private static native void nativeReleaseMaterial(int materialId);
}
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew compileJava 2>&1 | findstr "error"`
预期：编译无错误（nativeCompileMaterial 暂为 private static native 存根，运行时才真正链接）

---

### 任务 5：创建 shader_generator.h — C++ 着色器生成器头文件

**文件：**
- 创建：`src/main/native/include/shader_generator.h`

- [ ] **步骤 1：创建 ShaderGenerator 头文件**

```cpp
#ifndef SHADER_GENERATOR_H
#define SHADER_GENERATOR_H

#include <string>
#include <vector>
#include <unordered_map>
#include <cstdint>

/**
 * ShaderGenerator — 从 VMT 材质属性生成 GLSL Shader。
 * 
 * 生成策略：
 * - 解析 MaterialConfig JSON（从 Java 端传入）
 * - 根据激活的 VMT 特性（bumpmap/phong/envmap/detail）组合 Shader 变体
 * - 生成对应的 GLSL 顶点着色器 + 片段着色器
 * - 编译为 OpenGL Shader Program，返回 program ID
 */
class ShaderGenerator {
public:
    struct ShaderSource {
        std::string vertexSource;
        std::string fragmentSource;
    };

    struct ShaderProgram {
        uint32_t programId = 0;
        std::string variantName;     // "base", "bump", "phong", "full"
        std::vector<std::string> defines;
        
        // Uniform 位置缓存
        int uModelMatrix = -1;
        int uViewMatrix = -1;
        int uProjectionMatrix = -1;
        int uLightDir = -1;
        int uLightColor = -1;
        int uAmbientColor = -1;
        int uCameraPos = -1;
        
        // 纹理 uniform
        int uBaseTexture = -1;
        int uBumpTexture = -1;
        int uEnvTexture = -1;
        int uDetailTexture = -1;
        
        // 材质参数 uniform
        int uPhongBoost = -1;
        int uPhongExponent = -1;
        int uEnvMapTint = -1;
        int uDetailBlendFactor = -1;
    };

    /**
     * 生成 GLSL Shader 源码。
     * @param properties VMT 材质属性键值对（从 MaterialConfig.parameters 序列化而来）
     * @param variant Shader 变体名：base / bump / phong / envmap / detail / full
     * @return ShaderSource 包含顶点和片段着色器源码
     */
    static ShaderSource generateSource(
        const std::unordered_map<std::string, std::string>& properties,
        const std::string& variant);

    /**
     * 编译 Shader Program。
     * @param source 由 generateSource 生成的源码
     * @return 编译后的 ShaderProgram，programId=0 表示编译失败
     */
    static ShaderProgram compile(const ShaderSource& source);

    /**
     * 从属性集推断需要的 Shader 变体列表。
     * 例如：有 $bumpmap + $phong → {"base", "bump", "phong"}
     */
    static std::vector<std::string> determineVariants(
        const std::unordered_map<std::string, std::string>& properties);

    /**
     * 释放 Shader Program。
     */
    static void destroy(ShaderProgram& program);

private:
    static std::string generateVertexSource(const std::string& variant);
    static std::string generateFragmentSource(
        const std::unordered_map<std::string, std::string>& properties,
        const std::string& variant);
    static std::string getVersionDirective();
    static uint32_t compileShader(uint32_t type, const std::string& source);
};

#endif // SHADER_GENERATOR_H
```

---

### 任务 6：实现 shader_generator.cpp — GLSL 代码生成

**文件：**
- 创建：`src/main/native/src/shader_generator.cpp`

- [ ] **步骤 1：实现通用 Shader 源码框架和编译函数**

```cpp
#include "shader_generator.h"
#include <glad/gl.h>
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

static uint32_t compileShader(uint32_t type, const std::string& source) {
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
```

- [ ] **步骤 2：验证编译**

运行：C++ 编译检查（需要 MSVC/MinGW + OpenGL 头文件）
```
cd src/main/native && g++ -std=c++20 -c src/shader_generator.cpp -I include -I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" 2>&1
```
预期：编译无错误（可能需要 GL 头文件路径调整）

---

### 任务 7：创建 material_system.h/cpp — C++ 材质系统

**文件：**
- 创建：`src/main/native/include/material_system.h`
- 创建：`src/main/native/src/material_system.cpp`

- [ ] **步骤 1：创建 material_system.h**

```cpp
#ifndef MATERIAL_SYSTEM_H
#define MATERIAL_SYSTEM_H

#include <string>
#include <vector>
#include <unordered_map>
#include <cstdint>
#include <memory>
#include "shader_generator.h"

/**
 * MaterialSystem — 材质渲染系统。
 * 
 * 管理从 Java 端传来的材质配置，驱动 ShaderGenerator 编译 Shader，
 * 维护材质 ID → ShaderProgram + RenderPass 映射，
 * 执行多 Pass 渲染。
 */
class MaterialSystem {
public:
    struct RenderPass {
        ShaderGenerator::ShaderProgram program;
        std::string textureName;       // 此 Pass 绑定的纹理名
        uint32_t textureId = 0;        // 已上传的 OpenGL 纹理 ID
        int blendMode = 0;             // 0=NORMAL, 1=ADDITIVE, 2=ALPHA_TEST
        int cullMode = 0;              // 0=BACK, 1=FRONT, 2=NONE
        bool depthWrite = true;
    };

    struct CompiledMaterial {
        int id;
        std::string shaderType;
        std::vector<RenderPass> passes;
        bool translucent = false;
        bool alphaTest = false;
        bool additive = false;
        bool noCull = false;
    };

    /**
     * 编译材质配置（JSON 格式从 Java 传入）。
     * @param materialJson MaterialConfig 的 JSON 序列化
     * @return materialId，0 表示编译失败
     */
    int compile(const std::string& materialJson);

    /**
     * 绑定材质到渲染管线。
     * 设置此材质需要的一切渲染状态。
     */
    bool bind(int materialId);

    /**
     * 执行单个 Pass 渲染。
     */
    bool renderPass(int materialId, int passIndex,
                    uint32_t vao, int indexCount,
                    const float* modelMatrix);

    /**
     * 获取材质的 Pass 数量。
     */
    int getPassCount(int materialId) const;

    /**
     * 释放材质。
     */
    void release(int materialId);

    /**
     * 释放所有材质。
     */
    void releaseAll();

private:
    std::unordered_map<int, std::unique_ptr<CompiledMaterial>> materials;
    int nextId = 1;
    
    // 从 JSON 解析材质配置
    CompiledMaterial* parseMaterialConfig(const std::string& json, int id);
};

#endif // MATERIAL_SYSTEM_H
```

- [ ] **步骤 2：实现 material_system.cpp**

```cpp
#include "material_system.h"
#include <glad/gl.h>
#include <cstring>
#include <sstream>
#include <vector>
#include <cstdio>

// Simple JSON parser (minimal, for MaterialConfig only)
// In production, consider using nlohmann/json or similar
static std::string jsonGetString(const std::string& json, const std::string& key) {
    auto pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return "";
    auto colon = json.find(':', pos + key.size() + 2);
    if (colon == std::string::npos) return "";
    auto start = json.find('"', colon + 1);
    if (start == std::string::npos) return "";
    auto end = json.find('"', start + 1);
    if (end == std::string::npos) return "";
    return json.substr(start + 1, end - start - 1);
}

static bool jsonGetBool(const std::string& json, const std::string& key, bool def) {
    auto pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return def;
    auto colon = json.find(':', pos + key.size() + 2);
    if (colon == std::string::npos) return def;
    auto val = json.find_first_of("tf01", colon + 1);
    if (val == std::string::npos) return def;
    char c = json[val];
    return (c == 't') || (c == '1');
}

static int jsonGetInt(const std::string& json, const std::string& key, int def) {
    auto pos = json.find("\"" + key + "\"");
    if (pos == std::string::npos) return def;
    auto colon = json.find(':', pos + key.size() + 2);
    if (colon == std::string::npos) return def;
    int val = 0;
    sscanf(json.c_str() + colon + 1, "%d", &val);
    return val;
}

MaterialSystem::CompiledMaterial* MaterialSystem::parseMaterialConfig(
    const std::string& json, int id)
{
    auto mat = std::make_unique<CompiledMaterial>();
    mat->id = id;
    mat->shaderType = jsonGetString(json, "shaderType");
    mat->translucent = jsonGetBool(json, "translucent", false);
    mat->alphaTest = jsonGetBool(json, "alphaTest", false);
    mat->additive = jsonGetBool(json, "additive", false);
    mat->noCull = jsonGetBool(json, "noCull", false);
    
    // Parse renderPassCount and passes (simplified)
    int passCount = jsonGetInt(json, "renderPassCount", 1);
    
    // Build shader variant name from shaderType and params
    std::string variant = "base";
    if (mat->shaderType.find("VertexLitGeneric") != std::string::npos) {
        // Check for phong/bump via presence in parameters
        // For now, generate base + phong passes if phong is likely
        // Full parameter parsing will come from the JSON parameters map
        variant = "full";
    }
    
    // Create passes
    for (int i = 0; i < passCount; i++) {
        RenderPass pass;
        
        std::string passVariant = "base";
        if (i == 1) passVariant = "bump";
        else if (i == 2) passVariant = "phong";
        else if (i == 3) passVariant = "envmap";
        
        // Extract parameters from JSON for shader generation
        std::unordered_map<std::string, std::string> props;
        // Parse the "parameters" JSON object (simplified)
        auto paramsStart = json.find("\"parameters\"");
        if (paramsStart != std::string::npos) {
            auto brace = json.find('{', paramsStart);
            if (brace != std::string::npos) {
                auto close = json.find('}', brace);
                if (close != std::string::npos) {
                    std::string paramsSection = json.substr(brace + 1, close - brace - 1);
                    // Parse key-value pairs (rough)
                    size_t q1 = 0;
                    while ((q1 = paramsSection.find('"', q1)) != std::string::npos) {
                        auto q2 = paramsSection.find('"', q1 + 1);
                        if (q2 == std::string::npos) break;
                        std::string k = paramsSection.substr(q1 + 1, q2 - q1 - 1);
                        auto colon = paramsSection.find(':', q2 + 1);
                        if (colon == std::string::npos) break;
                        auto vStart = paramsSection.find_first_not_of(" \t\"", colon + 1);
                        if (vStart == std::string::npos) break;
                        auto vEnd = paramsSection.find_first_of(",}\"", vStart);
                        std::string v = (vEnd != std::string::npos) 
                            ? paramsSection.substr(vStart, vEnd - vStart) 
                            : paramsSection.substr(vStart);
                        // Remove trailing quotes
                        if (!v.empty() && v.back() == '"') v.pop_back();
                        props[k] = v;
                        q1 = (vEnd != std::string::npos) ? vEnd : q2 + 1;
                    }
                }
            }
        }
        
        auto src = ShaderGenerator::generateSource(props, passVariant);
        pass.program = ShaderGenerator::compile(src);
        
        mat->passes.push_back(std::move(pass));
    }
    
    auto* raw = mat.release();
    materials[id] = std::unique_ptr<CompiledMaterial>(raw);
    return raw;
}

int MaterialSystem::compile(const std::string& materialJson) {
    int id = nextId++;
    CompiledMaterial* mat = parseMaterialConfig(materialJson, id);
    if (!mat || mat->passes.empty()) {
        return 0;
    }
    return id;
}

bool MaterialSystem::bind(int materialId) {
    auto it = materials.find(materialId);
    if (it == materials.end()) return false;
    
    auto& mat = it->second;
    
    // Set render states
    if (mat->noCull) {
        glDisable(GL_CULL_FACE);
    } else {
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
    }
    
    if (mat->alphaTest) {
        glEnable(GL_ALPHA_TEST);
    } else {
        glDisable(GL_ALPHA_TEST);
    }
    
    if (mat->translucent || mat->additive) {
        glEnable(GL_BLEND);
        if (mat->additive) {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        } else {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        }
    } else {
        glDisable(GL_BLEND);
    }
    
    return true;
}

bool MaterialSystem::renderPass(int materialId, int passIndex,
                                uint32_t vao, int indexCount,
                                const float* modelMatrix)
{
    auto it = materials.find(materialId);
    if (it == materials.end()) return false;
    if (passIndex < 0 || passIndex >= (int)it->second->passes.size()) return false;
    
    auto& pass = it->second->passes[passIndex];
    if (!pass.program.programId) return false;
    
    glUseProgram(pass.program.programId);
    
    // Set uniforms (simplified — assumes a fixed view/projection via GlRenderer)
    if (pass.program.uModelMatrix >= 0)
        glUniformMatrix4fv(pass.program.uModelMatrix, 1, GL_FALSE, modelMatrix);
    
    // Bind texture
    if (pass.textureId && pass.program.uBaseTexture >= 0) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, pass.textureId);
        glUniform1i(pass.program.uBaseTexture, 0);
    }
    
    // Draw
    glBindVertexArray(vao);
    glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
    glBindVertexArray(0);
    
    return true;
}

int MaterialSystem::getPassCount(int materialId) const {
    auto it = materials.find(materialId);
    if (it == materials.end()) return 0;
    return (int)it->second->passes.size();
}

void MaterialSystem::release(int materialId) {
    auto it = materials.find(materialId);
    if (it != materials.end()) {
        for (auto& pass : it->second->passes) {
            ShaderGenerator::destroy(pass.program);
        }
        materials.erase(it);
    }
}

void MaterialSystem::releaseAll() {
    for (auto& pair : materials) {
        for (auto& pass : pair.second->passes) {
            ShaderGenerator::destroy(pass.program);
        }
    }
    materials.clear();
}
```

- [ ] **步骤 2：验证编译**

运行：`cd src/main/native && g++ -std=c++20 -c src/material_system.cpp src/shader_generator.cpp -I include -I"%JAVA_HOME%/include" -I"%JAVA_HOME%/include/win32" 2>&1`
预期：编译无错误或仅缺 GL 头文件（完整构建通过 CMake）

---

### 任务 8：创建 MaterialBridge.java — 材质 JNI 接口

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MaterialBridge.java`

- [ ] **步骤 1：创建 JNI 桥接类**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 材质系统的 JNI 桥接。
 * 与 GmodNativeBridge 分离，职责单一。
 * C++ 对应实现在 jni_bridge.cpp 中（以 Java_transferstation_..._MaterialBridge_ 命名）。
 */
public class MaterialBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static boolean nativeAvailable = false;
    
    /**
     * 检查原生材质系统是否可用。
     */
    public static synchronized boolean isAvailable() {
        if (!nativeAvailable && GmodNativeBridge.isAvailable()) {
            try {
                nativeAvailable = nativeInitialize();
            } catch (UnsatisfiedLinkError e) {
                LOGGER.debug("[MaterialBridge] Native material system not available: {}", e.getMessage());
                nativeAvailable = false;
            }
        }
        return nativeAvailable;
    }
    
    /**
     * 编译材质配置。
     * @param materialJson MaterialConfig 的 JSON 序列化
     * @return materialId (0 = 编译失败)
     */
    public static int compile(String materialJson) {
        if (!isAvailable()) return 0;
        try {
            return nativeCompileMaterial(materialJson);
        } catch (Exception e) {
            LOGGER.warn("[MaterialBridge] Compile failed: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * 绑定材质（设置 OpenGL 渲染状态）。
     */
    public static boolean bind(int materialId) {
        if (!isAvailable()) return false;
        return nativeBindMaterial(materialId);
    }
    
    /**
     * 渲染单个 Pass。
     */
    public static boolean renderPass(int materialId, int passIndex,
                                      long modelHandle, int meshIndex,
                                      float[] modelMatrix, int packedLight) {
        if (!isAvailable()) return false;
        return nativeRenderPass(materialId, passIndex, modelHandle, meshIndex, modelMatrix, packedLight);
    }
    
    /**
     * 释放材质。
     */
    public static void release(int materialId) {
        if (!isAvailable()) return;
        try {
            nativeReleaseMaterial(materialId);
        } catch (Exception e) {
            LOGGER.debug("[MaterialBridge] Release failed: {}", e.getMessage());
        }
    }
    
    // Native methods
    private static native boolean nativeInitialize();
    private static native int nativeCompileMaterial(String materialJson);
    private static native boolean nativeBindMaterial(int materialId);
    private static native boolean nativeRenderPass(int materialId, int passIndex,
                                                    long modelHandle, int meshIndex,
                                                    float[] modelMatrix, int packedLight);
    private static native void nativeReleaseMaterial(int materialId);
}
```

- [ ] **步骤 2：验证编译**

运行：`./gradlew compileJava 2>&1 | findstr "error"`
预期：编译无错误（native 方法在 runtime 才链接）

---

### 任务 9：扩展 jni_bridge.cpp — 添加材质系统 JNI 方法

**文件：**
- 修改：`src/main/native/src/jni_bridge.cpp`
- 依赖：MaterialSystem (Task 7), ShaderGenerator (Task 6)

- [ ] **步骤 1：在 jni_bridge.cpp 末尾（extern "C" 块内）添加材质 JNI 方法**

```cpp
// ===================== Material System JNI =====================

static MaterialSystem s_materialSystem;

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_MaterialBridge_nativeInitialize(
    JNIEnv* env, jclass)
{
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_MaterialBridge_nativeCompileMaterial(
    JNIEnv* env, jclass, jstring materialJson)
{
    if (materialJson == nullptr) return 0;
    const char* jsonChars = env->GetStringUTFChars(materialJson, nullptr);
    if (jsonChars == nullptr) return 0;
    
    std::string json(jsonChars);
    env->ReleaseStringUTFChars(materialJson, jsonChars);
    
    try {
        int id = s_materialSystem.compile(json);
        return static_cast<jint>(id);
    } catch (const std::exception& e) {
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_MaterialBridge_nativeBindMaterial(
    JNIEnv* env, jclass, jint materialId)
{
    return s_materialSystem.bind(static_cast<int>(materialId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_MaterialBridge_nativeRenderPass(
    JNIEnv* env, jclass,
    jint materialId, jint passIndex,
    jlong modelHandle, jint meshIndex,
    jfloatArray modelMatrix16, jint packedLight)
{
    // Look up mesh VAO from model cache
    auto it = s_modelCache.find(static_cast<int64_t>(modelHandle));
    if (it == s_modelCache.end()) return JNI_FALSE;
    
    int idx = static_cast<int>(meshIndex);
    if (idx < 0 || idx >= static_cast<int>(it->second->meshes.size())) return JNI_FALSE;
    
    const auto& mesh = it->second->meshes[idx];
    if (!mesh.glVao || mesh.indexCount <= 0) return JNI_FALSE;
    
    // Get model matrix
    if (modelMatrix16 == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(modelMatrix16) < 16) return JNI_FALSE;
    jfloat* matrix = env->GetFloatArrayElements(modelMatrix16, nullptr);
    if (matrix == nullptr) return JNI_FALSE;
    
    bool ok = s_materialSystem.renderPass(
        static_cast<int>(materialId),
        static_cast<int>(passIndex),
        mesh.glVao, mesh.indexCount, matrix);
    
    env->ReleaseFloatArrayElements(modelMatrix16, matrix, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_MaterialBridge_nativeReleaseMaterial(
    JNIEnv* env, jclass, jint materialId)
{
    s_materialSystem.release(static_cast<int>(materialId));
}
```

- [ ] **步骤 2：在 jni_bridge.cpp 头部添加 include**

```cpp
// 添加到文件顶部已有的 include 之后：
#include "material_system.h"
```

- [ ] **步骤 3：更新 nativeClearAllCaches 方法，在末尾添加材质清理**

```cpp
// 在 Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeClearAllCaches()
// 方法的末尾，在当前清理代码之后添加：
    s_materialSystem.releaseAll();
```

---

### 任务 10：更新 CMakeLists.txt — 添加新源文件

**文件：**
- 修改：`src/main/native/CMakeLists.txt`

- [ ] **步骤 1：添加新源文件**

```cmake
# 修改 file(GLOB ...) 行已经在 CMakeLists.txt 中自动匹配 src/*.cpp，
# 因此添加 src/shader_generator.cpp 和 src/material_system.cpp 后会自动被包含。
# 只需要确认现有的行：
#   file(GLOB NATIVE_SOURCES "src/*.cpp")
# 已经覆盖了这两个新文件。

# 如果需要链接 glad (OpenGL loader)，添加：
# find_package(glad) 或手动添加 glad.c 到源文件列表
```

无需修改 CMakeLists.txt（file(GLOB) 会自动匹配新文件。如果需要 GLAD，需添加 glad 源文件路径）。

---

### 任务 11：集成到 MdlModelRenderer — 材质渲染管线对接

**文件：**
- 修改：`src/main/java/.../client/model/MdlModelRenderer.java`
- 修改：`src/main/java/.../client/model/ModelLoadManager.java`（参考）

- [ ] **步骤 1：在 MdlModelRenderer 中添加材质初始化**

```java
// 添加到 MdlModelRenderer 类中，在 static 字段区：

// 材质系统
private static MaterialTree materialTree = null;
private static final Map<String, Integer> meshMaterialMap = new HashMap<>();

// 添加到 MdlModelRenderer 中（在 setModelsDir 或类似的初始化方法中）：
public static void initMaterialSystem(java.nio.file.Path modelsDir) {
    materialTree = new MaterialTree(modelsDir);
    MaterialBridge.isAvailable(); // 触发 native 初始化检查
}
```

- [ ] **步骤 2：在 loadModel 方法中添加材质编译逻辑**

```java
// 在 MdlModelRenderer.loadModel() 方法中，解析 VMT 并编译材质。
// 在 JavaModelRenderer.setModelData(data) 之后添加：
if (materialTree != null && data != null) {
    for (int meshIdx = 0; meshIdx < data.meshes.size(); meshIdx++) {
        var mesh = data.meshes.get(meshIdx);
        if (mesh.materialName != null && !mesh.materialName.isEmpty()) {
            MaterialConfig config = materialTree.getOrCompile(mesh.materialName);
            if (config != null) {
                int materialId = MaterialCompiler.compile(config, mesh.materialName);
                if (materialId > 0) {
                    meshMaterialMap.put(data.packageDir + ":" + meshIdx, materialId);
                }
            }
        }
    }
}
```

- [ ] **步骤 3：在 render 方法中使用材质渲染（可选回退）**

```java
// 在 MdlModelRenderer.render() 方法中，修改原生渲染分支。
// 当前 nativeRenderModel 仍保留作为快速路径。
// 新增材质感知渲染路径（当 mesh 有编译材质时）：
private static void renderWithMaterials(long handle, SourceModelData data,
    LivingEntity entity, PoseStack poseStack, int packedLight, float partialTicks)
{
    if (!MaterialBridge.isAvailable() || materialTree == null) {
        // 回退到原有渲染
        return;
    }
    
    poseStack.pushPose();
    // ... (原有的矩阵变换代码)
    
    float[] matArray = new float[16];
    matrix.get(matArray);
    
    for (int meshIdx = 0; meshIdx < data.meshes.size(); meshIdx++) {
        Integer matId = meshMaterialMap.get(data.packageDir + ":" + meshIdx);
        if (matId == null) continue;
        
        int passCount = MaterialBridge.getPassCount(matId); // 需要在 MaterialBridge 中暴露
        for (int pass = 0; pass < passCount; pass++) {
            MaterialBridge.renderPass(matId, pass, handle, meshIdx, matArray, packedLight);
        }
    }
    
    poseStack.popPose();
}
```

- [ ] **步骤 4：在 cleanup/shutdown 方法中添加材质释放**

```java
// 在 unloadAll() 方法中添加：
MaterialCompiler.releaseAll();
```

---

### 任务 12：添加缓存的 getPassCount JNI 方法

- [ ] **步骤 1：在 MaterialBridge.java 中添加 getPassCount**

```java
// 添加到 MaterialBridge.java
public static int getPassCount(int materialId) {
    if (!isAvailable()) return 0;
    try {
        return nativeGetPassCount(materialId);
    } catch (Exception e) {
        return 0;
    }
}

private static native int nativeGetPassCount(int materialId);
```

- [ ] **步骤 2：在 jni_bridge.cpp 中添加 getPassCount 实现**

```cpp
JNIEXPORT jint JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_MaterialBridge_nativeGetPassCount(
    JNIEnv* env, jclass, jint materialId)
{
    return static_cast<jint>(s_materialSystem.getPassCount(static_cast<int>(materialId)));
}
```

- [ ] **步骤 3：验证完整编译**

运行：`./gradlew compileJava 2>&1 | findstr "error"`
运行：`cd src/main/native && mkdir -p build && cd build && cmake .. 2>&1`
预期：Java 编译无错误，CMake 配置成功

---

### 范围外（后续阶段）

此计划不包含以下内容，留给后续阶段：

- **P2 (VPhysics)**：Bullet Physics 集成、约束布娃娃、碰撞体
- **P3 (Animation)**：AnimationFSM、动画混合、Flex 面部系统
- 粒子/特效系统（已降级为 MC 原版粒子）

### 自检清单

- [ ] 每个新增 Java 文件遵循现有包结构 (`transferstation.transferstation_whimsicalideas.client.model`)
- [ ] 每个新增 C++ 文件遵循现有包含路径 (`src/main/native/include/` + `src/main/native/src/`)
- [ ] JNI 命名遵循 `Java_<package>_<class>_<method>` 模式，包名中 `.` 替换为 `_`，`_` 替换为 `_1`
- [ ] 所有 JNI native 方法在 Java 和 C++ 侧签名一致
- [ ] 材质编译失败时降级回退到现有 Java 渲染（materialId=0 即回退）
- [ ] 无占位符、无 TODO、无未实现的 dangling 引用
