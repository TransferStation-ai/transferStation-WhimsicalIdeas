# LOD 系统改进设计规格

## 概述

改进 Java 路径的 LOD（Level of Detail）系统。当前实现仅在运行时做简单减化（每隔 N 个三角保留一个），与 Native 路径利用 VTX 原生 LOD 数据的做法差距很大。

**两种改进方案叠加：**
1. **Part A** — 像 Native 路径一样解析 VTX 文件中预设的 LOD 三角面数据
2. **Part B** — QEM（Quadric Error Metrics）算法作为无 VTX LOD 数据时的 fallback

## Part A：VTX 原生 LOD

### 现状

`VtxParser.parse()` 已解析所有 LOD 级别的三角面到 `lodMeshTriangles`，`getTrianglesForLod()` 已能按级别获取。但 `buildMeshes()` 只用 LOD 0 的 `meshTriangles`，`lodMeshes1/2/3` 始终为空。

### 改动

**在 `ModelLoadManager.java` 中将 `buildMeshes()` 重构为两步：**

```
buildMeshes(mdl, vvd, vtx, meshTextureMap, result)        // LOD 0 → result.meshes (不变)
buildLodMeshes(vtx, result)                                 // LOD 1-3 → result.lodMeshes*
```

具体来说：

1. 提取 `buildMeshesForLod(mdl, vvd, vtx, lodLevel, meshTextureMap)` → `List<MeshData>`
   - 与现有 `buildMeshes` 逻辑相同，但用 `VtxParser.getTrianglesForLod(vtx, lodLevel)` 代替 `vtx.meshTriangles`
   - VVD 顶点解析、骨骼权重、坐标转换逻辑完全复用

2. `buildMeshes()` 调用 `buildMeshesForLod(0)` 填充 `result.meshes`

3. 在 `buildSourceModelData()` 末尾追加：
   ```java
   for (int lod = 1; lod <= 3; lod++) {
       List<SourceModelData.MeshData> lodMeshes = buildMeshesForLod(mdl, vvd, vtx, lod, meshTextureMap);
       switch (lod) {
           case 1 -> result.lodMeshes1.addAll(lodMeshes);
           case 2 -> result.lodMeshes2.addAll(lodMeshes);
           case 3 -> result.lodMeshes3.addAll(lodMeshes);
       }
   }
   ```

4. LOD 网格复用 LOD 0 的纹理（`meshTextureMap` 按 VTX mesh 索引查找，不同 LOD 的 mesh 索引对齐）

### 涉及文件

| 文件 | 改动 |
|------|------|
| `ModelLoadManager.java` | 重构 `buildMeshes()`，提取 `buildMeshesForLod()` |

### 不涉及文件

- `VtxParser.java` — 无需改动，现有 API 已满足
- `SourceModelData.java` — 无需改动，`lodMeshes1/2/3` 成员已存在
- 磁盘缓存 — LOD 网格运行时构建，不修改缓存格式

### 效果验证

- 有 VTX 多 LOD 的模型：LOD 切换效果与 Native 路径一致
- 无 VTX 多 LOD 的模型：`lodMeshes*` 为空，自动走 QEM fallback

## Part B：QEM 减化算法

### 现状

`SourceModelData.decimateMesh()` 的算法是"每隔 N 个三角保留一个"，完全忽略几何特征：
```java
int stride = switch (lodLevel) {
    case 1 -> 2;    // 保留 50%
    case 2 -> 4;    // 保留 25%
    case 3 -> 10;   // 保留 10%
};
```

这种算法会导致严重的轮廓退化、纹理拉伸和视觉 popping。

### 算法

**Quadric Error Metrics (QEM)** 基于迭代边折叠，每次折叠误差最小的边：

1. **初始化 QEM 矩阵** — 为每个顶点计算 4x4 对称矩阵 `Q = Σ (K_p)`，其中 `K_p` 是顶点相邻三角面的平面方程 `(a,b,c,d)` 的外积
2. **计算边代价** — 对每条边 `(v1, v2)`，计算折叠到最优位置 `v_opt` 的误差 `v_opt^T · (Q1+Q2) · v_opt`
3. **边界保护** — 如果边只有 1 个相邻三角面（属于网格边界），惩罚代价 x10，保护轮廓
4. **纹理接缝保护** — UV 不连续处的边额外惩罚
5. **法线翻转检测** — 折叠后检查相邻三角面法线是否反转，若是则跳过该边
6. **最小堆迭代** — 反复弹出最低代价边并折叠，直到三角数达标
7. **不保留骨骼权重** — LOD 网格不需要骨骼蒙皮数据（fallback 路径无动画）

### 新增文件

`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MeshDecimator.java`

```java
public class MeshDecimator {
    public static SourceModelData.MeshData decimate(
        SourceModelData.MeshData original, 
        int targetTriCount
    );
}
```

### 修改文件

| 文件 | 改动 |
|------|------|
| `SourceModelData.java` | `decimateMesh()` 调用替换为 `MeshDecimator.decimate()` |
| `SourceModelData.java` | `getMeshesForLod()` 中清除过时注释 |

### 边界情况

- **目标面数 >= 原始面数**：返回原始网格，不简化
- **目标面数 < 3**：返回至少 1 个三角形
- **极端简化（~90%）**：算法可能产生几何扭曲，但优于当前均匀采样的结果
- **仅 1 个三角形的网格**：直接返回

## 代码路径矩阵

| 场景 | LOD 数据来源 | 行为 |
|------|-------------|------|
| 有 VTX LOD | `VtxParser.getTrianglesForLod()` — Part A | 使用作者预设 LOD |
| MDL+SMD，无 VTX | `MeshDecimator.decimate()` — Part B | QEM fallback |
| BBModel，无 LOD | `MeshDecimator.decimate()` — Part B | QEM fallback |
| 磁盘缓存命中 | `SourceModelData.getMeshesForLod()` | 运行时计算（缓存只存 LOD 0） |

## 不在此范围的工作

- Native 路径的 LOD 改进（已正确使用 VTX LOD）
- GPU LOD 切换方式（距离阈值已在 `MdlModelRenderer` 中配置）
- 磁盘缓存 LOD 网格（运行时算即可，每次加载时间变化在 5ms 内）
