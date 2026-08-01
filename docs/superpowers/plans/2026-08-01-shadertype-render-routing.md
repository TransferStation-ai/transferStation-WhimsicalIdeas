# ShaderType 渲染路由接入 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在 C++ 原生渲染路径与 Java 辅助渲染路径同时接入 VMT ShaderType 路由，使 UnlitGeneric（全亮度）、EyeRefract（高光/全亮）、Sprite（billboard）、Skybox/ToolTexture（跳过）获得正确渲染行为。

**架构：** C++ 侧在 `VmtInfo` 解析 shader 名 + `$selfillum`/`$envmap`，推断 `RenderMode`（BASE/UNLIT/EYE/SKIP）写入 `MeshData`，`GlRenderer` 拆为 3 个 program（BASE/UNLIT/EYE），SKIP 不建 vao 不渲染；Java 侧 `SourceModelData.MeshData` 增加 `shaderType`（String，存 shader 名）贯通 VMT→mesh→磁盘缓存→渲染路由，`JavaModelRenderer` 对 UNLIT/EYE_REFRACT 走 fullbright、SKYBOX/TOOL_TEXTURE 跳过、SPRITE 做 billboard 矩阵替换。

**技术栈：** C++20 + OpenGL 3.3（glad 动态加载）、JNI、Java 17 + Forge 1.20.1、JUnit 5。

**验证命令：**
- Java 编译：`gradlew compileJava`（或 IDE 构建）
- C++ 编译：`gradlew buildNativeDll`（需 CMake + MinGW，机器有 `C:\msys64\mingw64`）
- 全部测试：`gradlew test`
- 目测：运行环境加载 valve_content NPC 模型（metrocop 等）

---

## 文件结构

| 文件 | 职责 | 操作 |
|---|---|---|
| `src/main/native/include/studio_types.h` | `RenderMode` 枚举 + `MeshData.renderMode` 字段 | 修改 |
| `src/main/native/src/model_loader.cpp` | `VmtInfo` 扩展、shader 名/`$selfillum`/`$envmap` 解析、`inferRenderMode`、VMT→mesh 与 SMD 路径填充 renderMode | 修改 |
| `src/main/native/include/gl_renderer.h` | 3 program 成员、`renderMesh` 加 mode 参数、相机/高光 setter | 修改 |
| `src/main/native/src/gl_renderer.cpp` | 3 program 编译、UNLIT/EYE fragment shader、`renderMesh` 按 mode 路由、shutdown | 修改 |
| `src/main/native/src/native_bridge.cpp` | `renderMeshList` 传 mode + SKIP 跳过、`uploadMeshes` 跳过 SKIP、`nativeSetCameraPosition` JNI | 修改 |
| `src/main/java/.../client/model/SourceModelData.java` | `MeshData`/`Builder`/`MeshTextureInfo` 增加 `shaderType` 字段 | 修改 |
| `src/main/java/.../client/model/MeshDecimator.java` | 2 处 mesh 拷贝透传 shaderType | 修改 |
| `src/main/java/.../client/model/ModelLoadManager.java` | 填充 shaderType、磁盘缓存序列化（版本 25→26） | 修改 |
| `src/main/java/.../client/model/JavaModelRenderer.java` | UNLIT/EYE 全亮、SKYBOX/TOOL 跳过、SPRITE billboard | 修改 |
| `src/main/java/.../client/model/GmodNativeBridge.java` | `nativeSetCameraPosition` 声明 | 修改 |
| `src/main/java/.../client/model/MdlModelRenderer.java` | `renderNative` 每帧传相机位置 | 修改 |
| `src/test/java/.../client/model/SourceModelDataTest.java` | shaderType 透传测试 | 修改 |
| `src/test/java/.../client/model/VmtShaderTypeTest.java` | `ShaderType.fromName` 全覆盖测试 | 创建 |

**注意：** 不改 `shader_generator.cpp`（死代码）、不改 MDL/VTF 解析层、不做真实 EyeRefract 折射、不做 `$envmap` 反射。

---

### 任务 1：C++ 数据结构 — RenderMode 枚举与 MeshData 字段

**文件：**
- 修改：`src/main/native/include/studio_types.h:243-256`

- [ ] **步骤 1：在 `studio_types.h` 的 `MeshData` 结构上方添加枚举**

在 `// ===================== Mesh Data ... =====================` 段（第 235 行附近）插入：

```cpp
enum class RenderMode { BASE, UNLIT, EYE, SKIP };
```

- [ ] **步骤 2：给 `MeshData` 添加字段**

在 `studio_types.h:243-256` 的 `MeshData` 结构末尾（`bool noCull = false;` 之后）添加：

```cpp
    RenderMode renderMode = RenderMode::BASE;
```

- [ ] **步骤 3：编译验证**

运行：`gradlew buildNativeDll`
预期：BUILD SUCCESSFUL（后续任务会逐步使用该字段，本任务仅验证编译不受影响）

- [ ] **步骤 4：Commit**

```bash
git add src/main/native/include/studio_types.h
git commit -m "feat(native): add RenderMode enum and MeshData.renderMode"
```

---

### 任务 2：C++ VMT 解析 — shader 名 / $selfillum / $envmap 与 RenderMode 推断

**文件：**
- 修改：`src/main/native/src/model_loader.cpp:36-130`

- [ ] **步骤 1：扩展 `VmtInfo` 结构（model_loader.cpp:36-48）**

在 `VmtInfo` 的 `baseTexture` 字段上方添加：

```cpp
    std::string shaderName;  // VMT 第一行 shader 名，如 "UnlitGeneric"
    bool selfIllum = false;  // $selfillum
    bool envmap = false;     // $envmap 键存在
```

- [ ] **步骤 2：`parseVmtMaterial` 解析 shader 名与新增键（model_loader.cpp:74-77 的 while 循环开头）**

在 `while (std::getline(file, line))` 内、`size_t start = ...` 之后、`line = line.substr(start);` 之后插入：

```cpp
        if (info.shaderName.empty() && !line.empty()
            && line[0] != '{' && line[0] != '}' && line[0] != '/') {
            std::string token = line;
            size_t tokStart = token.find_first_not_of(" \t\"");
            if (tokStart != std::string::npos) token = token.substr(tokStart);
            size_t tokEnd = token.find_first_of(" \t\r\"");
            if (tokEnd != std::string::npos) token = token.substr(0, tokEnd);
            if (!token.empty()) info.shaderName = token;
        }
```

在现有 `$alphatest` 解析（`if (!at.empty()) info.alphaTest = boolVal(at);`）之后插入：

```cpp
        std::string si = extractValue(line, "$selfillum");
        if (!si.empty()) info.selfIllum = boolVal(si);
        if (info.envmap == false && !extractValue(line, "$envmap").empty()) {
            info.envmap = true;
        }
```

- [ ] **步骤 3：添加 `inferRenderMode` 推断函数**

在 `parseVmtMaterial` 函数定义（model_loader.cpp:130 的 `}` 之后、`toLowerInPlace` 之前）插入：

```cpp
static RenderMode inferRenderMode(const VmtInfo& info) {
    std::string s = ModelLoader::toLower(info.shaderName);
    if (s.find("unlitgeneric") != std::string::npos || info.selfIllum) {
        return RenderMode::UNLIT;
    }
    if (s.find("eyerefract") != std::string::npos) {
        return RenderMode::EYE;
    }
    if (s.find("skybox") != std::string::npos
        || s.find("tooltexture") != std::string::npos
        || s.find("tools/tool") != std::string::npos) {
        return RenderMode::SKIP;
    }
    return RenderMode::BASE;
}
```

`ModelLoader::toLower` 声明在 `model_loader.h:107`，`inferRenderMode` 位于 `ModelLoader::toLowerInPlace` 定义之前调用它没有问题（声明可见）。

- [ ] **步骤 4：编译验证**

运行：`gradlew buildNativeDll`
预期：BUILD SUCCESSFUL，无新增警告

- [ ] **步骤 5：Commit**

```bash
git add src/main/native/src/model_loader.cpp
git commit -m "feat(native): parse shader name, selfillum, envmap in VmtInfo with RenderMode inference"
```

---

### 任务 3：C++ mesh 关联 renderMode + SKIP 不建 vao

**文件：**
- 修改：`src/main/native/src/model_loader.cpp:772-788`（MDL 路径）
- 修改：`src/main/native/src/model_loader.cpp:967-1002`（SMD 路径）
- 修改：`src/main/native/src/native_bridge.cpp:129-136`（uploadMeshes）

- [ ] **步骤 1：MDL 路径 VMT→mesh 复制处设置 renderMode（model_loader.cpp:785）**

在 `mesh.noCull = vmtInfo.noCull;` 之后、`break;` 之前插入：

```cpp
                            mesh.renderMode = inferRenderMode(vmtInfo);
```

- [ ] **步骤 2：SMD 路径设置 renderMode（model_loader.cpp:967-993 的 mesh 构建循环）**

在 SMD mesh 构建循环中、`if (texIt != texNameToIdx.end()) { ... }` 块之后、`mesh.indices = std::move(meshIndices[material]);` 之前插入：

```cpp
            for (auto& [key, vmtInfo] : vmtInfoMap) {
                if (ModelLoader::toLower(key) == matLower) {
                    mesh.renderMode = inferRenderMode(vmtInfo);
                    break;
                }
            }
```

（`matLower` 变量已在 972 行定义。）

- [ ] **步骤 3：SKIP mesh 不建 vao（native_bridge.cpp:129-136 的 uploadMeshes lambda）**

在 `if (mesh.vertices.empty() || mesh.indices.empty()) continue;` 之后插入：

```cpp
                if (mesh.renderMode == RenderMode::SKIP) continue;
```

- [ ] **步骤 4：编译验证**

运行：`gradlew buildNativeDll`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add src/main/native/src/model_loader.cpp src/main/native/src/native_bridge.cpp
git commit -m "feat(native): assign renderMode to meshes and skip SKIP meshes at VAO upload"
```

---

### 任务 4：C++ GlRenderer 多 program

**文件：**
- 修改：`src/main/native/include/gl_renderer.h:28-42`
- 修改：`src/main/native/src/gl_renderer.cpp:149-188, 191-260, 416-498, 500-519`

- [ ] **步骤 1：更新头文件（gl_renderer.h:28-42）**

`renderMesh` 声明加 `RenderMode mode = RenderMode::BASE` 参数，私有成员拆为 3 个 program，新增 setter：

```cpp
    static void renderMesh(uint32_t vao, int indexCount, uint32_t textureId,
                           const float* modelMatrix, int packedLight,
                           const float* colorTint = nullptr,
                           RenderMode mode = RenderMode::BASE);

    static void setCameraPosition(float x, float y, float z);
    static void setPhongBoost(float boost);

    static void shutdown();

private:
    static const char* VERTEX_SHADER_SOURCE;
    static const char* FRAGMENT_SHADER_SOURCE;
    static const char* FRAGMENT_SHADER_UNLIT;
    static const char* FRAGMENT_SHADER_EYE;
    static uint32_t s_programBase;
    static uint32_t s_programUnlit;
    static uint32_t s_programEye;
    static bool s_initialized;
    static float s_cameraPos[3];
    static float s_phongBoost;
```

- [ ] **步骤 2：共享 VERTEX_SHADER 增加世界坐标输出（gl_renderer.cpp:149-165）**

在 `uniform mat4 u_modelViewProjection;` 之后添加 `uniform mat4 u_modelMatrix;`，`out vec3 v_normal;` 之后添加 `out vec3 v_worldPos;`，`main()` 内 `v_normal = ...` 之后添加：

```glsl
    v_worldPos = (u_modelMatrix * vec4(in_position, 1.0)).xyz;
```

- [ ] **步骤 3：新增 UNLIT 与 EYE fragment shader 源码**

在 `FRAGMENT_SHADER_SOURCE` 定义（gl_renderer.cpp:167-185）之后添加：

```cpp
const char* GlRenderer::FRAGMENT_SHADER_UNLIT = R"(
#version 150 core
in vec2 v_texcoord;

uniform sampler2D u_texture;
uniform vec4 u_colorTint;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(u_texture, v_texcoord);
    fragColor = vec4(texColor.rgb * u_colorTint.rgb, texColor.a * u_colorTint.a);
}
)";

const char* GlRenderer::FRAGMENT_SHADER_EYE = R"(
#version 150 core
in vec2 v_texcoord;
in vec3 v_normal;
in vec3 v_worldPos;

uniform sampler2D u_texture;
uniform vec3 u_lightDir;
uniform vec3 u_cameraPos;
uniform float u_ambient;
uniform float u_phongBoost;
uniform vec4 u_colorTint;

out vec4 fragColor;

void main() {
    vec4 texColor = texture(u_texture, v_texcoord);
    vec3 N = normalize(v_normal);
    vec3 L = normalize(u_lightDir);
    float NdotL = max(dot(N, L), 0.0);
    float lighting = u_ambient + (1.0 - u_ambient) * NdotL;
    vec3 V = normalize(u_cameraPos - v_worldPos);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 32.0) * u_phongBoost;
    vec3 color = texColor.rgb * u_colorTint.rgb * lighting + vec3(spec);
    fragColor = vec4(color, texColor.a * u_colorTint.a);
}
)";
```

- [ ] **步骤 4：静态成员定义（gl_renderer.cpp:187-188）**

替换：

```cpp
uint32_t GlRenderer::s_program = 0;
bool GlRenderer::s_initialized = false;
```

为：

```cpp
uint32_t GlRenderer::s_programBase = 0;
uint32_t GlRenderer::s_programUnlit = 0;
uint32_t GlRenderer::s_programEye = 0;
bool GlRenderer::s_initialized = false;
float GlRenderer::s_cameraPos[3] = {0.0f, 0.0f, 0.0f};
float GlRenderer::s_phongBoost = 0.3f;
```

- [ ] **步骤 5：`initialize()` 编译 3 个 program（gl_renderer.cpp:241-259）**

将原有编译单 program 的代码块替换为：

```cpp
    // Compile and link shader programs
    uint32_t vs = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
    if (!vs) return false;

    auto linkWithVs = [vs](const char* fsSource, const char* name) {
        uint32_t fs = GlRenderer::compileShader(GL_FRAGMENT_SHADER, fsSource);
        if (!fs) return static_cast<uint32_t>(0);
        uint32_t program = GlRenderer::linkProgram(vs, fs);
        glDeleteShader(fs);
        if (!program) std::cerr << "[GL] Failed to link " << name << " program" << std::endl;
        return program;
    };

    s_programBase = linkWithVs(FRAGMENT_SHADER_SOURCE, "base");
    s_programUnlit = linkWithVs(FRAGMENT_SHADER_UNLIT, "unlit");
    s_programEye = linkWithVs(FRAGMENT_SHADER_EYE, "eye");
    glDeleteShader(vs);

    if (!s_programBase) return false;

    s_initialized = true;
    std::cout << "[GL] Renderer initialized, programs: base=" << s_programBase
              << " unlit=" << s_programUnlit << " eye=" << s_programEye << std::endl;
    return true;
```

- [ ] **步骤 6：`renderMesh` 按 mode 路由（gl_renderer.cpp:416-461）**

签名与开头改为：

```cpp
void GlRenderer::renderMesh(uint32_t vao, int indexCount, uint32_t textureId,
                             const float* modelMatrix, int packedLight,
                             const float* colorTint, RenderMode mode) {
    if (mode == RenderMode::SKIP) return;
    uint32_t program = s_programBase;
    switch (mode) {
        case RenderMode::UNLIT: program = s_programUnlit; break;
        case RenderMode::EYE:   program = s_programEye;   break;
        default: break;
    }
    if (!vao || indexCount <= 0 || !program) return;
    if (!glUseProgram || !glBindVertexArray || !glDrawElements) return;
```

将原 `s_program` 引用替换为 `program`（`glUseProgram(s_program)` → `glUseProgram(program)`，各 `glGetUniformLocation(s_program, ...)` → `glGetUniformLocation(program, ...)`）。

在 `mvpLoc` 设置之后插入 `u_modelMatrix` 与 mode 专属 uniforms：

```cpp
    int modelLoc = glGetUniformLocation(program, "u_modelMatrix");
    if (modelLoc >= 0) glUniformMatrix4fv(modelLoc, 1, GL_FALSE, modelMatrix);

    if (mode == RenderMode::EYE) {
        int camLoc = glGetUniformLocation(program, "u_cameraPos");
        if (camLoc >= 0) glUniform3fv(camLoc, 1, s_cameraPos);
        int pbLoc = glGetUniformLocation(program, "u_phongBoost");
        if (pbLoc >= 0) glUniform1f(pbLoc, s_phongBoost);
    }
```

- [ ] **步骤 7：新增 setter 并更新 `shutdown()`**

在 `renderMesh` 定义之后添加：

```cpp
void GlRenderer::setCameraPosition(float x, float y, float z) {
    s_cameraPos[0] = x; s_cameraPos[1] = y; s_cameraPos[2] = z;
}

void GlRenderer::setPhongBoost(float boost) {
    s_phongBoost = boost;
}
```

`shutdown()`（gl_renderer.cpp:508-517）中的 `if (s_program) {...}` 块替换为：

```cpp
    auto deleteProgram = [](uint32_t& prog) {
        if (!prog) return;
        typedef void (GL_API* GL_DELETEPROGRAM)(uint32_t);
        static GL_DELETEPROGRAM pDeleteProgram = nullptr;
        if (!pDeleteProgram) {
            pDeleteProgram = reinterpret_cast<GL_DELETEPROGRAM>(glPlatformLoadProc("glDeleteProgram"));
        }
        if (pDeleteProgram) pDeleteProgram(prog);
        prog = 0;
    };
    deleteProgram(s_programBase);
    deleteProgram(s_programUnlit);
    deleteProgram(s_programEye);
```

- [ ] **步骤 8：编译验证**

运行：`gradlew buildNativeDll`
预期：BUILD SUCCESSFUL

- [ ] **步骤 9：Commit**

```bash
git add src/main/native/include/gl_renderer.h src/main/native/src/gl_renderer.cpp
git commit -m "feat(native): multi-program GlRenderer with UNLIT and EYE shaders"
```

---

### 任务 5：C++ native_bridge 透传 + 相机位置 JNI

**文件：**
- 修改：`src/main/native/src/native_bridge.cpp:210-220, 201-208 附近`

- [ ] **步骤 1：`renderMeshList` 透传 mode 并跳过 SKIP（native_bridge.cpp:215-219）**

替换为：

```cpp
    for (const auto& mesh : meshes) {
        if (mesh.renderMode == RenderMode::SKIP) continue;
        if (!mesh.glVao || mesh.indexCount <= 0) continue;
        GlRenderer::renderMesh(mesh.glVao, mesh.indexCount, mesh.textureId,
                                matrix, packedLight, mesh.colorTint, mesh.renderMode);
    }
```

- [ ] **步骤 2：新增 `nativeSetCameraPosition` JNI 导出**

在 `Java_..._nativeGetMeshCount`（native_bridge.cpp:201-208）之后添加：

```cpp
JNIEXPORT void JNICALL
Java_transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge_nativeSetCameraPosition(
    JNIEnv* env, jclass, jfloat x, jfloat y, jfloat z)
{
    GlRenderer::setCameraPosition(static_cast<float>(x), static_cast<float>(y), static_cast<float>(z));
}
```

（`gl_renderer.h` 已由 native_bridge.cpp 引用，无需新增 include。）

- [ ] **步骤 3：编译验证**

运行：`gradlew buildNativeDll`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add src/main/native/src/native_bridge.cpp
git commit -m "feat(native): pass renderMode through renderMeshList and expose nativeSetCameraPosition"
```

---

### 任务 6：Java 数据链路 — MeshData / Builder / MeshTextureInfo 增加 shaderType

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelData.java:30-99, 101-237`
- 测试：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelDataTest.java`
- 创建：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/VmtShaderTypeTest.java`

- [ ] **步骤 1：编写失败的测试（SourceModelDataTest.java）**

在 `SourceModelDataTest` 类中添加：

```java
    @Test
    void meshShaderTypeRoundTrip() {
        SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
            .shaderType("UnlitGeneric")
            .build();
        assertEquals("UnlitGeneric", mesh.shaderType);
    }

    @Test
    void meshShaderTypeDefaultsNull() {
        SourceModelData.MeshData mesh = createTriangleMesh();
        assertNull(mesh.shaderType);
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew test --tests "transferstation.transferstation_whimsicalideas.client.model.SourceModelDataTest"`
预期：编译失败，`cannot find symbol: method shaderType(String)`

- [ ] **步骤 3：`SourceModelData.MeshData` 增加字段（SourceModelData.java:129-130 附近）**

在 `public final int detailBlendMode;` 之后添加：

```java
        public final String shaderType;
```

在 `MeshData(Builder builder)` 构造函数（161 行 `this.detailBlendMode = builder.detailBlendMode;` 之后）添加：

```java
            this.shaderType = builder.shaderType;
```

- [ ] **步骤 4：`Builder` 增加字段与方法（SourceModelData.java:201-202 附近）**

在 Builder 的 `private int detailBlendMode;` 之后添加：

```java
            private String shaderType;
```

在 Builder 的 `detailBlendMode(int)` 方法之后添加：

```java
            public Builder shaderType(String shaderType) { this.shaderType = shaderType; return this; }
```

- [ ] **步骤 5：`MeshTextureInfo` 增加可变字段（SourceModelData.java:62-65 附近）**

在 `public int detailBlendMode;` 之后添加：

```java
        public String shaderType;
```

（非 final，与 `colorTint`/`alpha` 风格一致；两个 `simple(...)` 重载不需要改动，默认 null。）

- [ ] **步骤 6：创建 VmtShaderTypeTest.java**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VmtShaderTypeTest {

    @Test
    void fromNameRoutesAllShaders() {
        assertEquals(VmtParser.ShaderType.VERTEX_LIT_GENERIC, VmtParser.ShaderType.fromName("VertexLitGeneric"));
        assertEquals(VmtParser.ShaderType.UNLIT_GENERIC, VmtParser.ShaderType.fromName("UnlitGeneric"));
        assertEquals(VmtParser.ShaderType.EYE_REFRACT, VmtParser.ShaderType.fromName("EyeRefract"));
        assertEquals(VmtParser.ShaderType.SPRITE, VmtParser.ShaderType.fromName("Sprite"));
        assertEquals(VmtParser.ShaderType.CABLE, VmtParser.ShaderType.fromName("Cable"));
        assertEquals(VmtParser.ShaderType.SKYBOX, VmtParser.ShaderType.fromName("SkyBox"));
        assertEquals(VmtParser.ShaderType.TOOL_TEXTURE, VmtParser.ShaderType.fromName("ToolTexture"));
        assertEquals(VmtParser.ShaderType.TOOL_TEXTURE, VmtParser.ShaderType.fromName("tools/toolsskybox"));
    }

    @Test
    void fromNameHandlesCaseAndNull() {
        assertEquals(VmtParser.ShaderType.UNLIT_GENERIC, VmtParser.ShaderType.fromName("unlitgeneric"));
        assertEquals(VmtParser.ShaderType.UNKNOWN, VmtParser.ShaderType.fromName("SomeCustomShader"));
        assertEquals(VmtParser.ShaderType.UNKNOWN, VmtParser.ShaderType.fromName(null));
    }
}
```

- [ ] **步骤 7：运行测试验证通过**

运行：`gradlew test --tests "transferstation.transferstation_whimsicalideas.client.model.*"`
预期：全部 PASS（新增 4 个测试）

- [ ] **步骤 8：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelData.java src/test/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelDataTest.java src/test/java/transferstation/transferstation_whimsicalideas/client/model/VmtShaderTypeTest.java
git commit -m "feat(model): add shaderType to MeshData/Builder/MeshTextureInfo with tests"
```

---

### 任务 7：Java MeshDecimator 透传 shaderType

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MeshDecimator.java:237-246, 275-292`
- 测试：`src/test/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelDataTest.java`

- [ ] **步骤 1：编写失败的测试（SourceModelDataTest.java）**

```java
    @Test
    void lodDecimationPreservesShaderType() {
        SourceModelData model = createSimpleModel();
        SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
            .vertices(createFloatArray(64 * 8))
            .indices(createIntArray(96))
            .shaderType("UnlitGeneric")
            .build();
        model.meshes.add(mesh);
        SourceModelData lod = model.getMeshesForLod(1);
        assertEquals("UnlitGeneric", lod.meshes.get(0).shaderType);
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`gradlew test --tests "transferstation.transferstation_whimsicalideas.client.model.SourceModelDataTest"`
预期：FAIL（`lod.meshes.get(0).shaderType` 为 null）

- [ ] **步骤 3：MeshDecimator 两处 builder 透传**

`MeshDecimator.java:244-245`（fallback 分支）：

```java
                .surfaceProp(original.surfaceProp).detailBlendMode(original.detailBlendMode)
```

改为：

```java
                .surfaceProp(original.surfaceProp).detailBlendMode(original.detailBlendMode)
                .shaderType(original.shaderType)
```

`MeshDecimator.java:290-291`（主分支）：

```java
            .alpha(original.alpha).surfaceProp(original.surfaceProp)
            .detailBlendMode(original.detailBlendMode)
```

改为：

```java
            .alpha(original.alpha).surfaceProp(original.surfaceProp)
            .detailBlendMode(original.detailBlendMode)
            .shaderType(original.shaderType)
```

- [ ] **步骤 4：运行测试验证通过**

运行：`gradlew test --tests "transferstation.transferstation_whimsicalideas.client.model.SourceModelDataTest"`
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/MeshDecimator.java src/test/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelDataTest.java
git commit -m "feat(model): propagate shaderType through MeshDecimator"
```

---

### 任务 8：Java ModelLoadManager — 填充 shaderType + 磁盘缓存

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java:32, 884-897, 1161-1231, 2290-2313, 3090-3096`

- [ ] **步骤 1：`buildFullMeshTextureInfo` 填充（ModelLoadManager.java:3090-3096）**

在 `return new SourceModelData.MeshTextureInfo(...)` 之前重构为：

```java
        SourceModelData.MeshTextureInfo info = new SourceModelData.MeshTextureInfo(loc, normalMap,
            ssbumpMap, envMapMask, parallaxMap, detailMap, selfIllumMask,
            mat.isTransparent(), mat.isAlphaTest(), mat.isNoCull(),
            mat.isSelfIllum(), mat.hasPhong(), mat.isHalfLambert(),
            mat.getPhongBoost(), mat.getPhongFresnelRanges(), phongExponentTex,
            vtfKey, colorTint, mat.getAlpha(),
            mat.getSurfaceProp(), mat.getDetailBlendMode());
        info.shaderType = mat.shader;
        return info;
```

- [ ] **步骤 2：MDL 路径 mesh 构建透传（ModelLoadManager.java:2294 之后）**

在 `detailBlendMode = texInfo.detailBlendMode;` 之后添加：

```java
                        String shaderType = texInfo.shaderType;
```

在 builder（2300-2313）的 `.surfaceProp(surfaceProp).detailBlendMode(detailBlendMode)` 之后添加：

```java
                        .shaderType(shaderType)
```

- [ ] **步骤 3：SMD 路径透传（ModelLoadManager.java:1771-1787 区域）**

在 `noCull = mat.isNoCull();` 之后添加：

```java
                            String shaderType = mat.shader;
```

在 builder（1823-1829）末尾 `.vtfKey(vtfKey)` 之后添加：

```java
                .shaderType(shaderType)
```

注意：`shaderType` 变量声明在 `if (mat != null)` 块内（1771-1787），builder 在其后使用——若 `mat == null` 分支则变量不可达。改为在 `if (texture == null) { texture = ... }` 之前声明：

```java
            String shaderType = null;
```

并将 `noCull = mat.isNoCull();` 之后改为 `shaderType = mat.shader;`。

- [ ] **步骤 4：磁盘缓存写入（ModelLoadManager.java:1231 之后）**

在 mesh 循环末尾 `writeResourceLocation(dos, mesh.phongExponentTexture);` 之后添加：

```java
                    if (mesh.shaderType != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.shaderType);
                    } else {
                        dos.writeBoolean(false);
                    }
```

- [ ] **步骤 5：磁盘缓存读取（ModelLoadManager.java:882 之后）**

在 `ResourceLocation phongExponentTexture = readResourceLocation(dis);` 之后添加：

```java
                    String shaderType = dis.readBoolean() ? dis.readUTF() : null;
```

在 builder（884-897）的 `.surfaceProp(surfaceProp).detailBlendMode(detailBlendMode)` 之后添加：

```java
                        .shaderType(shaderType)
```

- [ ] **步骤 6：缓存格式版本号提升（ModelLoadManager.java:32）**

```java
private static final int CACHE_FORMAT_VERSION = 26;
```

（旧缓存版本 25 将在 `loadFromDiskCache` 的版本检查处被丢弃并重新解析，无需其他兼容代码。）

- [ ] **步骤 7：编译验证**

运行：`gradlew compileJava`（或 IDE 构建）
预期：编译通过，无新增警告

- [ ] **步骤 8：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java
git commit -m "feat(model): populate shaderType from VMT and serialize in disk cache (v26)"
```

---

### 任务 9：Java 渲染路由 — UNLIT/EYE 全亮、SKYBOX/TOOL 跳过、SPRITE billboard

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/JavaModelRenderer.java:148-149, 204-214, 280-281, 297-299, 327-328`

- [ ] **步骤 1：新增 shader 路由辅助方法**

在 `selectRenderType`（181 行）之前添加：

```java
    private static boolean isSkippedShader(SourceModelData.MeshData mesh) {
        if (mesh.shaderType == null) return false;
        VmtParser.ShaderType st = VmtParser.ShaderType.fromName(mesh.shaderType);
        return st == VmtParser.ShaderType.SKYBOX || st == VmtParser.ShaderType.TOOL_TEXTURE;
    }

    private static boolean isSelfIllumShader(SourceModelData.MeshData mesh) {
        if (mesh.shaderType == null) return false;
        VmtParser.ShaderType st = VmtParser.ShaderType.fromName(mesh.shaderType);
        return st == VmtParser.ShaderType.UNLIT_GENERIC || st == VmtParser.ShaderType.EYE_REFRACT;
    }

    private static boolean needsSpriteBillboard(SourceModelData data) {
        for (SourceModelData.MeshData mesh : data.meshes) {
            if (mesh.shaderType != null
                    && VmtParser.ShaderType.fromName(mesh.shaderType) == VmtParser.ShaderType.SPRITE) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **步骤 2：`renderMeshWithEmissiveSupport` 应用路由（204-214 行）**

在 `if (mesh.indices.length < 3) return;` 之后添加：

```java
        if (isSkippedShader(mesh)) return;
```

将：

```java
        RenderType renderType = selectRenderType(texture, mesh.translucent, mesh.alphaTest, mesh.selfIllum, mesh.noCull);
        int light = mesh.selfIllum ? fullbrightLight : packedLight;
```

替换为：

```java
        boolean selfIllum = mesh.selfIllum || isSelfIllumShader(mesh);
        boolean alphaTest = mesh.alphaTest
                || (mesh.shaderType != null
                    && VmtParser.ShaderType.fromName(mesh.shaderType) == VmtParser.ShaderType.EYE_REFRACT);
        RenderType renderType = selectRenderType(texture, mesh.translucent, alphaTest, selfIllum, mesh.noCull);
        int light = selfIllum ? fullbrightLight : packedLight;
```

- [ ] **步骤 3：`renderMeshSkinned` 应用同样的路由（321-328 行）**

在 `if (mesh.indices.length < 3) return;` 之后添加：

```java
        if (isSkippedShader(mesh)) return;
```

将：

```java
        RenderType renderType = selectRenderType(texture, mesh.translucent, mesh.alphaTest, mesh.selfIllum, mesh.noCull);
        int light = mesh.selfIllum ? fullbrightLight : packedLight;
```

替换为与步骤 2 相同的 4 行路由代码。

- [ ] **步骤 4：新增 billboard 方法（在 `selectRenderType` 之前）**

```java
    private static void applySpriteBillboard(PoseStack poseStack) {
        net.minecraft.client.Camera camera =
            net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        poseStack.mulPose(camera.rotation().conjugate());
        // Source sprite 前向 +X → MC 前向 -Z：绕 Y 轴补偿 90°
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation((float) Math.PI / 2));
    }
```

- [ ] **步骤 5：`renderWithData` 替换朝向补偿（148-149 行）**

将：

```java
        // 整体朝向补偿：把 Source 模型前向对齐到 Minecraft 实体前向（-Z，看向玩家）
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));
```

替换为：

```java
        // 整体朝向补偿：把 Source 模型前向对齐到 Minecraft 实体前向（-Z，看向玩家）
        // Sprite shader 模型改为面向相机（billboard）
        if (needsSpriteBillboard(data)) {
            applySpriteBillboard(poseStack);
        } else {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));
        }
```

- [ ] **步骤 6：`renderWithSkinning` 同样的替换（280-281 行）**

将：

```java
        // 整体朝向补偿：与 renderWithData 保持一致（见上方说明）
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));
```

替换为与步骤 5 相同的 if/else 结构。

- [ ] **步骤 7：编译验证**

运行：`gradlew compileJava`
预期：编译通过，无新增警告

- [ ] **步骤 8：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/JavaModelRenderer.java
git commit -m "feat(render): route UNLIT/EYE fullbright, skip SKYBOX/TOOL meshes, SPRITE billboard"
```

---

### 任务 10：Java 相机位置透传 + 集成验证

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/GmodNativeBridge.java:187-188 附近`
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MdlModelRenderer.java:426-461`

- [ ] **步骤 1：GmodNativeBridge 声明 JNI 方法**

在 `nativeRenderModelLOD` 声明之后添加：

```java
    static native void nativeSetCameraPosition(float x, float y, float z);
```

- [ ] **步骤 2：MdlModelRenderer.renderNative 每帧传相机位置（448 行 `float[] matArray = new float[16];` 之前）**

添加：

```java
        try {
            net.minecraft.client.Camera cam =
                net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
            GmodNativeBridge.nativeSetCameraPosition(
                (float) cam.getPosition().x, (float) cam.getPosition().y, (float) cam.getPosition().z);
        } catch (Exception ignored) {}
```

- [ ] **步骤 3：全量构建验证**

运行：`gradlew compileJava buildNativeDll test`
预期：全部 SUCCESSFUL，所有 JUnit 测试全绿

- [ ] **步骤 4：IDE 复查**

用 IDE 检查以下文件问题为零新增：
- `src/main/java/transferstation/transferstation_whimsicalideas/client/model/JavaModelRenderer.java`
- `src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java`
- `src/main/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelData.java`

- [ ] **步骤 5：目测验证（需人工执行，环境启动）**

运行客户端，加载 valve_content NPC 模型，检查：
1. UnlitGeneric / `$selfillum` 材质（如 metrocop 护目镜）全亮度渲染
2. Skybox / ToolTexture 材质不再显示为黑盒（被跳过）
3. EyeRefract 材质带高光、整体偏亮
4. Sprite 材质（如有）面向相机
5. 常规 VertexLitGeneric 材质行为与改动前一致

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/GmodNativeBridge.java src/main/java/transferstation/transferstation_whimsicalideas/client/model/MdlModelRenderer.java
git commit -m "feat(render): pass camera position to native renderer for EYE specular"
```

---

## 自检记录

- **规格覆盖度：** 组件 1（VmtInfo 扩展 + RenderMode）→ 任务 2；组件 2（MeshData.renderMode + SKIP 不 push）→ 任务 1、3；组件 3（GlRenderer 多 program）→ 任务 4；组件 4（native_bridge 透传）→ 任务 5；组件 5（Java shaderType 全链路 + selectRenderType 路由 + billboard）→ 任务 6-10。验证节 1-4 → 任务 10 步骤 3-5。
- **占位符扫描：** 无 TODO/待定；所有代码步骤含完整代码。
- **类型一致性：** `RenderMode` 枚举贯穿任务 1-5 签名一致；`shaderType`（String）贯穿任务 6-10；`inferRenderMode` 在任务 2 定义、任务 3 使用，签名一致；`nativeSetCameraPosition` JNI 名与 `GmodNativeBridge` 声明对应（`transferstation_transferstation_1whimsicalideas_client_model_GmodNativeBridge`）。
- **设计偏离说明：** 磁盘缓存兼容采用版本号 25→26（既有惯例）而非"剩余字节数判断"——旧缓存被安全丢弃并重建，行为等价且更稳健。
