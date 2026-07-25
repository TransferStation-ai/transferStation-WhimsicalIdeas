# BBModel 加载器设计规格

## 概述

为项目添加 Blockbench (.bbmodel) 模型的加载支持，使模组能像加载 Source Engine 模型一样加载 Blockbench 模型作为角色/生物模型。

支持两种 Blockbench 模型格式：
- **free** — 通用网格模型（mesh 元素）
- **java_block** — Java 版 box 模型（cube 元素）

支持：静态几何体、骨骼层级、关键帧动画、嵌入纹理。

## 架构

在 `ModelLoadManager.loadFromDirectory()` 中添加一条新分支 —— 检测到 `.bbmodel` 文件时走 BBModel 加载路径，与现有 MDL 路径和 SMD 路径并列。

```
loadFromDirectory()
  ├── 检测 .bbmodel 文件 → BBModelParser.parse()
  ├── 检测 MDL trio → 现有 MDL 路径
  └── 检测 SMD 文件 → 现有 SMD 路径
```

## 新增文件

### `client/model/BBModelParser.java`

核心解析器，将 `.bbmodel` JSON 转换为 `SourceModelData`。

**方法签名：**
```java
public static SourceModelData parse(Path bbmodelPath, Path packageDir) throws IOException
```

**内部步骤：**

1. **`parseMeta(json)`** — 读取 `format_version`、`model_format`，决定后续解析策略
2. **`parseTextures(json, packageDir)`** — 解析 `textures[]` 数组：
   - 解码 base64 PNG → `BufferedImage`
   - 通过 `DynamicTexture` 注册到 Minecraft `TextureManager`
   - 生成 `ResourceLocation`（命名空间 `transferstation_whimsicalideas`，路径 `bbmodel/<hash>`）
   - 返回 `Map<Integer, ResourceLocation>`（texture ID → ResourceLocation）
3. **`parseElements(json, textures)`** — 解析 `elements[]`：
   - `type == "mesh"`（free 格式）：直接提取 vertices/faces/uvs/normals → `MeshData.Builder`
   - `type == "cube"`（java_block 格式）：展开 6 个面 → 12 个三角形 → `MeshData.Builder`
4. **`parseOutliner(json)`** — 解析 `outliner[]` 树结构 → `List<BoneInfo>`：
   - 递归遍历树节点
   - 每个节点提取 `name`、`origin`（作为 bone pos）
   - 构建 parent 索引
5. **`parseAnimations(json, outliner)`** — 解析 `animations[]`：
   - 遍历 `animations[].animators`
   - 对每个 animator，提取 `position` 和 `rotation` 关键帧
   - 构建 `AnimationData` → 通过 `AnimationProcessor.registerAnimation()` 注册
6. **`computeBounds(result)`** — 计算包围盒和自动缩放

### Cube → Mesh 展开算法（`java_block` 格式）

每个 cube 元素有 6 个面（north/south/east/west/up/down），每个面有 UV 坐标。

```
对每个面：
  1. 根据 face 方向确定 4 个顶点位置（from/to 的适当组合）
  2. 计算面法线
  3. 从 face.uv 映射 4 个顶点的 UV 坐标 [u1,v1,u2,v2]
  4. 拆分为 2 个三角形 → 6 个顶点
  5. 如果 cube 有 rotation（pivot + 角度），先对顶点做变换
```

**UV 映射规则：**
- `face.uv = [u1, v1, u2, v2]` 对应纹理上的矩形范围
- Blockbench 使用 0-16 范围的 UV（与 Minecraft 纹理像素对应）
- 转换为 0.0-1.0 范围：`u/texWidth, v/texHeight`

### 坐标与缩放

Blockbench 使用 Minecraft 坐标系（y-up, x-right, z-south），与 `SourceModelData` 兼容，无需坐标转换。

自动缩放逻辑复用现有 `computeBounds()`：基于包围盒最大维度计算 `modelScale = 1.8f / maxDim`。

## 修改文件

| 文件 | 改动 |
|------|------|
| `GmodModelConfig.java:110` | `hasAnyModelFile()` 添加 `.bbmodel` |
| `ModelSyncManager.java:213` | `hasAnyModelFile()` 添加 `.bbmodel` |
| `MdlModelRenderer.java:187` | `hasAnyModelFile()` 添加 `.bbmodel` |
| `ModelLoadManager.java:1124` | `findAnyModelFile()` 添加 `.bbmodel` |
| `ModelLoadManager.java:1475` | 文件类型检测添加 `.bbmodel` |
| `ModelLoadManager.java:1450` | `loadFromDirectory()` 开始处添加 BBModel 检测分支 |

## 加载流程示例

```
config/models/my_bbmodel/
  ├── model.bbmodel          ← JSON 格式，包含嵌入纹理
  └── (可选) CustomAnim/     ← 动画保存目录
```

当用户选择 `my_bbmodel` 作为当前模型时：
1. `ModelLoadManager.loadModel()` 调用 `loadFromDirectory()`
2. 检测到 `my_bbmodel` 目录下有 `.bbmodel` 文件
3. 走 BBModel 加载路径
4. 返回 `SourceModelData` → 设置到 `JavaModelRenderer`
5. 模型在游戏内渲染，支持骨骼编辑、动画播放

## 边界情况

- **无嵌入纹理的 .bbmodel**：fallback 到白色纹理
- **空 elements 数组**：返回空 SourceModelData，不加载
- **格式版本不兼容**：记录警告，尝试兼容解析
- **无效 base64 纹理**：跳过该纹理，日志警告
- **free 格式包含 cube 元素**（或反之）：按元素实际 type 分别处理
- **outliner 为扁平列表**（无嵌套）：每个 outliner 节点作为根骨骼
