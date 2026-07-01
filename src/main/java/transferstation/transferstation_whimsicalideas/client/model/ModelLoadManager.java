package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ModelLoadManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VERTEX_FLOAT_SIZE = 8;
    private static final int VVD_VERTEX_SIZE = 48;

    private static final Map<String, SourceModelData> modelCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SourceModelData> eldest) {
            return size() > 64;
        }
    };

    private static final Map<String, ResourceLocation> textureRegistry = new HashMap<>();
    private static Path cacheDir = null;

    public static void setCacheDir(Path dir) {
        cacheDir = dir;
    }

    public static SourceModelData getCached(String key) {
        return modelCache.get(key);
    }

    public static void clearCache() {
        modelCache.clear();
    }

    public static SourceModelData loadModel(Path packageDir) {
        String cacheKey = packageDir.toAbsolutePath().toString();
        SourceModelData cached = modelCache.get(cacheKey);
        if (cached != null) return cached;

        // Try disk cache first
        SourceModelData diskData = loadFromDiskCache(packageDir);
        if (diskData != null) {
            modelCache.put(cacheKey, diskData);
            LOGGER.info("[ModelLoadManager] Restored model from disk cache: {} meshes", diskData.meshes.size());
            return diskData;
        }

        try {
            SourceModelData data = loadFromDirectory(packageDir);
            if (data != null && !data.meshes.isEmpty()) {
                modelCache.put(cacheKey, data);
                saveToDiskCache(packageDir, data);
                LOGGER.info("[ModelLoadManager] Loaded model from {}: {} meshes, {} triangles, {} vertices",
                    packageDir, data.meshes.size(), data.totalTriangles(), data.totalVertices());
            }
            return data;
        } catch (Exception e) {
            LOGGER.error("[ModelLoadManager] Failed to load model from {}", packageDir, e);
            return null;
        }
    }

    public static SourceModelData loadModelForLod(Path packageDir, int lodLevel) {
        SourceModelData data = loadModel(packageDir);
        if (data == null) return null;
        
        SourceModelData result = data.getMeshesForLod(lodLevel);
        LOGGER.info("[ModelLoadManager] Loaded model for LOD {}: {} meshes", lodLevel, result.meshes.size());
        return result;
    }

    private static Path getCacheFilePath(Path packageDir) {
        if (cacheDir == null) return null;
        String key = packageDir.toAbsolutePath().toString().replace('\\', '/');
        String hash = Integer.toHexString(key.hashCode());
        return cacheDir.resolve(hash + ".bin.gz");
    }

    private static long getLatestModifiedTime(Path packageDir) {
        try (Stream<Path> files = Files.walk(packageDir, 8)) {
            return files.filter(Files::isRegularFile)
                .map(f -> {
                    try { return Files.getLastModifiedTime(f).toMillis(); }
                    catch (IOException e) { return 0L; }
                })
                .max(Long::compare)
                .orElse(0L);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static SourceModelData loadFromDiskCache(Path packageDir) {
        if (cacheDir == null) return null;
        Path cacheFile = getCacheFilePath(packageDir);
        if (cacheFile == null || !Files.exists(cacheFile)) return null;

        try {
            long currentModTime = getLatestModifiedTime(packageDir);
            try (DataInputStream dis = new DataInputStream(
                    new GZIPInputStream(Files.newInputStream(cacheFile)))) {
                long cachedModTime = dis.readLong();
                if (cachedModTime != currentModTime) {
                    LOGGER.debug("[ModelLoadManager] Disk cache stale for {}", packageDir);
                    return null;
                }

                SourceModelData data = new SourceModelData();
                data.name = dis.readUTF();
                data.minZ = dis.readFloat();
                data.maxZ = dis.readFloat();
                data.modelScale = dis.readFloat();

                int meshCount = dis.readInt();
                for (int m = 0; m < meshCount; m++) {
                    int vertCount = dis.readInt();
                    float[] verts = new float[vertCount];
                    for (int i = 0; i < vertCount; i++) verts[i] = dis.readFloat();

                    int idxCount = dis.readInt();
                    int[] indices = new int[idxCount];
                    for (int i = 0; i < idxCount; i++) indices[i] = dis.readInt();

                    boolean translucent = dis.readBoolean();

                    String txNamespace = dis.readBoolean() ? dis.readUTF() : null;
                    String txPath = dis.readBoolean() ? dis.readUTF() : null;
                    ResourceLocation texture = (txNamespace != null)
                        ? ResourceLocation.parse(txNamespace + ":" + txPath)
                        : null;

                    data.meshes.add(new SourceModelData.MeshData(verts, indices, texture, translucent));
                }

                LOGGER.info("[ModelLoadManager] Disk cache hit for {}: {} meshes", packageDir, data.meshes.size());
                return data;
            }
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to read disk cache for {}", packageDir, e);
            try { Files.deleteIfExists(cacheFile); } catch (IOException ignored) {}
            return null;
        }
    }

    private static void saveToDiskCache(Path packageDir, SourceModelData data) {
        if (cacheDir == null || data == null || data.meshes.isEmpty()) return;
        Path cacheFile = getCacheFilePath(packageDir);
        if (cacheFile == null) return;

        try {
            long modTime = getLatestModifiedTime(packageDir);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(new GZIPOutputStream(bos))) {
                dos.writeLong(modTime);
                dos.writeUTF(data.name != null ? data.name : "");
                dos.writeFloat(data.minZ);
                dos.writeFloat(data.maxZ);
                dos.writeFloat(data.modelScale);

                dos.writeInt(data.meshes.size());
                for (SourceModelData.MeshData mesh : data.meshes) {
                    dos.writeInt(mesh.vertices.length);
                    for (float v : mesh.vertices) dos.writeFloat(v);

                    dos.writeInt(mesh.indices.length);
                    for (int idx : mesh.indices) dos.writeInt(idx);

                    dos.writeBoolean(mesh.translucent);

                    if (mesh.texture != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.texture.getNamespace());
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.texture.getPath());
                    } else {
                        dos.writeBoolean(false);
                        dos.writeBoolean(false);
                    }
                }
            }
            Files.write(cacheFile, bos.toByteArray());
            LOGGER.debug("[ModelLoadManager] Saved model to disk cache: {} ({} bytes)", cacheFile.getFileName(), bos.size());
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to write disk cache for {}", packageDir, e);
        }
    }

    public static void clearAllCaches() {
        modelCache.clear();
        if (cacheDir != null) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                files.forEach(f -> {
                    try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
            LOGGER.info("[ModelLoadManager] Cleared in-memory and disk model caches");
        }
        if (GmodNativeBridge.isAvailable()) {
            GmodNativeBridge.nativeClearAllCaches();
        }
    }

    private static SourceModelData loadFromDirectory(Path packageDir) throws IOException {
        if (!Files.exists(packageDir) || !Files.isDirectory(packageDir)) {
            throw new IOException("Model directory not found: " + packageDir);
        }

        Path mdlPath = null, vvdPath = null, vtxPath = null;
        Path materialsDir = findMaterialsDir(packageDir);

        int maxDepth = 8;
        try (Stream<Path> files = Files.walk(packageDir, maxDepth)) {
            List<Path> fileList = files.filter(Files::isRegularFile).toList();
            for (Path f : fileList) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".mdl")) {
                    if (mdlPath == null) mdlPath = f;
                } else if (name.endsWith(".vvd")) {
                    if (vvdPath == null) vvdPath = f;
                } else if (name.endsWith(".dx90.vtx")) {
                    if (vtxPath == null) vtxPath = f;
                }
            }
        }

        if (mdlPath == null || vvdPath == null || vtxPath == null) {
            throw new IOException("Missing model files in " + packageDir
                + " (mdl=" + (mdlPath != null) + " vvd=" + (vvdPath != null) + " vtx=" + (vtxPath != null) + ")");
        }

        MdlParser.ParsedModel mdl = MdlParser.parse(Files.readAllBytes(mdlPath));
        VvdParser.ParsedVvd vvd = VvdParser.parse(Files.readAllBytes(vvdPath));
        VtxParser.ParsedVtx vtx = VtxParser.parse(Files.readAllBytes(vtxPath));

        // Log include model references for debugging
        if (!mdl.includeModels.isEmpty()) {
            LOGGER.info("[ModelLoadManager] Model references {} include model(s): {}", 
                mdl.includeModels.size(), String.join(", ", mdl.includeModels));
        }

        // Log body parts for debugging complex models
        if (!mdl.bodyParts.isEmpty()) {
            int bpCount = mdl.bodyParts.size();
            int modelCount = mdl.models.size();
            int meshCount = mdl.meshes.size();
            LOGGER.info("[ModelLoadManager] MDL: {} body parts, {} models, {} meshes, {} bones, {} textures",
                bpCount, modelCount, meshCount, mdl.header.numbones, mdl.header.numtextures);
        }

        Map<Integer, ResourceLocation> meshTextureMap = loadTextures(mdl, materialsDir);

        SourceModelData result = new SourceModelData();
        result.name = mdl.header.name != null ? mdl.header.name : packageDir.getFileName().toString();
        result.modelScale = 1.0f;

        buildMeshes(mdl, vvd, vtx, meshTextureMap, result);

        // Handle include models (composite models)
        if (!mdl.includeModels.isEmpty()) {
            LOGGER.info("[ModelLoadManager] Processing {} include models for composite model",
                mdl.includeModels.size());
            for (String includePath : mdl.includeModels) {
                Path includeDir = resolveIncludeModelPath(packageDir, includePath);
                if (includeDir != null && Files.exists(includeDir)) {
                    try {
                        SourceModelData subData = loadFromDirectory(includeDir);
                        if (subData != null && !subData.meshes.isEmpty()) {
                            result.meshes.addAll(subData.meshes);
                            LOGGER.info("[ModelLoadManager] Merged {} meshes from sub-model: {}",
                                subData.meshes.size(), includePath);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[ModelLoadManager] Failed to load sub-model {}: {}",
                            includePath, e.getMessage());
                    }
                }
            }
        }

        for (SourceModelData.MeshData mesh : result.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                float y = mesh.vertices[i + 1];
                if (y < result.minZ) result.minZ = y;
                if (y > result.maxZ) result.maxZ = y;
            }
        }

        if (result.meshes.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No meshes built from {}", packageDir);
        } else {
            LOGGER.info("[ModelLoadManager] Built {} meshes ({} total triangles, {} total vertices)",
                result.meshes.size(), result.totalTriangles(), result.totalVertices());
        }

        return result;
    }

    private static void buildMeshes(
        MdlParser.ParsedModel mdl,
        VvdParser.ParsedVvd vvd,
        VtxParser.ParsedVtx vtx,
        Map<Integer, ResourceLocation> meshTextureMap,
        SourceModelData result
    ) {
        List<VvdParser.StudioVertexExt> vvdVerts = vvd.vertices;
        if (vvdVerts.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No VVD vertices available");
            return;
        }

        // Source Engine vertex index mapping:
        //   vvdIndex = (model.vertexindex / VERTEX_SIZE) + mesh.vertexoffset + vtx.origMeshVertID
        //
        // The VTX origMeshVertID is an index into the MESH's vertex range (0..mesh.numvertices-1),
        // while mesh.vertexoffset gives the start of this mesh's vertices within the model's
        // VVD range. community python tools use this same formula.

        int meshCounter = 0;
        for (int modelIdx = 0; modelIdx < mdl.models.size(); modelIdx++) {
            MdlParser.StudioModel model = mdl.models.get(modelIdx);
            int vvdBase = model.vertexindex / VVD_VERTEX_SIZE;

            for (int meshLocalIdx = 0; meshLocalIdx < model.nummeshes; meshLocalIdx++) {
                // FIX: Match C++ model_loader.cpp logic - use meshCounter for global indexing
                int globalMeshIdx = meshCounter++;
                int vertexOffset = (globalMeshIdx < mdl.meshes.size()) ?
                    mdl.meshes.get(globalMeshIdx).vertexoffset : 0;

                // FIX: Use Lod 0 triangles for base model building (matches C++ VTX parsing)
                List<VtxParser.VtxTriangle> tris = (globalMeshIdx < vtx.meshTriangles.size()) ?
                    vtx.meshTriangles.get(globalMeshIdx) : new ArrayList<>();

                if (tris.isEmpty()) continue;

                // FIX: Match C++ texture mapping - use global index to match C++
                ResourceLocation texture = meshTextureMap.get(globalMeshIdx);

                List<Float> vertList = new ArrayList<>();
                List<Integer> idxList = new ArrayList<>();
                Map<String, Integer> vertCache = new HashMap<>();

                for (VtxParser.VtxTriangle tri : tris) {
                    int[] vvdIndices = new int[3];
                    vvdIndices[0] = vvdBase + vertexOffset + tri.v0;
                    vvdIndices[1] = vvdBase + vertexOffset + tri.v1;
                    vvdIndices[2] = vvdBase + vertexOffset + tri.v2;

                    for (int vi = 0; vi < 3; vi++) {
                        int vvdIdx = vvdIndices[vi];
                        if (vvdIdx < 0 || vvdIdx >= vvdVerts.size()) {
                            vvdIndices[vi] = -1;
                            continue;
                        }

                        // FIX: Match C++ mesh ID calculation
                        String cacheKey = vvdIdx + "_" + (globalMeshIdx);
                        Integer cached = vertCache.get(cacheKey);
                        if (cached != null) {
                            idxList.add(cached);
                            continue;
                        }

                        VvdParser.StudioVertexExt sv = vvdVerts.get(vvdIdx);
                        float u = sv.u;
                        float v = 1.0f - sv.v;

                        // Source (x=forward, y=left, z=up) → Minecraft (x=right, y=up, z=forward)
                        // mc_x = -src_y, mc_y = src_z, mc_z = src_x
                        // normals: mc_nx = -src_ny, mc_ny = src_nz, mc_nz = src_nx
                        Collections.addAll(vertList, -sv.y, sv.z, sv.x, -sv.ny, sv.nz, sv.nx, u, v);
                        int newIdx = (vertList.size() / 8) - 1;
                        vertCache.put(cacheKey, newIdx);
                        idxList.add(newIdx);
                    }
                }

                if (idxList.size() >= 3) {
                    float[] vertArray = new float[vertList.size()];
                    for (int i = 0; i < vertList.size(); i++) {
                        vertArray[i] = vertList.get(i);
                    }
                    int[] idxArray = new int[idxList.size()];
                    for (int i = 0; i < idxList.size(); i++) {
                        idxArray[i] = idxList.get(i);
                    }

                    boolean translucent = false;
                    if (texture != null) {
                        String texPath = texture.getPath().toLowerCase();
                        translucent = texPath.contains("glass") || texPath.contains("translucent")
                            || texPath.contains("alpha") || texPath.contains("trans");
                    }

                    result.meshes.add(new SourceModelData.MeshData(vertArray, idxArray, texture, translucent));
                }
            }
        }

        if (result.meshes.isEmpty() && vvdVerts.size() >= 3) {
            LOGGER.warn("[ModelLoadManager] No meshes from VTX, building fallback from VVD vertices");
            int maxVerts = Math.min(vvdVerts.size(), 30000);
            List<Float> vertList = new ArrayList<>();
            List<Integer> idxList = new ArrayList<>();
            for (int i = 0; i + 2 < maxVerts; i += 3) {
                for (int j = 0; j < 3; j++) {
                    VvdParser.StudioVertexExt sv = vvdVerts.get(i + j);
                    Collections.addAll(vertList, -sv.y, sv.z, sv.x, -sv.ny, sv.nz, sv.nx, sv.u, 1.0f - sv.v);
                }
                int base = (vertList.size() / 8) - 3;
                idxList.add(base); idxList.add(base + 1); idxList.add(base + 2);
            }
            if (!idxList.isEmpty()) {
                float[] vertArray = new float[vertList.size()];
                for (int i = 0; i < vertList.size(); i++) vertArray[i] = vertList.get(i);
                int[] idxArray = new int[idxList.size()];
                for (int i = 0; i < idxList.size(); i++) idxArray[i] = idxList.get(i);
                result.meshes.add(new SourceModelData.MeshData(vertArray, idxArray, null, false));
            }
        }
    }

    static Path findMaterialsDir(Path packageDir) {
        Path directChild = packageDir.resolve("materials");
        if (Files.exists(directChild) && Files.isDirectory(directChild)) {
            return directChild;
        }
        Path parent = packageDir.getParent();
        while (parent != null) {
            Path candidate = parent.resolve("materials");
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                return candidate;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static Path resolveIncludeModelPath(Path packageDir, String includePath) {
        // Try as relative path from packageDir
        String cleaned = includePath.replace('\\', '/');
        if (cleaned.endsWith(".mdl")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        if (cleaned.startsWith("models/")) {
            cleaned = cleaned.substring("models/".length());
        }

        // Try direct child of packageDir
        Path candidate = packageDir.getParent().resolve(cleaned);
        if (Files.exists(candidate) && Files.isDirectory(candidate)) return candidate;

        // Try same-level as packageDir in models root
        Path modelsRoot = packageDir.getParent();
        if (modelsRoot != null) {
            candidate = modelsRoot.resolve(cleaned);
            if (Files.exists(candidate) && Files.isDirectory(candidate)) return candidate;
        }

        // Try as sibling (same directory level)
        String[] parts = cleaned.split("/");
        for (int i = 0; i <= parts.length; i++) {
            Path partPath = modelsRoot;
            for (int j = 0; j < parts.length; j++) {
                partPath = partPath.resolve(parts[j]);
            }
            if (Files.exists(partPath) && Files.isDirectory(partPath)) return partPath;
            Path stripped = modelsRoot;
            if (i > 0 && i <= parts.length) {
                for (int j = 0; j < parts.length - i; j++) {
                    stripped = stripped.resolve(parts[j]);
                }
            }
            if (Files.exists(stripped) && Files.isDirectory(stripped) && hasAnyModelFile(stripped)) return stripped;
        }

        return null;
    }

    private static boolean hasAnyModelFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase();
                return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".lua")
                    || name.endsWith(".dx90.vtx");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<Integer, ResourceLocation> loadTextures(MdlParser.ParsedModel mdl, Path materialsDir) {
        Map<Integer, ResourceLocation> meshTexMap = new HashMap<>();
        if (materialsDir == null || !Files.exists(materialsDir)) return meshTexMap;

        Map<String, VtfParser.VtfImageData> vtfCache = new HashMap<>();
        Map<String, VmtParser.VmtMaterial> vmtCache = new HashMap<>();

        try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();

            for (Path f : files) {
                String name = f.getFileName().toString().toLowerCase();
                try {
                    if (name.endsWith(".vmt")) {
                        VmtParser.VmtMaterial mat = VmtParser.parse(Files.readAllBytes(f));
                        vmtCache.put(relativePath(materialsDir, f), mat);
                    } else if (name.endsWith(".vtf")) {
                        VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                        vtfCache.put(relativePath(materialsDir, f), vtf);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[ModelLoadManager] Skipping texture file {}: {}", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[ModelLoadManager] Error scanning materials dir", e);
            return meshTexMap;
        }

        // Build cdtexture prefix for grouping textures by subdirectory
        var ref = new Object() {
            String cdPrefix = "";
        };
        if (!mdl.cdTextures.isEmpty()) {
            ref.cdPrefix = mdl.cdTextures.get(0).replace('\\', '/').toLowerCase();
            if (!ref.cdPrefix.endsWith("/")) ref.cdPrefix += "/";
        }

        // Build a map from $basetexture (VMT reference) to VTF key
        Map<String, String> baseTexToVtfKey = new HashMap<>();
        for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
            String bt = e.getValue().getBaseTexture();
            if (bt != null) {
                String btNorm = bt.replace('\\', '/').toLowerCase();
                baseTexToVtfKey.put(btNorm, e.getKey());
            }
        }

        // Build reverse map from VMT path to VTF key
        Map<String, String> vmtPathToVtfKey = new HashMap<>();
        for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
            String bt = e.getValue().getBaseTexture();
            if (bt != null) {
                String btNorm = bt.replace('\\', '/').toLowerCase();
                // The VMT path is typically: materials/<path>.vmt
                // The $basetexture is typically: <path>/<name>
                // The VTF path is: materials/<path>/<name>.vtf
                for (String vtfKey : vtfCache.keySet()) {
                    if (vtfKey.equalsIgnoreCase(btNorm)) {
                        vmtPathToVtfKey.put(e.getKey(), vtfKey);
                        break;
                    }
                }
            }
        }

        // Build ordered list of VTF keys within cdtexture directory (fallback ordering)
        List<String> orderedVtfKeys = vtfCache.keySet().stream()
            .filter(k -> k.toLowerCase().startsWith(ref.cdPrefix))
            .sorted()
            .collect(Collectors.toList());
        LOGGER.info("[ModelLoadManager] Found {} VTFs under cdprefix '{}'", orderedVtfKeys.size(), ref.cdPrefix);

        // For each mesh, build a list of candidate texture names based on texture index
        for (int meshIdx = 0; meshIdx < mdl.meshes.size(); meshIdx++) {
            ResourceLocation texLoc = resolveMeshTexture(
                mdl, meshIdx, vmtCache, vtfCache,
                    ref.cdPrefix, orderedVtfKeys, baseTexToVtfKey,
                vmtPathToVtfKey
            );
            if (texLoc != null) {
                meshTexMap.put(meshIdx, texLoc);
            }
        }

        return meshTexMap;
    }

    private static String relativePath(Path base, Path full) {
        String rel = base.relativize(full).toString().replace('\\', '/');
        if (rel.endsWith(".vmt") || rel.endsWith(".vtf")) {
            rel = rel.substring(0, rel.length() - 4);
        }
        return rel;
    }

    private static ResourceLocation resolveMeshTexture(
        MdlParser.ParsedModel mdl, int meshIdx,
        Map<String, VmtParser.VmtMaterial> vmtCache,
        Map<String, VtfParser.VtfImageData> vtfCache,
        String cdPrefix,
        List<String> orderedVtfKeys,
        Map<String, String> baseTexToVmtKey,
        Map<String, String> vmtPathToVtfKey
    ) {
        if (meshIdx >= mdl.meshes.size()) return null;

        int materialIdx = mdl.meshes.get(meshIdx).material;
        int texIndex = materialIdx;

        if (!mdl.skinTable.isEmpty() && mdl.header.numskinref > 0) {
            int wrapped = materialIdx >= 0 ? materialIdx % mdl.header.numskinref : 0;
            if (wrapped < mdl.skinTable.size()) {
                texIndex = mdl.skinTable.get(wrapped);
            }
        }

        // Get texture name from MDL texture array
        String texName = "";
        if (texIndex >= 0 && texIndex < mdl.textures.size()) {
            String name = mdl.textures.get(texIndex).name;
            if (name != null && name.length() > 2) {
                texName = name;
            }
        }

        // Strategy 1: Match by building materials/<cdtexture>/<texName>.vmt
        if (!texName.isEmpty()) {
            // Strip file extension if present
            String cleanTexName = texName;
            if (cleanTexName.endsWith(".vtf") || cleanTexName.endsWith(".vmt")) {
                cleanTexName = cleanTexName.substring(0, cleanTexName.length() - 4);
            }
            String normalized = cleanTexName.replace('\\', '/').toLowerCase();

            // Try exact match in VMT cache
            for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
                String bt = e.getValue().getBaseTexture();
                if (bt != null) {
                    String btNorm = bt.replace('\\', '/').toLowerCase();
                    // Try matching the full normalized name
                    if (btNorm.equals(normalized) || btNorm.endsWith("/" + normalized)) {
                        String vtfKey = vmtPathToVtfKey.get(e.getKey());
                        if (vtfKey == null) vtfKey = findVtfForBaseTexture(btNorm, vtfCache);
                        if (vtfKey != null) {
                            VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
                            if (vtf != null && vtf.image != null) {
                                return registerTexture(vtfKey, vtf.image);
                            }
                        }
                    }
                }
            }

            // Try matching as a VTF key directly
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String key = e.getKey().toLowerCase();
                if (key.equals(normalized) || key.endsWith("/" + normalized)) {
                    return registerTexture(e.getKey(), e.getValue().image);
                }
            }

            // Try matching by filename only (last path component)
            String simpleName = normalized.contains("/") ?
                normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String kSimple = e.getKey().toLowerCase();
                kSimple = kSimple.contains("/") ? kSimple.substring(kSimple.lastIndexOf('/') + 1) : kSimple;
                if (kSimple.equals(simpleName)) {
                    return registerTexture(e.getKey(), e.getValue().image);
                }
            }

            // Try fuzzy match: texture name appears within VTF path
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String key = e.getKey().toLowerCase();
                if (key.contains(normalized.replace("/", "_"))
                    || key.contains(normalized.replace("/", ""))) {
                    return registerTexture(e.getKey(), e.getValue().image);
                }
            }
        }

        // Strategy 2: Use texture index to select Nth VTF in the cdtexture directory
        if (!cdPrefix.isEmpty() && !orderedVtfKeys.isEmpty()) {
            int idx = texIndex >= 0 ? texIndex % orderedVtfKeys.size() : meshIdx % orderedVtfKeys.size();
            String vtfKey = orderedVtfKeys.get(idx);
            VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
            if (vtf != null && vtf.image != null) {
                LOGGER.debug("[ModelLoadManager] Mesh {} texIdx {} → VTF[{}] = {}", meshIdx, texIndex, idx, vtfKey);
                return registerTexture(vtfKey, vtf.image);
            }
        }

        // Strategy 3: Match by VMT path mapping (pair VMT with same-named VTF)
        if (!vmtCache.isEmpty()) {
            // For meshIdx, try to find the corresponding VMT by texture index
            String texNameLower = texName.toLowerCase().replace('\\', '/');
            for (Map.Entry<String, String> entry : vmtPathToVtfKey.entrySet()) {
                String vmtPath = entry.getKey().toLowerCase();
                if (texNameLower.endsWith("/" + vmtPath) || texNameLower.equals(vmtPath)) {
                    VtfParser.VtfImageData vtf = vtfCache.get(entry.getValue());
                    if (vtf != null && vtf.image != null) {
                        return registerTexture(entry.getValue(), vtf.image);
                    }
                }
            }
        }

        // Strategy 4: Fallback - use mesh index % count
        if (!orderedVtfKeys.isEmpty()) {
            int idx = meshIdx % orderedVtfKeys.size();
            VtfParser.VtfImageData vtf = vtfCache.get(orderedVtfKeys.get(idx));
            if (vtf != null && vtf.image != null) {
                return registerTexture(orderedVtfKeys.get(idx), vtf.image);
            }
        }

        return null;
    }

    private static String findVtfForBaseTexture(String baseTexPath, Map<String, VtfParser.VtfImageData> vtfCache) {
        // Exact match first
        if (vtfCache.containsKey(baseTexPath)) return baseTexPath;
        // Try without last path segment
        for (String key : vtfCache.keySet()) {
            if (key.equalsIgnoreCase(baseTexPath)) return key;
        }
        return null;
    }

    private static ResourceLocation registerTexture(String key, BufferedImage image) {
        String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_').replace('.', '_');
        ResourceLocation existing = textureRegistry.get(regKey);
        if (existing != null) return existing;

        ResourceLocation loc = ResourceLocation.parse("transferstation_whimsicalideas:textures/generated/" + regKey);

        try {
            NativeImage nativeImage = bufferedImageToNativeImage(image);
            DynamicTexture dynamicTex = new DynamicTexture(nativeImage);
            Minecraft.getInstance().getTextureManager().register(loc, dynamicTex);
            textureRegistry.put(regKey, loc);
            LOGGER.debug("[ModelLoadManager] Registered texture: {} ({}x{})", loc, image.getWidth(), image.getHeight());
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to register texture {}: {}", regKey, e.getMessage());
        }

        return loc;
    }

    private static NativeImage bufferedImageToNativeImage(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = pixels[y * w + x];
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                nativeImage.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }

        return nativeImage;
    }

public static void clearTextureRegistry() {
        textureRegistry.clear();
    }
}
