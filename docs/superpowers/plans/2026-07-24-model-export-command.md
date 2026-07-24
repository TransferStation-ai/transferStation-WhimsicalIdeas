# 模型导出指令 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 注册 `/exportmodel` 指令，将 Source Engine 模型包导出为 Blockbench 兼容的 OBJ+MTL 和 .bbmodel 格式。

**架构：** 服务端指令（`command` 包）调用导出引擎，后者直接使用 `client.model` 包中的底层解析器（MdlParser/VvdParser/VtxParser/SmdParser/VtfParser），这些解析器不依赖 Minecraft 客户端 API，可在服务端运行。输出写入模型包目录下的 `export/` 子目录。

**技术栈：** Minecraft Forge 1.20+, Brigadier, java.awt.image, javax.imageio, Gson

---

### 任务 1：创建 ModelExportCommand

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/command/ModelExportCommand.java`

- [ ] **步骤 1：编写指令框架（参数解析 + 补全建议）**

指令结构：
```
/exportmodel <package> [format] [output]
  - package: 模型包名（StringArgumentType.greedyString），带目录补全
  - format: (可选) "obj" / "bbmodel" / "all"，默认 "all"
  - output: (可选) 输出路径，默认 <packageDir>/export/
```

注册方式与 `NpcCommand` / `ModelPartsCommand` 一致：
```java
@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class ModelExportCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("exportmodel")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("package", StringArgumentType.greedyString())
                .suggests(MODEL_PACKAGE_SUGGESTIONS)
                .executes(ctx -> export(ctx, "all", null))
                .then(Commands.literal("obj")
                    .executes(ctx -> export(ctx, "obj", null))
                    .then(Commands.argument("output", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx, "obj", StringArgumentType.getString(ctx, "output")))))
                .then(Commands.literal("bbmodel")
                    .executes(ctx -> export(ctx, "bbmodel", null))
                    .then(Commands.argument("output", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx, "bbmodel", StringArgumentType.getString(ctx, "output")))))
                .then(Commands.literal("all")
                    .executes(ctx -> export(ctx, "all", null))
                    .then(Commands.argument("output", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx, "all", StringArgumentType.getString(ctx, "output")))))
            ));
    }
```

补全提供者：扫描 `<configDir>/models/` 下所有子目录作为模型包名。

```java
private static final SuggestionProvider<CommandSourceStack> MODEL_PACKAGE_SUGGESTIONS =
    (ctx, builder) -> {
        Path modelsDir = getModelsDir();
        if (modelsDir == null || !Files.exists(modelsDir)) return builder.buildFuture();
        try (Stream<Path> dirs = Files.list(modelsDir)) {
            dirs.filter(Files::isDirectory)
                .map(d -> d.getFileName().toString())
                .forEach(builder::suggest);
        } catch (IOException ignored) {}
        return builder.buildFuture();
    };

private static Path getModelsDir() {
    return FMLPaths.CONFIGDIR.get()
        .resolve(Transferstation_whimsicalideas.MODID)
        .resolve("models");
}
```

- [ ] **步骤 2：编写 export 执行方法**

```java
private static int export(CommandContext<CommandSourceStack> ctx, String defaultFormat, String defaultOutput) {
    CommandSourceStack source = ctx.getSource();
    String packageName = StringArgumentType.getString(ctx, "package");
    String format = defaultFormat;
    String outputStr = defaultOutput;

    try {
        Path modelsDir = getModelsDir();
        if (modelsDir == null || !Files.exists(modelsDir)) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.not_found", packageName));
            return 0;
        }
        Path packageDir = modelsDir.resolve(packageName);
        if (!Files.exists(packageDir) || !Files.isDirectory(packageDir)) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.not_found", packageName));
            return 0;
        }

        Path outputDir = outputStr != null ? Path.of(outputStr) : packageDir.resolve("export");
        Files.createDirectories(outputDir);

        source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.export.started", packageName, format), false);

        ModelExporter.ExportResult result = ModelExporter.export(packageDir, outputDir, format);
        if (result.success) {
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.export.success", outputDir.toString()), true);
        } else {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.failed", result.errorMessage));
        }
        return result.success ? 1 : 0;
    } catch (Exception e) {
        source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.failed", e.getMessage()));
        return 0;
    }
}
```

### 任务 2：创建 ModelExporter（核心导出引擎）

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/export/ModelExporter.java`
- 引用：`client/model/MdlParser.java`, `client/model/VvdParser.java`, `client/model/VtxParser.java`, `client/model/SmdParser.java`, `client/model/VtfParser.java`, `client/model/SourceModelData.java`

- [ ] **步骤 1：编写 ExportResult 和入口方法**

```java
package transferstation.transferstation_whimsicalideas.export;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ModelExporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class ExportResult {
        public final boolean success;
        public final String errorMessage;
        public ExportResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage;
        }
    }

    public static ExportResult export(Path packageDir, Path outputDir, String format) {
        try {
            // 1. 扫描并加载模型
            // 2. 导出纹理
            // 3. 根据 format 调用 ObjWriter / BBModelWriter
        } catch (Exception e) {
            LOGGER.error("Export failed for {}", packageDir, e);
            return new ExportResult(false, e.getMessage());
        }
    }
}
```

- [ ] **步骤 2：实现模型加载（直接调用底层解析器）**

复用 `ModelLoadManager.loadFromDirectory` 的文件发现逻辑，但直接调用解析器：

```java
private static SourceModelData loadModelForExport(Path packageDir) throws IOException {
    // 扫描目录，找 .mdl/.smd（同 loadFromDirectory 的文件发现模式）
    Map<Path, List<Path>> dirFiles = new HashMap<>();
    try (Stream<Path> walk = Files.walk(packageDir, 8)) {
        for (Path f : walk.filter(Files::isRegularFile).toList()) {
            String name = f.getFileName().toString().toLowerCase();
            if (name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd")) {
                dirFiles.computeIfAbsent(f.getParent(), k -> new ArrayList<>()).add(f);
            }
        }
    }

    // 找最佳 MDL trio 或 SMD（简化版，不处理 sibling 子模型）
    Path mdl = null, vvd = null, vtx = null, smd = null;
    for (var entry : dirFiles.entrySet()) {
        for (Path f : entry.getValue()) {
            String name = f.getFileName().toString().toLowerCase();
            if (name.endsWith(".mdl")) mdl = f;
            else if (name.endsWith(".vvd")) vvd = f;
            else if (name.endsWith(".dx90.vtx")) vtx = f;
            else if (name.endsWith(".smd")) smd = f;
        }
    }

    if (mdl != null && vvd != null && vtx != null) {
        return buildFromMdlTrio(mdl, vvd, vtx);
    } else if (smd != null) {
        return buildFromSmd(smd);
    } else {
        throw new IOException("No parseable model files found in " + packageDir);
    }
}

private static SourceModelData buildFromMdlTrio(Path mdlPath, Path vvdPath, Path vtxPath) throws IOException {
    MdlDataTypes.ParsedModel mdl = MdlParser.parse(Files.readAllBytes(mdlPath));
    VvdParser.ParsedVvd vvd = VvdParser.parse(Files.readAllBytes(vvdPath));
    VtxParser.ParsedVtx vtx = VtxParser.parse(Files.readAllBytes(vtxPath), vvd.vertices.size());

    SourceModelData data = new SourceModelData();
    data.name = mdl.header.name;
    data.modelScale = 1.0f;

    for (MdlDataTypes.Bone bone : mdl.bones) {
        data.bones.add(new SourceModelData.BoneInfo(
            bone.name,
            new float[]{bone.pos[0], bone.pos[1], bone.pos[2]},
            bone.quat != null ? new float[]{bone.quat[0], bone.quat[1], bone.quat[2], bone.quat[3]} : null,
            bone.rot != null ? new float[]{bone.rot[0], bone.rot[1], bone.rot[2]} : null,
            bone.parent));
    }

    // 构建网格：遍历 bodyparts → models → meshes → strips → indices
    buildMeshes(mdl, vvd, vtx, data);

    computeBounds(data);
    return data;
}

private static void buildMeshes(MdlDataTypes.ParsedModel mdl, VvdParser.ParsedVvd vvd,
                                 VtxParser.ParsedVtx vtx, SourceModelData data) {
    int meshIdx = 0;
    for (int bp = 0; bp < vtx.numBodyParts && bp < mdl.bodyParts.size(); bp++) {
        for (int m = 0; m < vtx.meshTriangles.size(); m++) {
            List<VtxParser.VtxTriangle> tris = vtx.meshTriangles.get(m);
            if (tris.isEmpty()) continue;

            // 收集顶点和索引
            Map<Integer, Integer> vtxRemap = new HashMap<>();
            List<Float> verts = new ArrayList<>();
            List<Integer> idxs = new ArrayList<>();

            for (VtxParser.VtxTriangle tri : tris) {
                for (int v : new int[]{tri.v0, tri.v1, tri.v2}) {
                    Integer cached = vtxRemap.get(v);
                    if (cached != null) {
                        idxs.add(cached);
                        continue;
                    }
                    VvdParser.VvdVertex src = vvd.vertices.get(v);
                    // Source 坐标: x=前, y=左, z=上 → Minecraft: x=右, y=上, z=南
                    verts.add(-src.y);  // x
                    verts.add(src.z);   // y
                    verts.add(src.x);   // z
                    verts.add(-src.ny); // nx
                    verts.add(src.nz);  // ny
                    verts.add(src.nx);  // nz
                    verts.add(src.u);   // u
                    verts.add(1.0f - src.v); // v
                    int newIdx = (verts.size() / 8) - 1;
                    vtxRemap.put(v, newIdx);
                    idxs.add(newIdx);
                }
            }

            float[] vertArray = new float[verts.size()];
            for (int i = 0; i < verts.size(); i++) vertArray[i] = verts.get(i);
            int[] idxArray = new int[idxs.size()];
            for (int i = 0; i < idxs.size(); i++) idxArray[i] = idxs.get(i);

            data.meshes.add(new SourceModelData.MeshData.Builder()
                .vertices(vertArray).indices(idxArray)
                .bodyPartIndex(bp).modelIndex(m).materialIndex(meshIdx)
                .build());
            meshIdx++;
        }
    }
}
```

- [ ] **步骤 3：实现 computeBounds 和 SMD 加载**

```java
private static void computeBounds(SourceModelData data) {
    for (SourceModelData.MeshData mesh : data.meshes) {
        for (int i = 0; i < mesh.vertices.length; i += 8) {
            float x = mesh.vertices[i], y = mesh.vertices[i+1], z = mesh.vertices[i+2];
            if (x < data.minX) data.minX = x;
            if (x > data.maxX) data.maxX = x;
            if (y < data.minY) data.minY = y;
            if (y > data.maxY) data.maxY = y;
            if (z < data.minZ) data.minZ = z;
            if (z > data.maxZ) data.maxZ = z;
        }
    }
}

private static SourceModelData buildFromSmd(Path smdPath) throws IOException {
    SmdParser.ParsedSmd smd = SmdParser.parse(Files.readAllBytes(smdPath));
    SourceModelData data = new SourceModelData();
    data.name = smdPath.getFileName().toString().replace(".smd", "");
    data.modelScale = 1.0f;

    for (SmdParser.SmdBone bone : smd.bones) {
        data.bones.add(new SourceModelData.BoneInfo(bone.name, new float[]{0,0,0}, bone.parent));
    }

    int meshIdx = 0;
    for (SmdParser.SmdMesh sm : smd.meshes) {
        if (sm.vertices.size() < 3) continue;
        // 同 ModelLoadManager.processSmdTriangles
        List<Float> vertList = new ArrayList<>();
        List<Integer> idxList = new ArrayList<>();
        Map<String, Integer> vertCache = new HashMap<>();

        int triCount = sm.vertices.size() / 3;
        for (int t = 0; t < triCount; t++) {
            for (int v = 0; v < 3; v++) {
                SmdParser.SmdVertex sv = sm.vertices.get(t * 3 + v);
                String key = String.format("%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%d",
                    sv.x, sv.y, sv.z, sv.nx, sv.ny, sv.nz, sv.u, sv.v, sv.primaryBone());
                Integer cached = vertCache.get(key);
                if (cached != null) { idxList.add(cached); continue; }
                vertList.add(-sv.y); vertList.add(sv.z); vertList.add(sv.x);
                vertList.add(-sv.ny); vertList.add(sv.nz); vertList.add(sv.nx);
                vertList.add(sv.u); vertList.add(1.0f - sv.v);
                int newIdx = (vertList.size() / 8) - 1;
                vertCache.put(key, newIdx);
                idxList.add(newIdx);
            }
        }

        float[] va = new float[vertList.size()];
        for (int i = 0; i < vertList.size(); i++) va[i] = vertList.get(i);
        int[] ia = new int[idxList.size()];
        for (int i = 0; i < idxList.size(); i++) ia[i] = idxList.get(i);

        data.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(va).indices(ia)
            .bodyPartIndex(0).modelIndex(0).materialIndex(meshIdx++)
            .build());
    }
    computeBounds(data);
    return data;
}
```

- [ ] **步骤 4：实现主导出流程（整合 OBJ/BBModel/纹理）**

```java
public static ExportResult export(Path packageDir, Path outputDir, String format) {
    try {
        long start = System.currentTimeMillis();
        SourceModelData model = loadModelForExport(packageDir);

        // 检查有无网格
        if (model.meshes.isEmpty()) {
            return new ExportResult(false, "模型没有可导出的网格");
        }

        // 导出纹理
        List<TextureEntry> textures = TextureExporter.exportTextures(packageDir, outputDir);
        LOGGER.info("Exported {} textures to {}", textures.size(), outputDir);

        boolean exportObj = format.equals("obj") || format.equals("all");
        boolean exportBbmodel = format.equals("bbmodel") || format.equals("all");

        if (exportObj) {
            ObjWriter.write(model, textures, outputDir);
        }
        if (exportBbmodel) {
            BBModelWriter.write(model, textures, outputDir);
        }

        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Export complete for {}: {} meshes, {} triangles, {} textures, took {}ms",
            packageDir.getFileName(), model.meshes.size(), model.totalTriangles(), textures.size(), elapsed);
        return new ExportResult(true, null);
    } catch (Exception e) {
        return new ExportResult(false, e.getMessage());
    }
}

public static class TextureEntry {
    public final String name;
    public final Path pngPath;
    public TextureEntry(String name, Path pngPath) {
        this.name = name;
        this.pngPath = pngPath;
    }
}
```

### 任务 3：创建 TextureExporter

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/export/TextureExporter.java`
- 引用：`client/model/VtfParser.java`, `client/model/TextureDebugExporter.java`（参考）

- [ ] **步骤 1：实现 VTF→PNG 导出**

```java
package transferstation.transferstation_whimsicalideas.export;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class TextureExporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static List<ModelExporter.TextureEntry> exportTextures(Path packageDir, Path outputDir) throws IOException {
        List<ModelExporter.TextureEntry> result = new ArrayList<>();
        Path texturesDir = outputDir.resolve("textures");
        Files.createDirectories(texturesDir);

        // 搜索材料目录
        Path materialsDir = findMaterialsDir(packageDir);
        if (materialsDir == null || !Files.exists(materialsDir)) {
            LOGGER.warn("No materials directory found for {}", packageDir);
            return result;
        }

        try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (!name.endsWith(".vtf")) continue;

                try {
                    byte[] data = Files.readAllBytes(f);
                    VtfParser.VtfImageData vtf = VtfParser.parse(data);
                    if (vtf == null || vtf.image == null) continue;

                    // 保持相对路径结构
                    String relPath = materialsDir.relativize(f).toString()
                        .replace('\\', '/')
                        .replaceAll("\\.vtf$", ".png");
                    Path target = texturesDir.resolve(relPath);
                    Files.createDirectories(target.getParent());

                    if (ImageIO.write(vtf.image, "png", target.toFile())) {
                        result.add(new ModelExporter.TextureEntry(relPath, target));
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to export texture {}: {}", f, e.getMessage());
                }
            }
        }
        return result;
    }

    private static Path findMaterialsDir(Path packageDir) {
        Path direct = packageDir.resolve("materials");
        if (Files.exists(direct) && Files.isDirectory(direct)) return direct;
        Path parent = packageDir.getParent();
        while (parent != null) {
            Path candidate = parent.resolve("materials");
            if (Files.exists(candidate) && Files.isDirectory(candidate)) return candidate;
            parent = parent.getParent();
        }
        return null;
    }
}
```

### 任务 4：创建 ObjWriter

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/export/ObjWriter.java`

- [ ] **步骤 1：实现 OBJ + MTL 写入**

```java
package transferstation.transferstation_whimsicalideas.export;

import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ObjWriter {
    private static final String INDICES = "0123456789abcdefghijklmnopqrstuvwxyz";

    public static void write(SourceModelData model, List<ModelExporter.TextureEntry> textures, Path outputDir) throws IOException {
        String modelName = sanitizeName(model.name.isEmpty() ? "model" : model.name);
        Path objFile = outputDir.resolve(modelName + ".obj");
        Path mtlFile = outputDir.resolve(modelName + ".mtl");

        try (BufferedWriter obj = Files.newBufferedWriter(objFile);
             BufferedWriter mtl = Files.newBufferedWriter(mtlFile)) {

            // MTL header
            mtl.write("# Material file for " + modelName + "\n");

            // OBJ header
            obj.write("# Wavefront OBJ exported from TransferStation WhimsicalIdeas\n");
            obj.write("mtllib " + modelName + ".mtl\n");

            // 统计全局顶点/法线/UV
            int globalVOffset = 0;
            Set<String> writtenMaterials = new HashSet<>();

            for (int m = 0; m < model.meshes.size(); m++) {
                SourceModelData.MeshData mesh = model.meshes.get(m);
                float[] verts = mesh.vertices;
                if (verts == null || verts.length < 8) continue;

                // 确定材质名
                String matName = "mesh_" + m;
                if (mesh.texture != null) {
                    String path = mesh.texture.getPath();
                    if (path.contains("/")) path = path.substring(path.lastIndexOf('/') + 1);
                    if (path.contains(".")) path = path.substring(0, path.lastIndexOf('.'));
                    matName = path;
                }
                matName = sanitizeName(matName);

                // 写入 OBJ 顶点
                obj.write("g " + matName + "\n");
                obj.write("usemtl " + matName + "\n");

                for (int i = 0; i < verts.length; i += 8) {
                    obj.write(String.format("v %.6f %.6f %.6f\n", verts[i], verts[i+1], verts[i+2]));
                    obj.write(String.format("vn %.6f %.6f %.6f\n", verts[i+3], verts[i+4], verts[i+5]));
                    obj.write(String.format("vt %.6f %.6f\n", verts[i+6], verts[i+7]));
                }

                // 写入面
                int[] indices = mesh.indices;
                for (int i = 0; i < indices.length; i += 3) {
                    int v1 = globalVOffset + indices[i] + 1;
                    int v2 = globalVOffset + indices[i+1] + 1;
                    int v3 = globalVOffset + indices[i+2] + 1;
                    obj.write(String.format("f %d/%d/%d %d/%d/%d %d/%d/%d\n",
                        v1, v1, v1, v2, v2, v2, v3, v3, v3));
                }

                globalVOffset += verts.length / 8;

                // 写入 MTL
                if (writtenMaterials.add(matName)) {
                    mtl.write("newmtl " + matName + "\n");
                    mtl.write("Ka 0.6 0.6 0.6\n");
                    mtl.write("Kd 0.8 0.8 0.8\n");
                    mtl.write("Ks 0.1 0.1 0.1\n");
                    mtl.write("Ns 32.0\n");
                    // 查找匹配纹理
                    String texName = matName;
                    // 用短名称匹配
                    String texNameLower = texName.toLowerCase();
                    String matchedPng = null;
                    for (ModelExporter.TextureEntry tex : textures) {
                        String tName = tex.name;
                        if (tName.contains("/")) tName = tName.substring(tName.lastIndexOf('/') + 1);
                        if (tName.contains(".")) tName = tName.substring(0, tName.lastIndexOf('.'));
                        if (tName.equalsIgnoreCase(texName) || texNameLower.contains(tName.toLowerCase())) {
                            matchedPng = tex.name;
                            break;
                        }
                    }
                    if (matchedPng != null) {
                        mtl.write("map_Kd textures/" + matchedPng + "\n");
                    }
                    if (mesh.alpha < 1.0f) {
                        mtl.write(String.format("d %.2f\n", mesh.alpha));
                    }
                    mtl.write("\n");
                }
            }
        }
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
```

### 任务 5：创建 BBModelWriter

**文件：**
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/export/BBModelWriter.java`

- [ ] **步骤 1：实现 BBModel JSON 写入**

```java
package transferstation.transferstation_whimsicalideas.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

public class BBModelWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void write(SourceModelData model, List<ModelExporter.TextureEntry> textures, Path outputDir) throws IOException {
        String modelName = sanitizeName(model.name.isEmpty() ? "model" : model.name);
        Path bbFile = outputDir.resolve(modelName + ".bbmodel");

        JsonObject root = new JsonObject();

        // meta
        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", "4.10");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
        root.add("meta", meta);
        root.addProperty("name", modelName);

        // textures (base64 embedded)
        JsonArray texArray = new JsonArray();
        for (int i = 0; i < textures.size(); i++) {
            ModelExporter.TextureEntry entry = textures.get(i);
            JsonObject tex = new JsonObject();
            tex.addProperty("id", i);
            tex.addProperty("name", entry.name);
            // 读取 PNG → base64
            byte[] pngBytes = Files.readAllBytes(entry.pngPath);
            String b64 = Base64.getEncoder().encodeToString(pngBytes);
            tex.addProperty("source", "data:image/png;base64," + b64);
            texArray.add(tex);
        }
        root.add("textures", texArray);

        // elements（每个 mesh 一个 element）
        JsonArray elements = new JsonArray();
        for (int m = 0; m < model.meshes.size(); m++) {
            SourceModelData.MeshData mesh = model.meshes.get(m);
            if (mesh.vertices == null || mesh.vertices.length < 8) continue;

            JsonObject elem = new JsonObject();
            elem.addProperty("name", "mesh_" + m);
            elem.addProperty("type", "mesh");

            // vertices
            JsonArray verts = new JsonArray();
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                JsonArray v = new JsonArray();
                v.add((double) mesh.vertices[i]);     // x
                v.add((double) mesh.vertices[i+1]);   // y
                v.add((double) mesh.vertices[i+2]);   // z
                verts.add(v);
            }
            elem.add("vertices", verts);

            // faces
            JsonArray faces = new JsonArray();
            for (int i = 0; i < mesh.indices.length; i += 3) {
                JsonArray face = new JsonArray();
                face.add(mesh.indices[i]);
                face.add(mesh.indices[i+1]);
                face.add(mesh.indices[i+2]);
                faces.add(face);
            }
            elem.add("faces", faces);

            // uvs
            JsonArray uvs = new JsonArray();
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                JsonArray uv = new JsonArray();
                uv.add((double) mesh.vertices[i+6]);  // u
                uv.add((double) mesh.vertices[i+7]);  // v
                uvs.add(uv);
            }
            elem.add("uvs", uvs);

            // normals
            JsonArray normals = new JsonArray();
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                JsonArray n = new JsonArray();
                n.add((double) mesh.vertices[i+3]);   // nx
                n.add((double) mesh.vertices[i+4]);   // ny
                n.add((double) mesh.vertices[i+5]);   // nz
                normals.add(n);
            }
            elem.add("normals", normals);

            // faces_materials（所有面使用材质 0 或对应纹理索引）
            JsonArray faceMats = new JsonArray();
            int texId = 0;
            // 通过名称匹配材质
            if (mesh.texture != null) {
                String path = mesh.texture.getPath().toLowerCase();
                for (int t = 0; t < textures.size(); t++) {
                    if (path.contains(textures.get(t).name.toLowerCase().replaceAll("\\.png$", "").replace('/', '_'))) {
                        texId = t;
                        break;
                    }
                }
            }
            for (int i = 0; i < mesh.indices.length / 3; i++) {
                faceMats.add(texId);
            }
            elem.add("faces_materials", faceMats);

            elements.add(elem);
        }
        root.add("elements", elements);

        // outliner（骨骼层次）
        JsonArray outliner = new JsonArray();
        if (!model.bones.isEmpty()) {
            // 构建骨骼树
            int[] childCount = new int[model.bones.size()];
            List<List<Integer>> children = new java.util.ArrayList<>();
            for (int i = 0; i < model.bones.size(); i++) children.add(new java.util.ArrayList<>());
            for (int i = 0; i < model.bones.size(); i++) {
                int p = model.bones.get(i).parent;
                if (p >= 0 && p < model.bones.size()) children.get(p).add(i);
                else childCount[i]++; // root
            }
            // 只加入根骨骼，子骨骼递归添加
            int[] added = new int[model.bones.size()];
            for (int i = 0; i < model.bones.size(); i++) {
                if (added[i] == 0 && isRootBone(i, model.bones)) {
                    outliner.add(buildBoneTree(i, model, children, added));
                }
            }
        }
        root.add("outliner", outliner);

        // bone_groups
        JsonArray boneGroups = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) {
            SourceModelData.BoneInfo bone = model.bones.get(i);
            JsonObject bg = new JsonObject();
            bg.addProperty("name", bone.name);
            bg.addProperty("parent", bone.parent);
            JsonArray pos = new JsonArray();
            if (bone.pos != null) {
                pos.add((double) bone.pos[0]);
                pos.add((double) bone.pos[1]);
                pos.add((double) bone.pos[2]);
            } else {
                pos.add(0); pos.add(0); pos.add(0);
            }
            bg.add("position", pos);
            boneGroups.add(bg);
        }
        root.add("bone_groups", boneGroups);

        Files.writeString(bbFile, GSON.toJson(root));
    }

    private static boolean isRootBone(int idx, List<SourceModelData.BoneInfo> bones) {
        int p = bones.get(idx).parent;
        return p < 0 || p >= bones.size();
    }

    private static JsonObject buildBoneTree(int idx, SourceModelData model,
                                             List<List<Integer>> children, int[] added) {
        added[idx] = 1;
        JsonObject node = new JsonObject();
        node.addProperty("name", model.bones.get(idx).name);
        JsonArray ch = new JsonArray();
        for (int child : children.get(idx)) {
            ch.add(buildBoneTree(child, model, children, added));
        }
        node.add("children", ch);
        return node;
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
```

### 任务 6：添加国际化翻译

**文件：**
- 修改：`src/main/resources/assets/transferstation_whimsicalideas/lang/zh_cn.json`
- 修改：`src/main/resources/assets/transferstation_whimsicalideas/lang/en_us.json`

- [ ] **步骤 1：添加中文翻译键**

在 `zh_cn.json` 末尾添加：
```json
"command.transferstation_whimsicalideas.export.usage": "/exportmodel <包名> [格式] [输出路径]",
"command.transferstation_whimsicalideas.export.started": "§7正在导出模型 '%s' 为 %s 格式...",
"command.transferstation_whimsicalideas.export.success": "§a导出成功：%s",
"command.transferstation_whimsicalideas.export.failed": "§c导出失败：%s",
"command.transferstation_whimsicalideas.export.not_found": "§c未找到模型包：%s",
"command.transferstation_whimsicalideas.export.no_models": "§c模型包 %s 中没有可导出的模型文件"
```

- [ ] **步骤 2：添加英文翻译键**

在 `en_us.json` 末尾添加：
```json
"command.transferstation_whimsicalideas.export.usage": "/exportmodel <package> [format] [output]",
"command.transferstation_whimsicalideas.export.started": "§7Exporting model '%s' as %s format...",
"command.transferstation_whimsicalideas.export.success": "§aExport successful: %s",
"command.transferstation_whimsicalideas.export.failed": "§cExport failed: %s",
"command.transferstation_whimsicalideas.export.not_found": "§cModel package not found: %s",
"command.transferstation_whimsicalideas.export.no_models": "§cNo exportable model files in package %s"
```

### 任务 7：编译验证

- [ ] **步骤 1：运行编译检查**

```bash
gradlew build
```

预期：BUILD SUCCESSFUL。如果有编译错误，根据错误信息修复（主要是 import 和类型引用）。
