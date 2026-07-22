# Task 1: PCF Parser 核心 — 实现报告

## 实现内容

创建了 Source 引擎 .pcf 粒子系统二进制格式的底层解析器，包含两个类文件：

### `PcfParticleSystemDef.java`
- 数据模型 POJO，包含 `SystemDefinition`、`RendererDef`、`InitializerDef`、`OperatorDef`、`ChildDef`、`ForceDef` 等内部类
- 枚举 `RendererType` (SPRITE, MODEL, BEAM, TRAIL, DECAL, LIGHT, ROPE)
- 完全按照 Step 2 规格实现

### `PcfParser.java`
- 二进制 PCF 格式解析器，支持读取 header (signature/version/padding)
- KeyValues 解析引擎：支持 Null, String, Int, Float, Ptr, WString, Object (open/close), Array (open/close)
- 内建 `KvNode`（AST 节点）和 `PcfBuffer`（小端字节缓冲读取器）辅助类型
- 完整属性解析：`parseSystemDef`, `parseRenderer`, `parseInitializers`, `parseOperators`, `parseChildrenList`, `parseForces`
- 递归提取 `m_particleSystemDefinition` 节点

## 修改的文件

| 文件 | 操作 |
|------|------|
| `src/main/java/.../client/particle/PcfParticleSystemDef.java` | **新建** |
| `src/main/java/.../client/particle/PcfParser.java` | **新建** |
| `.superpowers/sdd/task-1-report.md` | **新建** (本文件) |

## 与 Brief 的差异（自审）

1. **`extractSystems` → 改为 `static`**：Brief 中 `extractSystems` 为非静态方法（line 115: `private void`），但从静态方法 `parse()` 调用（line 56: `extractSystems(root, systemDefs)`），会导致编译错误。已修正为 `private static void`。

2. **Child 解析方法重命名**：Brief Step 3 定义了一个 `parseChildren(KvNode, List<ChildDef>)`，但与 Step 1 的 `parseChildren(PcfBuffer, KvNode)` 方法名冲突。将其重命名为 `parseChildrenList` 以避免歧义，相应更新了 `parseSystemDef` 中的调用点。

## 编译验证

- `./gradlew clean compileJava` → BUILD SUCCESSFUL
- 生成的 .class 文件：PcfParser (含 KvNode, KvType, PcfBuffer 内部类) + PcfParticleSystemDef (含全部 7 个内部类)

## 疑虑

无。代码按规格实现，编译通过，结构清晰，为后续任务（Task 2: initializer/operator 实现）提供了完整的解析基础。
