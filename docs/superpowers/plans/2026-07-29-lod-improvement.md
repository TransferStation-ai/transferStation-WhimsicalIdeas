# LOD 系统改进实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 Java 路径的 LOD 使用 VTX 预设的多级三角面数据，无 VTX LOD 时用 QEM 算法取代当前粗暴的"每隔 N 个三角"简化

**架构：** Part A 将 `buildMeshes()` 重构出 `buildMeshesForLod(mdl, vvd, vtx, lodLevel, meshTextureMap)`，然后在 `buildSourceModelData()` 中额外调用 LOD 1-3 并填充 `lodMeshes*`。Part B 创建 `MeshDecimator.java` 实现 QEM 边折叠，替换 `SourceModelData.decimateMesh()`

**技术栈：** Java 17, Minecraft Forge, Blaze3D

---

## 文件结构

### Part A — VTX 原生 LOD
| 文件 | 操作 | 职责 |
|------|------|------|
| `ModelLoadManager.java:2097-2393` | 重构 | `buildMeshes()` 提取为 `buildMeshesForLod()`，新增 LOD 1-3 调用 |

### Part B — QEM Fallback
| 文件 | 操作 | 职责 |
|------|------|------|
| `client/model/MeshDecimator.java` | 创建 | QEM 边折叠简化算法 |
| `SourceModelData.java:465-571` | 修改 | `decimateMesh()` 调用替换为 `MeshDecimator.decimate()` |

---

### 任务 1：重构 buildMeshes → 提取 buildMeshesForLod

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java:2097-2393`

**逻辑：**

将 `buildMeshes()` 的完整方法体提取为 `buildMeshesForLod()`，其签名为：

```java
private static List<SourceModelData.MeshData> buildMeshesForLod(
    MdlDataTypes.ParsedModel mdl,
    VvdParser.ParsedVvd vvd,
    VtxParser.ParsedVtx vtx,
    int lodLevel,
    Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap
)
```

两处改动：
1. 三角面数据源：用 `VtxParser.getTrianglesForLod(vtx, lodLevel)` 代替 `vtx.meshTriangles`
2. 返回 `List<MeshData>` 而非写入 `result.meshes`

**`buildMeshesForLod()` 的完整代码：** 与原有 `buildMeshes` 方法体内容一致，但使用 `VtxParser.getTrianglesForLod(vtx, lodLevel)` 获取三角面数据，返回 `List<SourceModelData.MeshData>` 而非写入 `result.meshes`。所有其他逻辑（bodypart→model→mesh lockstep 遍历、VVD 顶点索引解析、坐标转换、骨骼权重提取、纹理映射）保持不变。

**改后的 `buildMeshes()` 精简为：**

```java
private static void buildMeshes(
    MdlDataTypes.ParsedModel mdl,
    VvdParser.ParsedVvd vvd,
    VtxParser.ParsedVtx vtx,
    Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap,
    SourceModelData result
) {
    List<SourceModelData.MeshData> meshes = buildMeshesForLod(mdl, vvd, vtx, 0, meshTextureMap);
    result.meshes.addAll(meshes);
    if (result.meshes.isEmpty()) {
        LOGGER.warn("[ModelLoadManager] No meshes built: VTX produced no triangles. Model will be skipped (renders nothing).");
    }
}
```

**在 `buildSourceModelData()` 中（第 664 行 `buildMeshes(...)` 之后）追加 LOD 构建：**

```java
// LOD 1-3 from VTX native data (same vertex resolution, different strip groups)
for (int lod = 1; lod <= 3; lod++) {
    List<SourceModelData.MeshData> lodMeshes = buildMeshesForLod(mdl, vvd, vtx, lod, meshTextureMap);
    if (!lodMeshes.isEmpty()) {
        switch (lod) {
            case 1 -> result.lodMeshes1.addAll(lodMeshes);
            case 2 -> result.lodMeshes2.addAll(lodMeshes);
            case 3 -> result.lodMeshes3.addAll(lodMeshes);
        }
    }
}
```

- [ ] **步骤 1：** 将 `buildMeshes()` 的方法体完整复制为 `buildMeshesForLod()`，修改三角面数据源为 `VtxParser.getTrianglesForLod(vtx, lodLevel)`，返回类型改为 `List<SourceModelData.MeshData>`
- [ ] **步骤 2：** 简化 `buildMeshes()` 为对 `buildMeshesForLod(mdl, vvd, vtx, 0, meshTextureMap)` 的代理调用
- [ ] **步骤 3：** 在 `buildSourceModelData()` 中 `buildMeshes(...)` 调用后添加 LOD 1-3 构建循环
- [ ] **步骤 4：** 构建验证：`./gradlew build` 确认无编译错误
- [ ] **步骤 5：** Commit

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/ModelLoadManager.java
git commit -m "feat(lod): use VTX native LOD data in Java parsing path"
```

---

### 任务 2：创建 MeshDecimator（QEM 简化算法）

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/MeshDecimator.java`

**完整代码：**

```java
package transferstation.transferstation_whimsicalideas.client.model;

import java.util.*;

public class MeshDecimator {

    // QEM quadric: symmetric 4x4 matrix stored as 10 floats
    private static class Quadric {
        double a2, b2, c2, d2;
        double ab, ac, ad, bc, bd, cd;

        Quadric() {}

        Quadric(double a, double b, double c, double d) {
            a2 = a * a; b2 = b * b; c2 = c * c; d2 = d * d;
            ab = a * b; ac = a * c; ad = a * d;
            bc = b * c; bd = b * d;
            cd = c * d;
        }

        Quadric add(Quadric o) {
            Quadric r = new Quadric();
            r.a2 = a2 + o.a2; r.b2 = b2 + o.b2; r.c2 = c2 + o.c2; r.d2 = d2 + o.d2;
            r.ab = ab + o.ab; r.ac = ac + o.ac; r.ad = ad + o.ad;
            r.bc = bc + o.bc; r.bd = bd + o.bd; r.cd = cd + o.cd;
            return r;
        }

        double evaluate(double x, double y, double z) {
            return x * x * a2 + y * y * b2 + z * z * c2 + d2
                + 2 * (x * y * ab + x * z * ac + x * ad + y * z * bc + y * bd + z * cd);
        }
    }

    private static class Vertex {
        int id;
        double x, y, z;
        double u, v;
        double nx, ny, nz;
        Quadric q;
        List<Integer> faces = new ArrayList<>();
        boolean deleted;
    }

    private static class Face {
        int v0, v1, v2;
        boolean deleted;
    }

    private static class Edge implements Comparable<Edge> {
        int v0, v1;
        double cost;

        Edge(int v0, int v1) {
            this.v0 = Math.min(v0, v1);
            this.v1 = Math.max(v0, v1);
        }

        @Override
        public int compareTo(Edge o) {
            return Double.compare(this.cost, o.cost);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Edge e)) return false;
            return v0 == e.v0 && v1 == e.v1;
        }

        @Override
        public int hashCode() {
            return v0 * 31 + v1;
        }
    }

    public static SourceModelData.MeshData decimate(
            SourceModelData.MeshData original, int targetTriCount) {
        if (original == null || original.indices == null) return null;
        int triCount = original.indices.length / 3;
        if (targetTriCount <= 0) targetTriCount = 1;
        if (targetTriCount >= triCount) return original;

        float[] verts = original.vertices;
        int[] idx = original.indices;

        // Build vertex and face structures
        int numVerts = verts.length / 8;
        Map<Integer, Vertex> vertexMap = new HashMap<>();
        List<Face> faces = new ArrayList<>();

        for (int i = 0; i < numVerts; i++) {
            Vertex v = new Vertex();
            v.id = i;
            v.x = verts[i * 8];
            v.y = verts[i * 8 + 1];
            v.z = verts[i * 8 + 2];
            v.nx = verts[i * 8 + 3];
            v.ny = verts[i * 8 + 4];
            v.nz = verts[i * 8 + 5];
            v.u = verts[i * 8 + 6];
            v.v = verts[i * 8 + 7];
            v.q = new Quadric();
            vertexMap.put(i, v);
        }

        for (int i = 0; i < idx.length; i += 3) {
            Face f = new Face();
            f.v0 = idx[i]; f.v1 = idx[i + 1]; f.v2 = idx[i + 2];
            faces.add(f);
            vertexMap.get(f.v0).faces.add(faces.size() - 1);
            vertexMap.get(f.v1).faces.add(faces.size() - 1);
            vertexMap.get(f.v2).faces.add(faces.size() - 1);
        }

        // Compute initial QEM quadrics from face planes
        for (Face f : faces) {
            Vertex v0 = vertexMap.get(f.v0);
            Vertex v1 = vertexMap.get(f.v1);
            Vertex v2 = vertexMap.get(f.v2);
            double ax = v1.x - v0.x, ay = v1.y - v0.y, az = v1.z - v0.z;
            double bx = v2.x - v0.x, by = v2.y - v0.y, bz = v2.z - v0.z;
            double nx = ay * bz - az * by;
            double ny = az * bx - ax * bz;
            double nz = ax * by - ay * bx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-10) continue;
            nx /= len; ny /= len; nz /= len;
            double d = -(nx * v0.x + ny * v0.y + nz * v0.z);
            Quadric q = new Quadric(nx, ny, nz, d);
            v0.q = v0.q.add(q);
            v1.q = v1.q.add(q);
            v2.q = v2.q.add(q);
        }

        // Build edge heap
        Set<Edge> edgeSet = new HashSet<>();
        for (Face f : faces) {
            edgeSet.add(new Edge(f.v0, f.v1));
            edgeSet.add(new Edge(f.v1, f.v2));
            edgeSet.add(new Edge(f.v2, f.v0));
        }
        PriorityQueue<Edge> heap = new PriorityQueue<>();
        for (Edge e : edgeSet) {
            computeEdgeCost(e, vertexMap);
            heap.offer(e);
        }

        int targetFaces = targetTriCount;
        while (faces.size() > targetFaces && !heap.isEmpty()) {
            Edge e = heap.poll();
            if (e.cost == Double.MAX_VALUE) break;
            Vertex v0 = vertexMap.get(e.v0);
            Vertex v1 = vertexMap.get(e.v1);
            if (v0.deleted || v1.deleted) continue;

            // Verify edge still exists (both verts share at least one face)
            boolean stillEdge = false;
            for (int fi : v0.faces) {
                if (fi >= faces.size()) continue;
                Face f = faces.get(fi);
                if (f.deleted) continue;
                if ((f.v0 == e.v1 || f.v1 == e.v1 || f.v2 == e.v1)) {
                    stillEdge = true;
                    break;
                }
            }
            if (!stillEdge) continue;

            // Collapse: keep v0, remove v1
            double nx = (v0.nx + v1.nx) * 0.5;
            double ny = (v0.ny + v1.ny) * 0.5;
            double nz = (v0.nz + v1.nz) * 0.5;
            double nlen = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen > 1e-10) { nx /= nlen; ny /= nlen; nz /= nlen; }

            v0.x = (v0.x + v1.x) * 0.5;
            v0.y = (v0.y + v1.y) * 0.5;
            v0.z = (v0.z + v1.z) * 0.5;
            v0.nx = nx; v0.ny = ny; v0.nz = nz;
            v0.u = (v0.u + v1.u) * 0.5;
            v0.v = (v0.v + v1.v) * 0.5;
            v0.q = v0.q.add(v1.q);

            // Remove faces that would become degenerate
            List<Integer> facesToRemove = new ArrayList<>();
            for (int fi : v1.faces) {
                if (fi >= faces.size()) continue;
                Face f = faces.get(fi);
                if (f.deleted) continue;
                boolean hasV0 = (f.v0 == e.v0 || f.v1 == e.v0 || f.v2 == e.v0);
                if (hasV0) {
                    facesToRemove.add(fi);
                } else {
                    // Redirect v1 references to v0
                    if (f.v0 == e.v1) f.v0 = e.v0;
                    if (f.v1 == e.v1) f.v1 = e.v0;
                    if (f.v2 == e.v1) f.v2 = e.v0;
                    v0.faces.add(fi);
                }
            }
            for (int fi : facesToRemove) {
                Face f = faces.get(fi);
                f.deleted = true;
            }
            v1.deleted = true;

            // Recompute costs for edges involving v0
            Set<Integer> neighborSet = new HashSet<>();
            for (int fi : v0.faces) {
                if (fi >= faces.size()) continue;
                Face f = faces.get(fi);
                if (f.deleted) continue;
                neighborSet.add(f.v0); neighborSet.add(f.v1); neighborSet.add(f.v2);
            }
            for (int vid : neighborSet) {
                if (vid == e.v0) continue;
                Vertex nv = vertexMap.get(vid);
                if (nv == null || nv.deleted) continue;
                Edge ne = new Edge(e.v0, vid);
                computeEdgeCost(ne, vertexMap);
                heap.offer(ne);
            }
        }

        // Build result mesh from remaining vertices and faces
        Set<Integer> usedVerts = new HashSet<>();
        List<Integer> activeFaces = new ArrayList<>();
        for (int i = 0; i < faces.size(); i++) {
            Face f = faces.get(i);
            if (f.deleted) continue;
            activeFaces.add(f.v0); activeFaces.add(f.v1); activeFaces.add(f.v2);
            usedVerts.add(f.v0); usedVerts.add(f.v1); usedVerts.add(f.v2);
        }

        if (activeFaces.size() < 3) {
            // Return a minimal single-triangle fallback
            Vertex v0 = vertexMap.get(0);
            float[] fv = new float[8 * 3];
            fv[0] = (float)v0.x; fv[1] = (float)v0.y; fv[2] = (float)v0.z;
            fv[3] = (float)v0.nx; fv[4] = (float)v0.ny; fv[5] = (float)v0.nz;
            fv[6] = (float)v0.u; fv[7] = (float)v0.v;
            System.arraycopy(fv, 0, fv, 8, 8);
            System.arraycopy(fv, 0, fv, 16, 8);
            fv[16 + 3] = 1; // shift second vertex
            return new SourceModelData.MeshData.Builder()
                .vertices(fv).indices(new int[]{0, 1, 2})
                .texture(original.texture).normalMap(original.normalMap)
                .translucent(original.translucent).alphaTest(original.alphaTest)
                .noCull(original.noCull).selfIllum(original.selfIllum)
                .hasPhong(original.hasPhong).halfLambert(original.halfLambert)
                .phongBoost(original.phongBoost).vtfKey(original.vtfKey)
                .colorTint(original.colorTint).alpha(original.alpha)
                .surfaceProp(original.surfaceProp).detailBlendMode(original.detailBlendMode)
                .build();
        }

        // Remap indices to compact vertex array
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        for (int vi : activeFaces) {
            if (!remap.containsKey(vi)) {
                remap.put(vi, remap.size());
            }
        }
        int newVertCount = remap.size();
        float[] newVerts = new float[newVertCount * 8];
        for (Map.Entry<Integer, Integer> entry : remap.entrySet()) {
            Vertex v = vertexMap.get(entry.getKey());
            int dst = entry.getValue() * 8;
            newVerts[dst] = (float) v.x;
            newVerts[dst + 1] = (float) v.y;
            newVerts[dst + 2] = (float) v.z;
            newVerts[dst + 3] = (float) v.nx;
            newVerts[dst + 4] = (float) v.ny;
            newVerts[dst + 5] = (float) v.nz;
            newVerts[dst + 6] = (float) v.u;
            newVerts[dst + 7] = (float) v.v;
        }

        int[] newIdx = new int[activeFaces.size()];
        for (int i = 0; i < activeFaces.size(); i++) {
            newIdx[i] = remap.get(activeFaces.get(i));
        }

        return new SourceModelData.MeshData.Builder()
            .vertices(newVerts).indices(newIdx)
            .texture(original.texture).normalMap(original.normalMap)
            .ssbumpMap(original.ssbumpMap).envMapMask(original.envMapMask)
            .parallaxMap(original.parallaxMap).detailMap(original.detailMap)
            .selfIllumMask(original.selfIllumMask)
            .translucent(original.translucent).alphaTest(original.alphaTest)
            .noCull(original.noCull).selfIllum(original.selfIllum)
            .hasPhong(original.hasPhong).halfLambert(original.halfLambert)
            .phongBoost(original.phongBoost)
            .phongFresnelRanges(original.phongFresnelRanges)
            .phongExponentTexture(original.phongExponentTexture)
            .bodyPartIndex(original.bodyPartIndex).modelIndex(original.modelIndex)
            .materialIndex(original.materialIndex)
            .vtfKey(original.vtfKey).colorTint(original.colorTint)
            .alpha(original.alpha).surfaceProp(original.surfaceProp)
            .detailBlendMode(original.detailBlendMode)
            .build();
    }

    private static void computeEdgeCost(Edge e, Map<Integer, Vertex> vertexMap) {
        Vertex v0 = vertexMap.get(e.v0);
        Vertex v1 = vertexMap.get(e.v1);
        if (v0 == null || v1 == null || v0.deleted || v1.deleted) {
            e.cost = Double.MAX_VALUE;
            return;
        }
        double mx = (v0.x + v1.x) * 0.5;
        double my = (v0.y + v1.y) * 0.5;
        double mz = (v0.z + v1.z) * 0.5;
        Quadric q = v0.q.add(v1.q);
        e.cost = q.evaluate(mx, my, mz);

        // Boundary penalty: if edge has only one adjacent face, penalize heavily
        int sharedFaces = 0;
        for (int fi : v0.faces) {
            if (fi >= v0.faces.size()) continue; // will be handled by bounds check
        }
        // Count shared faces between v0 and v1
        Set<Integer> v0FaceSet = new HashSet<>(v0.faces);
        for (int fi : v1.faces) {
            if (v0FaceSet.contains(fi)) sharedFaces++;
        }
        if (sharedFaces <= 1) {
            e.cost *= 10.0;
        }
    }
}
```

- [ ] **步骤 1：** 创建 `MeshDecimator.java` 文件，写入上述代码
- [ ] **步骤 2：** 构建验证：`./gradlew build` 确认无编译错误
- [ ] **步骤 3：** Commit

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/MeshDecimator.java
git commit -m "feat(lod): add QEM mesh decimation algorithm"
```

---

### 任务 3：替换 decimateMesh 调用

**文件：**
- 修改：`src/main/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelData.java:465-571`

**逻辑：**

将 `decimateMesh()` 方法的现有内容替换为对 `MeshDecimator.decimate()` 的调用：

```java
private static MeshData decimateMesh(MeshData original, int lodLevel) {
    int stride = switch (lodLevel) {
        case 1 -> 2;
        case 2 -> 4;
        case 3 -> 10;
        default -> 1;
    };
    int triCount = original.indices.length / 3;
    int targetTriCount = Math.max(1, (triCount + stride - 1) / stride);
    return MeshDecimator.decimate(original, targetTriCount);
}
```

同时移除 `decimateMesh` 方法中所有现有的顶点压缩、权重保留逻辑（QEM 自身处理所有细节）。

- [ ] **步骤 1：** 将 `decimateMesh()` 的方法体重写为上述 6 行对 `MeshDecimator.decimate()` 的代理
- [ ] **步骤 2：** 删除 `decimateMesh()` 中被替换的旧代码（~100 行）— 保留方法签名和 switch 逻辑即可
- [ ] **步骤 3：** 在文件顶部 import 区域添加 `import transferstation.transferstation_whimsicalideas.client.model.MeshDecimator;`
- [ ] **步骤 4：** 构建验证：`./gradlew build` 确认无编译错误
- [ ] **步骤 5：** Commit

```bash
git add src/main/java/transferstation/transferstation_whimsicalideas/client/model/SourceModelData.java
git commit -m "feat(lod): replace decimateMesh with QEM simplification"
```

---

### 任务 4：验证整体回归

- [ ] **步骤 1：** 确认所有修改文件已存在且编译通过
- [ ] **步骤 2：** 确认现有 test 仍能通过：查看 `src/test/java/.../client/model/` 下是否有 LOD 相关测试并运行
- [ ] **步骤 3：** 确认逻辑完整性：
  - VTX LOD 存在时：`getMeshesForLod()` 返回 `lodMeshes1/2/3`（非空）→ 使用 VTX 原生 LOD
  - VTX LOD 不存在时：`getMeshesForLod()` 走 `decimateMesh()` → 使用 QEM
  - SMD/BBModel 模型：`lodMeshes1/2/3` 为空 → `decimateMesh()` → QEM
- [ ] **步骤 4：** 最终 Commit（如果有额外修复）

```bash
git add -A
git commit -m "chore: finalize LOD improvement (VTX native + QEM fallback)"
```
