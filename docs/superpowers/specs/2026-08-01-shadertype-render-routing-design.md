# ShaderType 渲染路由接入设计

## 背景

模型相关缺陷的解析层（MDL 结构对齐 / VMT 参数解析 / VTF 格式补全）已全部完成并提交在 master 上。但**渲染消费层未接入**：

1. Java `VmtParser.ShaderType` 枚举解析后无人消费——`MaterialConfig.fromVmt` 只存原始 shader 名字符串，`JavaModelRenderer.selectRenderType` 只有 translucent/alphaTest/selfIllum/noCull 四个布尔路由
2. C++ 侧 `model_loader.cpp` 的 `VmtInfo` 缺少 shader 名、`$selfillum`、`$envmap` 解析
3. `GlRenderer`（gl_renderer.cpp）是单 program 渲染器，无 UnlitGeneric/Sprite/EyeRefract/Skybox/ToolTexture 特殊处理
4. `ShaderGenerator`（shader_generator.cpp）是**死代码**（无调用者），不纳入本次范围

## 目标

在两条渲染路径接入 ShaderType 路由，使以下 shader 类型获得正确渲染行为：

| ShaderType | 原生 GlRenderer | Java 辅助路径 |
|---|---|---|
| VertexLitGeneric | BASE（现有） | 现有行为 |
| UnlitGeneric | UNLIT program：`texColor * tint` 直出全亮度 | selfIllum + fullbright |
| EyeRefract | EYE program：base + Blinn-Phong 高光 | cutout + fullbright |
| Sprite | 降级 BASE（原生按整模型单矩阵渲染，per-mesh billboard 不现实） | billboard 矩阵替换（Java 有相机） |
| Cable | 降级 BASE | 现有行为 |
| Skybox / ToolTexture | SKIP（不建 vao、不渲染） | SKIP |

## 架构

两条路径各自独立解析 VMT（C++ 侧 `parseVmtMaterial` 自行解析，Java 侧 `VmtParser` 自行解析），互不依赖。

### 组件 1：C++ VmtInfo 扩展（model_loader.cpp）

`VmtInfo` 结构（model_loader.cpp:36-49）新增：

```cpp
std::string shaderName;   // VMT 第一行 shader 名
bool selfIllum = false;   // $selfillum
bool envmap = false;      // $envmap 键存在
```

- `parseVmtMaterial` 解析第一行非注释内容为 shaderName
- 新增 `$selfillum`、`$envmap` 键检测
- 新增 `RenderMode` 枚举（放 **studio_types.h**，与 `MeshData` 同文件，避免 model_loader.h 循环依赖）：

```cpp
enum class RenderMode { BASE, UNLIT, EYE, SKIP };
```

- 推断函数：shader 名小写含 `unlitgeneric` 或 `$selfillum` → UNLIT；含 `eyerefract` → EYE；含 `skybox`/`tooltexture`/`tools/tool` → SKIP；其余 → BASE

### 组件 2：MeshData 增加 renderMode（studio_types.h:243-256）

`MeshData` 新增 `RenderMode renderMode = RenderMode::BASE;` 字段。

`model_loader.cpp` 的 VMT→mesh 复制处（约 772-788 行）同步复制 renderMode。`buildMeshes` 中 SKIP 的 mesh 不 push（省 vao/内存）。

### 组件 3：GlRenderer 多 program（gl_renderer.cpp）

- `s_program` 拆为 3 个静态 program：`s_programBase` / `s_programUnlit` / `s_programEye`
- 共享 VERTEX_SHADER_SOURCE（不变），各自独立 fragment shader：
  - UNLIT：`fragColor = vec4(texColor.rgb * u_colorTint.rgb, texColor.a * u_colorTint.a);`
  - EYE：base 光照 + Blinn-Phong 高光（`u_lightDir`、`u_cameraPos` uniform，`u_phongBoost` 控制强度）
- `renderMesh` 增加 `RenderMode mode` 参数：SKIP 直接 return；按 mode 选 program 并设置对应 uniforms
- `shutdown()` 释放 3 个 program

### 组件 4：native_bridge 透传（native_bridge.cpp:210-219）

`renderMeshList` 调用 `GlRenderer::renderMesh` 时传入 `mesh.renderMode`。

### 组件 5：Java 辅助路径

- `SourceModelData.MeshData` + Builder 新增 `shaderType` 字段（String，存 shader 名）
- `ModelLoadManager` 构建 mesh 处（约 2333-2386 行）填充：`ShaderType.fromName(mat.shader)` 的枚举名
- **磁盘缓存序列化兼容**（ModelLoadManager 约 880-927 / 1214-1280 的 dis/dos）：新字段追加在流末尾，读取时用剩余字节数判断旧缓存（旧缓存无此字段 → 默认 BASE 语义）
- `MeshDecimator`（约 241/280 行）的 mesh 拷贝处透传 shaderType
- `JavaModelRenderer.selectRenderType` 增加路由：
  - UNLIT → 走 selfIllum 分支（fullbright 光照 + cutout）
  - SKYBOX/TOOL_TEXTURE → 跳过该 mesh
- `JavaModelRenderer` 渲染时 SPRITE 做 billboard：将 pose 矩阵的旋转部分替换为面向相机的旋转

### 不做的

- 不碰死代码 `ShaderGenerator`（shader_generator.cpp）
- 不做 `$envmap` 反射渲染（cubemap 解析已就绪，反射渲染单独排期）
- 不改物理、不改 MDL/VTF 解析层
- 不实现真实 EyeRefract 折射（MC 前向渲染器无法低成本实现，用高光近似）

## 验证

1. `gradlew compileJava` 编译通过
2. `gradlew buildNativeDll`（需 CMake + MinGW，机器有 `C:\msys64\mingw64`）C++ 编译通过
3. 现有 JUnit 测试全绿（`gradlew test`）
4. 运行环境加载 valve_content NPC 模型，目测：
   - UnlitGeneric/`$selfillum` 材质全亮度（如 metrocop 护目镜）
   - Skybox/ToolTexture 材质不再显示为黑盒
