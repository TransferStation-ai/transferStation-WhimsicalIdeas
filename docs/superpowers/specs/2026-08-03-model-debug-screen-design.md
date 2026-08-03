# 模型调试界面（ModelDebugScreen）设计

日期：2026-08-03
状态：已批准（待实现）

## 背景

Gmod 模型加载/渲染存在两类问题：**白膜**（部分 mesh 无纹理，落到 1x1 白色回退纹理）
与**头部过小**（缩放/骨骼异常）。现有诊断手段分散在日志、CLI 工具（`MdlDiagnostic`、
`ModelDiagnostics`）与编辑器界面（`ModelEditorScreen`）中，缺少一个游戏内、面向
"加载与渲染双环节"的统一调试视图。

参考 YSM（YesSteveModel/LgeacyYSM）的 `DebugAnimationScreen`：用 `renderText(name, value)`
逐行渲染诊断数据、按值类型着色（浮点蓝/整数金/bool 绿红）、交替背景色。
本项目采用同样的文本行渲染风格承载右侧诊断面板。

## 目标

- 游戏内打开一个调试界面，**左侧 3D 预览**（待机姿势），**右侧诊断面板**。
- 覆盖两个环节：
  1. **加载环节**：实时加载进度（`ModelLoadProgress`）、本次加载诊断（`ModelLoadDiagnostics`）、
     文件完整性（`ModelDiagnostics.diagnoseDirectory`）、强制重载、切换 Java/Native 解析策略。
  2. **渲染环节**：待机姿势模型预览、白膜检测（红色高亮未贴图 mesh）、
     缩放/骨骼/纹理相关变量。
- 入口：新快捷键（默认 **M 键**，标准 KeyMapping，可在游戏内"选项→控制"改键）
  + `GmodModelScreen` 按钮，双入口。

## 方案选择

- **方案 A（已采纳）**：复用 `ModelViewport`（orbit/zoom/pan 相机与背景渲染）作为左侧预览，
  为其增加 `setBoneMatrices(float[][])` 蒙皮注入点；待机骨骼由
  `AnimationProcessor` 的 reference pose / A-pose / bind pose 逻辑生成。
- 备选 B（复用 `JavaModelRenderer` 实体渲染路径，需假实体）与
  备选 C（绑定游戏内真实 NPC）因耦合实体、无法独立于游戏状态而否决。

## 实现

### 1. ModelViewport 蒙皮扩展（client/editor/ModelViewport.java）

- 新增字段 `private float[][] boneMatrices;` 与 `setBoneMatrices(float[][])` / `clearBoneMatrices()`。
- `render()` 中：当 `boneMatrices != null && boneWeights != null` 时，对每个 mesh 走
  逐顶点蒙皮渲染；否则维持现状（绑定姿势直渲）。
- 蒙皮顶点计算逻辑与 `JavaModelRenderer.renderMeshSkinned` 保持一致。
  为免复制两份逻辑：从 `JavaModelRenderer` 提取一个静态蒙皮 helper（如
  `skinVertex(mesh, vertIdx, boneMatrices, outPos, outNormal)`），两边复用。
- 视口交互（orbit/zoom/pan/背景）完全不变。

### 2. 待机骨骼生成

- 静态工具方法（放 `ModelDebugScreen` 或 `AnimationProcessor` 新增无实体重载）：
  - 优先 `modelData.hasReferencePose()` → `getReferenceFrameData()` 生成骨骼矩阵；
  - 否则 `modelData.hasAPose()` → `getAPoseFrameData()`；
  - 否则退化绑定姿势（复用 `AnimationProcessor.initializeBindPose` 逻辑）。
- 无需 `LivingEntity`，是纯模型数据到骨骼矩阵的静态计算。

### 3. 调试界面 client/debug/ModelDebugScreen.java

布局：

```
+-----------------------------------------------------------+
|  [← 返回]       标题: 模型调试                              |
|  ┌──────────────────────┐   ├─ 加载状态区（实时）           |
|  │   3D 预览            │   ├─ 加载诊断区（本次加载）       |
|  │  (待机姿势, orbit)    │   ├─ 文件完整性诊断              |
|  │                      │   ├─ 相关变量(缩放/纹理/骨骼)    |
|  └──────────────────────┘   ├─ 白膜检测(红色高亮未贴图mesh)|
|                             ├─ bodypart 数量+名称列表      |
|                             └─ 已加载模型列表(可点击切换)   |
|                       底部按钮:[重新加载] [切换解析策略]     |
+-----------------------------------------------------------+
```

- 左侧：`ModelViewport`，rect 占屏幕左侧约 60%；滚轮缩放、拖拽旋转/平移。
- 右侧：YSM 式逐行文本（`renderText(name, value)`），长列表用滚动偏移（上下方向键/滚轮）。
- 状态：`SourceModelData model`、`ModelLoadDiagnostics diag`、当前模型名、
  面板滚动偏移、加载中标志。

#### 加载状态区（实时）

- 读 `ModelLoadProgress`：`getCurrentPhase()`（SCANNING/PARSING/TEXTURING/BUILDING）、
  `getProgress()`、`getCompletedItems()`/`getTotalItems()`、`getCurrentItem()`、`getElapsed()`。
- 打开界面时若目标模型正在异步加载，实时渲染进度而非空白。

#### 加载诊断区（本次加载完成）

- 数据来自 `ModelLoadManager` 的 `ModelLoadCallback`（`fireModelLoadedCallbacks` 已推送
  `ModelLoadDiagnostics`）或加载完成后直接读取本次结果。
- 展示：解析策略（`parserStrategy`）、MDL 版本、耗时、成功/失败（红色 FAILED）、
  骨骼/bodypart/mesh/顶点/三角面/纹理数量、`bodyPartNames`、`textureNames`、`warnings`。

#### 文件完整性诊断

- 调 `ModelDiagnostics.diagnoseDirectory(packageDir)`，展示各文件（MDL/VVD/VTX/PHY）
  校验和一致性检查与纹理缺失告警。可点击按钮触发扫描（开销较大）。

#### 相关变量

- `modelScale`、`minX/maxX/minY/maxY/minZ/maxZ`、`bones.size()`、
  `meshes.size()`、三角面数、当前解析策略、纹理注册数。

#### 白膜检测

- 遍历 `model.meshes`：`texture == null` 或 vtfKey 未注册 → 红色高亮列出
  `mesh[i] 材质名/纹理键`，帮助直接定位未贴图 mesh。

#### bodypart 区

- `model.bodyParts`：每个 `BodyPartInfo` 显示 `name`、`numModels`、`baseIndex`。
- 数量 = `bodyParts.size()`。

#### 已加载模型列表

- 数据来自 `GmodModelConfig.scanModelPackages()`。
- 点击条目 → 以该模型为目标重新初始化界面（加载→预览→诊断刷新）。

### 4. 强制重载与解析策略切换

- **重新加载**：`ModelLoadManager.unloadModel(cacheKey)` 清除该模型缓存后，
  `ModelLoadManager.loadModel(packageDir, strategy)` 重新加载；面板显示"加载中"，
  完成后刷新诊断与预览。
- **切换解析策略**：在当前 active strategy 与 Java strategy 之间切换。
  通过 `ModelParserProvider` 支持：新增一个简单的运行时切换入口
  （`ModelParserProvider.setOverride(strategy)` 或在调试界面内部记录"本次用 Java"，
  调用 `loadModel(packageDir, javaStrategy)` 直接指定策略，不动全局 active）。
  - 采用后者（界面内直接指定策略调用 `loadModel`），不污染全局配置。
  - 展示当前策略名，按钮文案显示目标策略（如"切到 Java"）。

### 5. 入口

- `GmodKeyBindings`：新增 `KeyMapping OPEN_DEBUG_KEY`，默认 `GLFW.GLFW_KEY_M`，
  `KeyConflictContext.IN_GAME`，注册到 `RegisterKeyMappingsEvent`；
  `onKeyInput` 中 `mc.setScreen(new ModelDebugScreen())`。
  （标准 KeyMapping，游戏内可改键，无硬编码覆盖。）
- `GmodModelScreen`：底部按钮行（ai_config / model_editor / anim_editor 所在行）追加
  "调试"按钮，`mc.setScreen(new ModelDebugScreen())`。
- 语言文件 `assets/<modid>/lang/zh_cn.json`（及 en_us.json）补键位与按钮文案。

### 6. 错误处理

- 目标模型未加载 → 复用 `MdlModelRenderer.loadModel` 异步加载，面板显示"加载中"。
- 加载失败（返回 null / 异常）→ 面板红色提示错误信息，预览区域显示"加载失败"。
- 模型包目录不可用 → 面板提示，不崩溃。
- 所有诊断读取均空值安全（`List.of()` 兜底）。

### 7. 测试

- 单元测试：`ModelViewport` 蒙皮注入——同一 mesh 在 `boneMatrices=null`（绑定姿势）与
  提供单位矩阵骨骼时顶点输出一致；提供非单位矩阵时输出随骨骼变换（可用少量顶点断言）。
- 单元测试：待机骨骼生成——对含 reference pose 的 `SourceModelData` 生成矩阵非空且尺寸=骨骼数。
- 单元测试：白膜检测——构造含 `texture=null` mesh 的数据，断言检测结果标记该 mesh。
- 编译验证：`gradlew compileJava`；native 部分无改动。

## 已知限制

- 左侧预览仅 Java 渲染路径；native 渲染器不在 Screen 内预览（native 端差异仍依赖
  "切换解析策略"后重新加载，从诊断/白膜检测中观察）。
- 文件完整性扫描较大目录时可能卡顿，仅按钮触发，不自动执行。
- bodypart 切换（改变 bodygroup 变体）不在本期范围。
