# Source Engine 模型/材质解析完整度完善设计

## 概述

基于 Source SDK 2013 开源的 `studio.h` 和 VMT/VTF 规范，系统性地完善本模组对 Source Engine 模型格式 (MDL/VVD/VTX) 和材质格式 (VMT/VTF) 的支持。

**核心约束：** 模型在 Minecraft 中的渲染效果必须与 Garry's Mod 中原版外观保持一致，包括坐标变换、色彩精度、材质参数映射和光照响应。

## 阶段 A：MDL 结构体与 Source SDK 2013 精确对齐

### 目标
对照 Valve Software/source-sdk-2013 的 `src/public/studio.h` 逐结构审计 Java 解析代码，修复所有版本相关的字段偏移和大小不匹配。

### 审计范围

#### A.1 mstudioseqdesc_t 字段偏移修正（高优先级）

**问题：** 当前 `SeqDesc` 解析缺少 `szactivitynameindex` 字段，导致 v48+ 下后续所有 `SeqDesc` 字段偏移错误 4 字节。

**修复：**
- 在 `SeqDesc` 类中 `label` 之后添加 `activityNameIndex` 占位
- 按版本决定是否读取该字段：v48 格式早于此字段（大小为 200），v49+ 包含（220/240）
- 当前 `activity` 和 `actweight` 的解析位置需重新对齐

**影响文件：**
- `MdlDataTypes.java` — `SeqDesc` 类
- `MdlParser.java` — `parseSequences()` 方法

#### A.2 mstudiomesh_t 64-bit 兼容性（中优先级）

**问题：** C++ 中 v49 mesh 包含 `mstudio_meshvertexdata_t vertexdata`，该结构体在 64/32 位平台大小不同（含裸指针）。

**修复：**
- 验证 `Mesh` 类中的 `extra[9]` 数组是否精确覆盖平台差异
- 对 v53 (GMod) 的 mesh header 大小做针对性验证

#### A.3 骨骼 flex 驱动数据（低优先级）

**新增解析：** `mstudioboneflexdriver_t` — 允许骨骼位置驱动 flex controller，用于面部表情等。Source SDK 2013 中此数据位于 HDR2 之后。

#### A.4 验证方法
- 对 5 个以上不同版本（v44, v47, v48, v49, v53）的已知正确 MDL 文件做二进制对比测试
- 解析前后的 struct 大小、关键字段值与 GMod 的 studiomdl 输出对比
- 确保修复后模型渲染视觉无退化

## 阶段 B：VMT 材质管线增强

### 目标
完善 VMT 解析器，使其完整支持 Source Engine 的材质系统参数和 Shader 类型，并正确映射到 Minecraft 渲染管线。

### B.1 Shader 类型路由
新增 Shader 类型枚举和处理逻辑：

| Shader 类型 | 检测方式 | 渲染行为 |
|---|---|---|
| `VertexLitGeneric` | shader 名字符串匹配 | 标准光照，当前默认路径 |
| `UnlitGeneric` | `unlitgeneric` | 无光照，全亮度渲染 |
| `EyeRefract` | `eyerefract` | 特殊眼球渲染（使用 eyeball 数据） |
| `Sprite` | `sprite` | 始终面向摄像机 |
| `Cable` | `cable` | 特殊线缆渲染 |
| `SkyBox` / `ToolTexture` | 按名字 | 禁用或特殊处理 |

### B.2 材质参数补全
基于 Source SDK 材质系统，添加以下缺失参数解析：

```
$color2, $color — 支持 {r g b} 和 {r g b a} 和 vertex 格式
$basetexturetransform — 完整的 center/scale/rotate/translate 矩阵
$detailblendmode — MUL/ADD/MASK/OVER 四种模式
$detailscale — 支持宽高独立缩放
$ssbump — 自阴影凹凸贴图
$phongfresnelranges — 完整三值向量
$envmapmask — 环境贴图遮罩
$selfillummask — 自发光遮罩
$parallaxmap — 视差映射
%includematerial — 材质继承
```

### B.3 材质继承链
- 实现 `VmtIncludeResolver` 的完整逻辑（已有骨架）
- 最大递归深度 16，循环检测
- 父材质缓存（避免重复文件 I/O）

### B.4 与 GMod 渲染一致性的关键映射

| Source 参数 | Minecraft 映射 | 说明 |
|---|---|---|
| `$translucent 1` | `RenderType.translucent()` | 透明渲染 |
| `$alphatest 1` | `RenderType.cutout()` | 裁剪 |
| `$nocull 1` | `RenderType` 禁用背面剔除 | 双面 |
| `$envmap` + `$envmapmask` | 模拟环境反射 | 使用 Minecraft 天空盒或立方体贴图 |
| `$phong` + `$phongboost` | 高光增强 | 调整光照公式 |
| `$selfillum` + `$selfillummask` | 发光纹理 | 叠加发光层 |
| `$bumpmap` / `$normalmap` | 法线贴图 | OpenGL 法线贴图格式 |
| `$ssbump` | 自阴影 | 法线贴图中提取 |
| `$detail` + `$detailblendmode` | 细节纹理 | 按混合模式叠加 |

### B.5 验证方法
- 对 GMod 常用 NPC 模型（Combine Soldier, Metrocop, Zombie 等）的 VMT 进行解析测试
- 对比 GMod 截图，验证材质颜色、透明度、发光效果的一致性

## 阶段 C：VTF 纹理格式补全

### 目标
补充缺失的 VTF 格式支持，确保颜色精度与 GMod 一致。

### C.1 高优先级：Cubemap 支持
Source Engine 广泛使用 VTF cubemap 格式用于 `$envmap`。

**实现：**
- 检测 VTF header 中的 `TEXTUREFLAGS_ENVMAP` 标志
- Cubemap 布局：6 面按 +X, -X, +Y, -Y, +Z, -Z 顺序存储，每个面有完整 mip 链
- 解码为 6 个 `BufferedImage` 或等矩形投影图
- 在 Minecraft 中映射为环境反射

### C.2 高优先级：ATI1N/ATI2N 格式
法线贴图压缩格式，GMod 模型中广泛使用。
- `ATI1N` (FORMAT_ATI1N = 17)：单通道 BC4 格式，用于灰度/高度数据
- `ATI2N` (FORMAT_ATI2N = 18)：双通道 BC5 格式，用于法线贴图（R=法线X, G=法线Y）

### C.3 中优先级：格式解码器完善
- BC6H 补充完整 14 个 mode 支持（当前仅 mode 0-3）
- BC7 使用完整 64-entry partition table（当前使用子集）
- 改进半精度浮点数 (float16) → float32 转换精度

### C.4 中优先级：颜色精度
- 所有 DXT/BC 解码器使用与 GMod 相同的颜色插值算法
- 对比 GMod 的 VTF 渲染输出，微调解码器参数
- 添加 Gamma 校正选项（如果 GMod 与 Minecraft 的 Gamma 处理不同）

### C.5 低优先级：多帧 VTF 动画
- 支持交替帧的时间控制（Source 的 `$frame` 和 `$framerate` 参数）
- 在 UI 和物品渲染中实现帧动画

### C.6 验证方法
- 使用 GMod 提取的 VTF 样本文件，解码为 PNG 后与 GMod 内渲染截图对比
- 重点验证法线贴图、环境贴图和 DXT 压缩纹理的颜色准确性

## 实施顺序

1. **阶段 A**（MDL 结构对齐）→ 基础正确性
2. **阶段 B**（VMT 材质管线）→ 渲染效果提升
3. **阶段 C**（VTF 格式补全）→ 纹理质量提升

每个阶段独立可验证，互不阻塞。

## 参考来源

- [ValveSoftware/source-sdk-2013 - studio.h](https://github.com/ValveSoftware/source-sdk-2013/blob/master/src/public/studio.h)
- [Valve Developer Community - MDL Format](https://developer.valvesoftware.com/wiki/MDL)
- [Valve Developer Community - VMT](https://developer.valvesoftware.com/wiki/VMT)
- [Valve Developer Community - VTF](https://developer.valvesoftware.com/wiki/VTF)
- [Forge 1.20.1 文档](https://docs.minecraftforge.net/en/1.20.x/)
