# Parser 消费链路审计报告（#7）

- 日期：2026-08-10
- 范围：`ParsedModel → SourceModelData` 转换链路逐字段审计

## 审计方法

逐字段追踪 3 段链路：
1. **已解析**：`MdlParser` / `MdlProceduralBones` / `MdlSequenceData` / `MdlFlexAnimation` 是否产出该字段
2. **转换**：`ModelLoadManager.buildSourceModelData` 是否复制到 `SourceModelData`
3. **渲染**：`SourceModelData` 是否有字段且渲染管线（AnimationProcessor / 各 renderer）能否读取

分级：
- **丢失**：已解析但未转换 → 渲染不可用
- **半消费**：已转换但渲染管线只用了部分语义
- **正常**：已解析 + 已转换 + 渲染使用

## 分级汇总

| 级别 | 字段 | 说明 |
|------|------|------|
| 丢失 | axisInterpBones, quatInterpBones, jiggleBones, aimAtBones | MdlProceduralBones 已产出，buildSourceModelData 未复制 |
| 丢失 | sequenceIKRules, sequenceAutolayers, sequenceActivityModifiers, sequenceMovements, localHierarchies | MdlSequenceData 已产出，buildSourceModelData 未复制 |
| 丢失 | meshFlexAnimations | MdlFlexAnimation 已产出，buildSourceModelData 未复制 |
| 正常 | bones, meshes, bodyParts, skinTable, attachments, boneControllers, hitboxSets, sequences, ikChains, flexDescs, flexControllers, flexRules, localAnims, poseParams, localNodes, ikAutoplayLocks, mouths, srcBoneTransforms, sequenceAnimData, referenceSequenceIndices, aPoseSequenceIndices, invBindMatrices | buildSourceModelData 显式 addAll |
| 半消费 | keyValues, surfaceProp, hdr2 | 已转换但仅诊断/日志使用 |

## 逐字段清单

### 程序骨骼（MdlProceduralBones）

| 字段 | 已解析 | 转换 | 渲染 | 级别 |
|------|--------|------|------|------|
| axisInterpBones | ✅ (MdlProceduralBones.java:120) | ❌ | ❌ | 丢失 |
| quatInterpBones | ✅ (MdlProceduralBones.java:154) | ❌ | ❌ | 丢失 |
| aimAtBones | ✅ (MdlProceduralBones.java:169) | ❌ | ❌ | 丢失 |
| jiggleBones | ✅ (MdlProceduralBones.java:212) | ❌ | ❌ | 丢失 |

### 序列数据（MdlSequenceData）

| 字段 | 已解析 | 转换 | 渲染 | 级别 |
|------|--------|------|------|------|
| sequenceIKRules | ✅ (MdlSequenceData.java:64,112) | ❌ | ❌ | 丢失 |
| sequenceAutolayers | ✅ (MdlSequenceData.java:144) | ❌ | ❌ | 丢失 |
| sequenceActivityModifiers | ✅ (MdlSequenceData.java:180) | ❌ | ❌ | 丢失 |
| sequenceMovements | ✅ (MdlSequenceData.java:222) | ❌ | ❌ | 丢失 |
| localHierarchies | ✅ (MdlSequenceData.java:250) | ❌ | ❌ | 丢失 |

### 网格 Flex 动画（MdlFlexAnimation）

| 字段 | 已解析 | 转换 | 渲染 | 级别 |
|------|--------|------|------|------|
| meshFlexAnimations | ✅ (MdlFlexAnimation.parse) | ❌ | ❌ | 丢失 |

## 结论

9 个程序骨骼 + 序列 + flex 字段在 `ParsedModel` 层面已完整解析，但 `buildSourceModelData`
（ModelLoadManager.java:779-880）未复制，`SourceModelData` 无对应字段，渲染管线完全不可读。
修复见 `docs/superpowers/plans/2026-08-10-audit-lane-plan.md` 任务 2-5。
