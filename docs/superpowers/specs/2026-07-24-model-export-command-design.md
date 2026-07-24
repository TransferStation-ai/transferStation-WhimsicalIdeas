# 模型导出指令设计文档

## 概述

在 Minecraft Forge 模组 "TransferStation WhimsicalIdeas" 中注册一个新指令 `/exportmodel`，
将 Source Engine 模型包（MDL/SMD）导出为 Blockbench 兼容的模型格式。

## 指令规范

```
/exportmodel <package> [format] [output]
```

| 参数 | 类型 | 必须 | 说明 |
|------|------|------|------|
| `package` | String | 是 | 模型包名称，带 TAB 自动补全 |
| `format` | String | 否 | `obj` / `bbmodel` / `all`，默认 `all` |
| `output` | String | 否 | 输出目录路径，默认 `<packageDir>/export/` |

权限：op 等级 2（与现有 `/npc` 指令一致）

## 架构

```
┌─────────────────────┐     ┌──────────────────────────────┐
│ ModelExportCommand   │────→│ ModelExporter                │
│ (common 包)         │     │ (独立加载+导出引擎)          │
│                     │     │                              │
│ RegisterCommands    │     │ SmdParser / MdlParser        │
│ scanModelPackages() │     │ + VvdParser / VtxParser     │
│ → resolve Path      │     │ + VtfParser → BufferedImage │
└─────────────────────┘     └──────┬───────────────────────┘
                                   │
                          ┌────────┼────────┐
                          ▼        ▼        ▼
                   ObjWriter  BBModelWriter  TextureExporter
                   .obj+.mtl  .bbmodel       .png
```

## 组件详述

### ModelExportCommand

- 包路径：`transferstation.transferstation_whimsicalideas.command.ModelExportCommand`
- 通过 `@SubscribeEvent(RegisterCommandsEvent)` 注册
- 参数补全：扫描 `modelsDir` 下所有子目录
- 执行流程：
  1. 解析参数
  2. 验证模型包目录存在（`<modelsDir>/<package>/`）
  3. 创建输出目录
  4. 调用 `ModelExporter.export()`
  5. 发送成功/失败消息给玩家

### ModelExporter

- 独立于 `ModelLoadManager`，直接使用底层解析器
- 支持的模型文件检测顺序：
  1. `.mdl` + `.vvd` + `.dx90.vtx`（Source Engine 标准三件套）
  2. `.smd`（兼容模式）
- 纹理目录：在包目录下查找 `materials/`，或依据 `$cdmaterials` 路径
- 确保解析器异常被捕获并反馈给玩家

### ObjWriter

输出文件：`<output>/<modelName>.obj` 和 `<output>/<modelName>.mtl`

OBJ 规范：
- 顶点：`v x y z`
- 法线：`vn nx ny nz`
- UV：`vt u v`
- 面：`f v1/vt1/vn1 v2/vt2/vn2 v3/vt3/vn3`
- 分组：按材质分 `g <materialName>`，每组合并对应三角形
- 材质引用：`usemtl <materialName>`
- 材质库：`mtllib <modelName>.mtl`

MTL 规范：
- `newmtl <materialName>`
- `map_Kd <textureName>.png`
- `d <alpha>`（透明材质）
- 颜色从 VMT 解析的 colorTint 映射到 `Kd`

### BBModelWriter

输出文件：`<output>/<modelName>.bbmodel`

Blockbench 格式版本 4.10，`model_format: "free"`。

结构：
- `meta`：格式版本、名称
- `textures`：纹理数组，使用 base64 data URI 嵌入（单文件自包含）
- `elements`：每个 mesh 作为一个 `type: "mesh"` 的 element
  - `vertices`：顶点坐标数组
  - `faces`：三角形索引
  - `uvs`：UV 坐标
  - `normals`：法线
  - `faces_materials`：每个面使用的材质索引
- `outliner`：骨骼层级（根骨骼 → 子骨骼的树形结构）
- `bone_groups`：骨骼变换（位置、旋转、父骨骼索引）

### TextureExporter

- 扫描材质目录下所有 `.vtf` 文件
- 调用 `VtfParser.parse()` 得到 `BufferedImage`
- `ImageIO.write(image, "png", pngFile)` 写出
- 文件名：VTF 的 `.vtf` 后缀替换为 `.png`
- 支持 Alpha 通道（RGBA）

## 数据流

```
1. 玩家输入 /exportmodel my_pack bbmodel
2. ModelExportCommand 解析参数
3. 查找 modelsDir/my_pack/ 是否存在
4. 创建 modelsDir/my_pack/export/ 目录
5. ModelExporter.export():
   a. 扫描目录，找到 .mdl 或 .smd 文件
   b. 使用对应解析器解析几何数据 → SourceModelData
   c. 解析纹理（VTF→BufferedImage）
   d. 遍历 SourceModelData.meshes[]
   e. 调用 BBModelWriter.write(data, textures, output)
   f. 调用 TextureExporter.writeTextures(textures, output)
6. 发送 "导出成功：<path>" 给玩家
```

## 坐标转换

Source Engine 坐标到标准 OBJ/Blockbench 坐标：

| 轴 | Source Engine | Minecraft (现有) | OBJ 标准 |
|----|-------------|------------------|----------|
| X  | 前 (Forward) | 右 (Right) | 右 (Right) |
| Y  | 左 (Left) | 上 (Up) | 上 (Up) |
| Z  | 上 (Up) | 南 (South) | 后 (Backward) |

导出时保留 Minecraft 坐标系（X=右, Y=上, Z=南），Blockbench 导入 OBJ 时
自动适配。如需标准坐标系可加 `--standard-coords` 参数。

## 国际化

新增翻译键（`zh_cn.json` / `en_us.json`）：

```
"command.transferstation_whimsicalideas.export.usage": "/exportmodel <包名> [格式] [输出路径]"
"command.transferstation_whimsicalideas.export.started": "§7正在导出模型 '%s' 为 %s 格式..."
"command.transferstation_whimsicalideas.export.success": "§a导出成功：%s"
"command.transferstation_whimsicalideas.export.failed": "§c导出失败：%s"
"command.transferstation_whimsicalideas.export.not_found": "§c未找到模型包：%s"
"command.transferstation_whimsicalideas.export.no_models": "§c模型包 %s 中没有可导出的模型文件"
```

## 边界情况

1. **模型包目录不存在** → 返回 `export.not_found` 错误
2. **目录中无 .mdl/.smd 文件** → 返回 `export.no_models` 错误
3. **解析失败** → 捕获异常，返回 `export.failed` + 具体错误信息
4. **VTF 纹理无法解析** → 跳过该纹理，继续导出几何，记录警告
5. **写入权限不足** → 返回 `export.failed` 包含 IOException 信息
6. **大模型（数万顶点）** → 同步执行，服务端线程有足够时间
