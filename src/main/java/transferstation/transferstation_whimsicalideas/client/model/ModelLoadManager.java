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
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ModelLoadManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VERTEX_FLOAT_SIZE = 8;
    private static final int VVD_VERTEX_SIZE = 48;
    // Bump this whenever parsing logic changes to invalidate old caches
    private static final int CACHE_FORMAT_VERSION = 14;

    private static final Map<String, SourceModelData> modelCache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SourceModelData> eldest) {
            return size() > 64;
        }
    });

    private static final TextureColorResolver colorResolver = new TextureColorResolver();
    private static Path cacheDir = null;

    public static void setCacheDir(Path dir) {
        cacheDir = dir;
    }

    public static SourceModelData getCached(String key) {
        return modelCache.get(key);
    }

    public static void clearCache() {
        modelCache.clear();
        colorResolver.clearAll();
    }

    public static SourceModelData loadModel(Path packageDir) {
        String cacheKey = packageDir.toAbsolutePath().toString();
        SourceModelData cached = modelCache.get(cacheKey);
        if (cached != null) return cached;

        colorResolver.trimStale();

        // Try disk cache first
        SourceModelData diskData = loadFromDiskCache(packageDir);
        if (diskData != null) {
            modelCache.put(cacheKey, diskData);
            LOGGER.info("[ModelLoadManager] Restored model from disk cache: {} meshes", diskData.meshes.size());
            reRegisterTexturesFromCache(packageDir, diskData);
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
                int cacheVersion = dis.readInt();
                if (cacheVersion != CACHE_FORMAT_VERSION) {
                    LOGGER.info("[ModelLoadManager] Disk cache version mismatch (cached={}, current={}), discarding",
                        cacheVersion, CACHE_FORMAT_VERSION);
                    try { Files.deleteIfExists(cacheFile); } catch (IOException ignored) {}
                    return null;
                }
                long cachedModTime = dis.readLong();
                if (cachedModTime != currentModTime) {
                    LOGGER.debug("[ModelLoadManager] Disk cache stale for {}", packageDir);
                    return null;
                }

                SourceModelData data = new SourceModelData();
                data.name = dis.readUTF();
                data.minX = dis.readFloat();
                data.maxX = dis.readFloat();
                data.minY = dis.readFloat();
                data.maxY = dis.readFloat();
                data.minZ = dis.readFloat();
                data.maxZ = dis.readFloat();
                data.modelScale = dis.readFloat();

                int bpCount = dis.readInt();
                for (int b = 0; b < bpCount; b++) {
                    String bpName = dis.readUTF();
                    int numModels = dis.readInt();
                    int baseIndex = dis.readInt();
                    SourceModelData.BodyPartInfo bpInfo = new SourceModelData.BodyPartInfo(bpName, numModels, baseIndex);
                    for (int m = 0; m < numModels; m++) {
                        bpInfo.modelNames.add(dis.readUTF());
                    }
                    data.bodyParts.add(bpInfo);
                }

                data.numSkinRef = dis.readInt();
                data.numSkinFamilies = dis.readInt();
                int skinTableSize = dis.readInt();
                for (int s = 0; s < skinTableSize; s++) {
                    data.skinTable.add(dis.readInt());
                }

                int meshCount = dis.readInt();
                for (int m = 0; m < meshCount; m++) {
                    int vertCount = dis.readInt();
                    float[] verts = new float[vertCount];
                    for (int i = 0; i < vertCount; i++) verts[i] = dis.readFloat();

                    int idxCount = dis.readInt();
                    int[] indices = new int[idxCount];
                    for (int i = 0; i < idxCount; i++) indices[i] = dis.readInt();

                    boolean translucent = dis.readBoolean();
                    boolean alphaTest = dis.readBoolean();
                    boolean noCull = dis.readBoolean();

                    String txNamespace = dis.readBoolean() ? dis.readUTF() : null;
                    String txPath = dis.readBoolean() ? dis.readUTF() : null;
                    ResourceLocation texture = (txNamespace != null)
                        ? ResourceLocation.parse(txNamespace + ":" + txPath)
                        : null;

                    int bodyPartIdx = dis.readInt();
                    int modelIdx = dis.readInt();
                    int materialIdx = dis.readInt();

                    String vtfKey = dis.readBoolean() ? dis.readUTF() : null;

                    float[] colorTint = null;
                    if (dis.readBoolean()) {
                        colorTint = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat(), dis.readFloat()};
                    }

                    data.meshes.add(new SourceModelData.MeshData(
                        verts, indices, texture, translucent, alphaTest, noCull,
                        bodyPartIdx, modelIdx, materialIdx, vtfKey, colorTint));
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

    private static void reRegisterTexturesFromCache(Path packageDir, SourceModelData data) {
        if (data == null || data.meshes.isEmpty()) return;
        Path materialsDir = findMaterialsDir(packageDir);
        if (materialsDir == null || !Files.exists(materialsDir)) {
            LOGGER.warn("[ModelLoadManager] No materials dir for cached model, textures may be missing: {}", packageDir);
            return;
        }

        Map<String, VtfParser.VtfImageData> vtfDataMap = new HashMap<>();
        TextureColorResolver.TextureParseStateTracker tracker =
            new TextureColorResolver.TextureParseStateTracker(0);

        try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".vtf"))
                .toList();

            tracker = new TextureColorResolver.TextureParseStateTracker(files.size());

            for (Path f : files) {
                String key = relativePath(materialsDir, f);
                String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_').replace('.', '_').toLowerCase(Locale.ROOT);
                try {
                    VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                    if (vtf != null && vtf.image != null) {
                        vtfDataMap.put(regKey, vtf);
                        if (colorResolver.getRegistered(regKey) == null) {
                            ResourceLocation loc = registerTexture(key, vtf.image);
                            colorResolver.markComplete(regKey, loc, extractCenterPixelColor(vtf.image), false, false, false);
                            tracker.incrementResolved();
                        }
                    } else {
                        colorResolver.markFailed(regKey, "VTF parse returned null image");
                        tracker.incrementFailed();
                    }
                } catch (Exception e) {
                    colorResolver.markFailed(regKey, e.toString());
                    tracker.incrementFailed();
                    LOGGER.debug("[ModelLoadManager] Skipping VTF for re-register: {} - {}", f.getFileName(), e.getMessage());
                }
            }
            LOGGER.info("[ModelLoadManager] Scanned {} VTFs from materials dir (registry had {} entries) - {}",
                vtfDataMap.size(), colorResolver.getAllEntries().size(), tracker);
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to scan textures for cached model: {}", packageDir, e);
        }

        // Re-register textures using persisted vtfKey from mesh data
        int fixedCount = 0;
        int nullCount = 0;
        int persistedCount = 0;
        for (SourceModelData.MeshData mesh : data.meshes) {
            if (mesh.texture == null) {
                nullCount++;
                continue;
            }
            String texPath = mesh.texture.getPath();
            if (!texPath.startsWith("textures/generated/")) continue;
            String regKey = texPath.substring("textures/generated/".length());
            if (colorResolver.isRegistered(regKey)) continue;

            // Use persisted vtfKey if available (matches the key used during original resolution)
            String lookupKey = mesh.vtfKey;
            VtfParser.VtfImageData vtf = null;

            if (lookupKey != null) {
                // Build regKey from the persisted vtfKey (same formula as registerTexture)
                String persistedRegKey = "gmod_" + lookupKey.replace('/', '_').replace('\\', '_')
                    .replace('.', '_').toLowerCase(Locale.ROOT);
                vtf = vtfDataMap.get(persistedRegKey);
                if (vtf != null && vtf.image != null) {
                    registerTexture(lookupKey, vtf.image);
                    persistedCount++;
                    continue;
                }
            }

            // Fallback: try to find VTF by matching regKey suffix against vtfDataMap keys
            String core = regKey.startsWith("gmod_") ? regKey.substring(5) : regKey;
            for (Map.Entry<String, VtfParser.VtfImageData> entry : vtfDataMap.entrySet()) {
                String vtfKey = entry.getKey();
                String vtfCore = vtfKey.startsWith("gmod_") ? vtfKey.substring(5) : vtfKey;
                if (vtfCore.endsWith("_" + core) || vtfCore.equals(core)) {
                    vtf = entry.getValue();
                    break;
                }
            }

            if (vtf != null && vtf.image != null) {
                String key = regKey.startsWith("gmod_") ? regKey.substring(5).replace('_', '/') : regKey.replace('_', '/');
                registerTexture(key, vtf.image);
                fixedCount++;
            }
        }
        if (fixedCount > 0 || nullCount > 0 || persistedCount > 0) {
            LOGGER.info("[ModelLoadManager] Texture check for {}: {} meshes had no texture, {} restored from persisted keys, {} fixed via fallback",
                packageDir.getFileName(), nullCount, persistedCount, fixedCount);
        }

        vtfDataMap.clear();

        var finalStats = colorResolver.getStatistics();
        if (finalStats.hasFailures()) {
            LOGGER.warn("[ModelLoadManager] Texture parse state for cached model: {}", finalStats);
        }
    }

    private static Path findMdlFile(Path packageDir) {
        try (Stream<Path> files = Files.walk(packageDir, 4)) {
            return files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".mdl"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
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
                dos.writeInt(CACHE_FORMAT_VERSION);
                dos.writeLong(modTime);
                dos.writeUTF(data.name != null ? data.name : "");
                dos.writeFloat(data.minX);
                dos.writeFloat(data.maxX);
                dos.writeFloat(data.minY);
                dos.writeFloat(data.maxY);
                dos.writeFloat(data.minZ);
                dos.writeFloat(data.maxZ);
                dos.writeFloat(data.modelScale);

                dos.writeInt(data.bodyParts.size());
                for (SourceModelData.BodyPartInfo bpInfo : data.bodyParts) {
                    dos.writeUTF(bpInfo.name != null ? bpInfo.name : "");
                    dos.writeInt(bpInfo.numModels);
                    dos.writeInt(bpInfo.baseIndex);
                    for (String modelName : bpInfo.modelNames) {
                        dos.writeUTF(modelName != null ? modelName : "");
                    }
                }

                dos.writeInt(data.numSkinRef);
                dos.writeInt(data.numSkinFamilies);
                dos.writeInt(data.skinTable.size());
                for (int skinVal : data.skinTable) {
                    dos.writeInt(skinVal);
                }

                dos.writeInt(data.meshes.size());
                for (SourceModelData.MeshData mesh : data.meshes) {
                    dos.writeInt(mesh.vertices.length);
                    for (float v : mesh.vertices) dos.writeFloat(v);

                    dos.writeInt(mesh.indices.length);
                    for (int idx : mesh.indices) dos.writeInt(idx);

                    dos.writeBoolean(mesh.translucent);
                    dos.writeBoolean(mesh.alphaTest);
                    dos.writeBoolean(mesh.noCull);

                    if (mesh.texture != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.texture.getNamespace());
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.texture.getPath());
                    } else {
                        dos.writeBoolean(false);
                        dos.writeBoolean(false);
                    }

                    dos.writeInt(mesh.bodyPartIndex);
                    dos.writeInt(mesh.modelIndex);
                    dos.writeInt(mesh.materialIndex);

                    if (mesh.vtfKey != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.vtfKey);
                    } else {
                        dos.writeBoolean(false);
                    }

                    if (mesh.colorTint != null && mesh.colorTint.length >= 3) {
                        dos.writeBoolean(true);
                        for (int c = 0; c < mesh.colorTint.length; c++) {
                            dos.writeFloat(mesh.colorTint[c]);
                        }
                    } else {
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
        colorResolver.clearAll();
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
            for (int i = 0; i < mdl.bodyParts.size(); i++) {
                MdlParser.StudioBodyPart bp = mdl.bodyParts.get(i);
                LOGGER.info("[ModelLoadManager]   BodyPart[{}]: '{}' numModels={} baseIndex={}",
                    i, bp.name, bp.nummodels, bp.baseIndex);
            }
            LOGGER.info("[ModelLoadManager] Skin table: {} entries (numSkinRef={}, numSkinFamilies={})",
                mdl.skinTable.size(), mdl.header.numskinref, mdl.header.numskinfamilies);
        }

        // Scan Lua files for material hints
        List<String> luaMaterialHints = new ArrayList<>();
        List<String> luaCdMaterialsHints = new ArrayList<>();
        scanLuaForMaterialHints(packageDir, luaMaterialHints, luaCdMaterialsHints);

        // Find all possible materials directories
        List<Path> allMaterialsDirs = findAllMaterialsDirs(packageDir);
        Path primaryMaterialsDir = allMaterialsDirs.isEmpty() ? null : allMaterialsDirs.get(0);

        if (allMaterialsDirs.size() > 1) {
            LOGGER.info("[ModelLoadManager] Found {} materials directories: {}",
                allMaterialsDirs.size(), allMaterialsDirs);
        }

        // Merge cdTextures from MDL with Lua hints
        List<String> allCdPrefixes = new ArrayList<>();
        for (String cdTex : mdl.cdTextures) {
            String prefix = cdTex.replace('\\', '/').toLowerCase();
            if (!prefix.endsWith("/")) prefix += "/";
            allCdPrefixes.add(prefix);
        }
        for (String hint : luaCdMaterialsHints) {
            String prefix = hint.replace('\\', '/').toLowerCase();
            if (!prefix.endsWith("/")) prefix += "/";
            if (!allCdPrefixes.contains(prefix)) {
                allCdPrefixes.add(prefix);
            }
        }

        Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap =
            loadTextures(mdl, primaryMaterialsDir, allMaterialsDirs, luaMaterialHints, allCdPrefixes);

        SourceModelData result = new SourceModelData();
        result.name = mdl.header.name != null ? mdl.header.name : packageDir.getFileName().toString();
        result.modelScale = 1.0f;

        for (MdlParser.StudioBodyPart bp : mdl.bodyParts) {
            SourceModelData.BodyPartInfo info = new SourceModelData.BodyPartInfo(
                bp.name, bp.nummodels, bp.baseIndex);
            for (MdlParser.StudioModel m : mdl.models) {
                if (m.bodypartIndex == mdl.bodyParts.indexOf(bp)) {
                    info.modelNames.add(m.name);
                }
            }
            result.bodyParts.add(info);
        }

        result.numSkinRef = mdl.header.numskinref;
        result.numSkinFamilies = mdl.header.numskinfamilies;
        result.skinTable.addAll(mdl.skinTable);
        result.currentSkinFamily = 0;

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
                float x = mesh.vertices[i];
                float y = mesh.vertices[i + 1];
                float z = mesh.vertices[i + 2];
                if (x < result.minX) result.minX = x;
                if (x > result.maxX) result.maxX = x;
                if (y < result.minY) result.minY = y;
                if (y > result.maxY) result.maxY = y;
                if (z < result.minZ) result.minZ = z;
                if (z > result.maxZ) result.maxZ = z;
            }
        }

        if (result.minX < Float.MAX_VALUE) {
            float sizeX = result.maxX - result.minX;
            float sizeY = result.maxY - result.minY;
            float sizeZ = result.maxZ - result.minZ;
            float maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
            if (maxDim > 0.001f) {
                result.modelScale = 1.8f / maxDim;
            }
            LOGGER.info("[ModelLoadManager] Bounding box: X=[{},{}] Y=[{},{}] Z=[{},{}] size={}x{}x{} autoScale={}",
                result.minX, result.maxX, result.minY, result.maxY, result.minZ, result.maxZ,
                sizeX, sizeY, sizeZ, result.modelScale);
        }

        if (result.meshes.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No meshes built from {}", packageDir);
        } else {
            LOGGER.info("[ModelLoadManager] Built {} meshes ({} total triangles, {} total vertices)",
                result.meshes.size(), result.totalTriangles(), result.totalVertices());
        }

        return result;
    }

    /**
     * Scan Lua files in and around the package directory for material/texture hints.
     */
    private static void scanLuaForMaterialHints(Path packageDir, List<String> materialHints, List<String> cdMaterialsHints) {
        // Search for Lua files in packageDir and up to 3 parent levels
        Path searchDir = packageDir;
        int depth = 0;
        while (searchDir != null && depth < 4) {
            try (Stream<Path> walk = Files.walk(searchDir, 3)) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".lua"))
                    .forEach(luaFile -> {
                        try {
                            List<String> lines = Files.readAllLines(luaFile);
                            for (String rawLine : lines) {
                                String line = rawLine.trim();
                                if (line.startsWith("--")) continue;
                                String lower = line.toLowerCase();

                                // Extract material paths: ENT.Material = "...", Material = "...", material = "..."
                                String[] matPatterns = {".material =", ".material=", "material =", "material="};
                                for (String pattern : matPatterns) {
                                    int idx = lower.indexOf(pattern);
                                    if (idx >= 0) {
                                        String rest = line.substring(idx + pattern.length()).trim();
                                        String val = extractLuaQuotedString(rest);
                                        if (val != null && !val.isEmpty() && !val.toLowerCase().endsWith(".mdl")) {
                                            materialHints.add(val.replace('\\', '/'));
                                        }
                                    }
                                }

                                // Extract $cdmaterials hints
                                int cdIdx = lower.indexOf("$cdmaterials");
                                if (cdIdx >= 0) {
                                    String rest = line.substring(cdIdx + 12).trim();
                                    String val = extractLuaQuotedString(rest);
                                    if (val != null && !val.isEmpty()) {
                                        cdMaterialsHints.add(val.replace('\\', '/'));
                                    }
                                }
                                int cdIdx2 = lower.indexOf("cdmaterials");
                                if (cdIdx2 >= 0 && cdIdx2 != cdIdx) {
                                    String after = lower.substring(cdIdx2 + 11);
                                    if (after.startsWith(" ") || after.startsWith("=")) {
                                        int eqIdx = after.indexOf("=");
                                        if (eqIdx >= 0) {
                                            String rest = line.substring(cdIdx2 + 11 + eqIdx + 1).trim();
                                            String val = extractLuaQuotedString(rest);
                                            if (val != null && !val.isEmpty()) {
                                                cdMaterialsHints.add(val.replace('\\', '/'));
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (IOException ignored) {}
                    });
            } catch (IOException ignored) {}
            searchDir = searchDir.getParent();
            depth++;
        }

        if (!materialHints.isEmpty()) {
            LOGGER.info("[ModelLoadManager] Lua material hints: {}", materialHints);
        }
        if (!cdMaterialsHints.isEmpty()) {
            LOGGER.info("[ModelLoadManager] Lua $cdmaterials hints: {}", cdMaterialsHints);
        }
    }

    private static String extractLuaQuotedString(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("\"")) {
            int end = s.indexOf('"', 1);
            if (end > 0) return s.substring(1, end);
            return s.substring(1);
        }
        if (s.startsWith("'")) {
            int end = s.indexOf('\'', 1);
            if (end > 0) return s.substring(1, end);
            return s.substring(1);
        }
        if (s.startsWith("[[")) {
            int end = s.indexOf("]]", 2);
            if (end > 0) return s.substring(2, end);
            return s.substring(2);
        }
        return null;
    }

    private static void buildMeshes(
        MdlParser.ParsedModel mdl,
        VvdParser.ParsedVvd vvd,
        VtxParser.ParsedVtx vtx,
        Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap,
        SourceModelData result
    ) {
        List<VvdParser.StudioVertexExt> vvdVerts = vvd.vertices;
        if (vvdVerts.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No VVD vertices available");
            return;
        }

        int vtxMeshCount = vtx.meshTriangles.size();
        int mdlMeshCount = mdl.meshes.size();
        int mdlModelCount = mdl.models.size();
        LOGGER.info("[ModelLoadManager] buildMeshes: VVD vertices={}, VTX meshes={}, MDL meshes={}, MDL models={}",
            vvdVerts.size(), vtxMeshCount, mdlMeshCount, mdlModelCount);

        if (vtxMeshCount != mdlMeshCount) {
            LOGGER.warn("[ModelLoadManager] MESH COUNT MISMATCH: VTX={} vs MDL={} - triangles may be misaligned!",
                vtxMeshCount, mdlMeshCount);
        }

        int meshCounter = 0;
        for (int modelIdx = 0; modelIdx < mdlModelCount; modelIdx++) {
            MdlParser.StudioModel model = mdl.models.get(modelIdx);
            int vvdBase = model.vertexindex / VVD_VERTEX_SIZE;

            for (int meshLocalIdx = 0; meshLocalIdx < model.nummeshes; meshLocalIdx++) {
                int globalMeshIdx = meshCounter++;
                int vertexOffset = (globalMeshIdx < mdlMeshCount) ?
                    mdl.meshes.get(globalMeshIdx).vertexoffset : 0;
                int meshNumVertices = (globalMeshIdx < mdlMeshCount) ?
                    mdl.meshes.get(globalMeshIdx).numvertices : 0;

                List<VtxParser.VtxTriangle> tris = (globalMeshIdx < vtxMeshCount) ?
                    vtx.meshTriangles.get(globalMeshIdx) : new ArrayList<>();

                if (tris.isEmpty()) continue;

                SourceModelData.MeshTextureInfo texInfo = meshTextureMap.get(globalMeshIdx);

                if (meshLocalIdx == 0) {
                    LOGGER.debug("[ModelLoadManager] Model[{}] vvdBase={}, numMeshes={}",
                        modelIdx, vvdBase, model.nummeshes);
                }
                LOGGER.debug("[ModelLoadManager] Mesh[global={} model={}.{}] vvdBase={} vertOffset={} meshNumVerts={} tris={}",
                    globalMeshIdx, modelIdx, meshLocalIdx, vvdBase, vertexOffset, meshNumVertices, tris.size());

                // Validate VTX vertex IDs against MDL mesh vertex count
                if (meshNumVertices > 0) {
                    int maxVtxVertId = 0;
                    for (VtxParser.VtxTriangle tri : tris) {
                        maxVtxVertId = Math.max(maxVtxVertId, Math.max(tri.v0, Math.max(tri.v1, tri.v2)));
                    }
                    if (maxVtxVertId >= meshNumVertices) {
                        LOGGER.warn("[ModelLoadManager] Mesh[{}] VTX origMeshVertID {} exceeds MDL mesh.numvertices {}",
                            globalMeshIdx, maxVtxVertId, meshNumVertices);
                    }
                    // Also validate that vvdBase + vertexOffset + maxVtxVertId is within VVD range
                    int maxVvdIdx = vvdBase + vertexOffset + maxVtxVertId;
                    if (maxVvdIdx >= vvdVerts.size()) {
                        LOGGER.warn("[ModelLoadManager] Mesh[{}] Max VVD index {} exceeds VVD vertex count {} (vvdBase={} vertOffset={} maxVtxId={})",
                            globalMeshIdx, maxVvdIdx, vvdVerts.size(), vvdBase, vertexOffset, maxVtxVertId);
                    }
                }

                List<Float> vertList = new ArrayList<>();
                List<Integer> idxList = new ArrayList<>();
                Map<Integer, Integer> vertCache = new HashMap<>();
                int oobCount = 0;
                int maxVvdIdx = -1;

                for (VtxParser.VtxTriangle tri : tris) {
                    int vvdIdx0 = vvdBase + vertexOffset + tri.v0;
                    int vvdIdx1 = vvdBase + vertexOffset + tri.v1;
                    int vvdIdx2 = vvdBase + vertexOffset + tri.v2;
                    maxVvdIdx = Math.max(maxVvdIdx, Math.max(vvdIdx0, Math.max(vvdIdx1, vvdIdx2)));

                    if (vvdIdx0 < 0 || vvdIdx0 >= vvdVerts.size() ||
                        vvdIdx1 < 0 || vvdIdx1 >= vvdVerts.size() ||
                        vvdIdx2 < 0 || vvdIdx2 >= vvdVerts.size()) {
                        oobCount++;
                        continue;
                    }

                    int[] vvdIndices = {vvdIdx0, vvdIdx1, vvdIdx2};

                    for (int vi = 0; vi < 3; vi++) {
                        int vvdIdx = vvdIndices[vi];

                        Integer cached = vertCache.get(vvdIdx);
                        if (cached != null) {
                            idxList.add(cached);
                            continue;
                        }

                        VvdParser.StudioVertexExt sv = vvdVerts.get(vvdIdx);
                        float u = sv.u;
                        float v = 1.0f - sv.v;

                        Collections.addAll(vertList, -sv.y, sv.z, sv.x, -sv.ny, sv.nz, sv.nx, u, v);
                        int newIdx = (vertList.size() / 8) - 1;
                        vertCache.put(vvdIdx, newIdx);
                        idxList.add(newIdx);
                    }
                }

                if (oobCount > 0) {
                    LOGGER.warn("[ModelLoadManager] Mesh[{}] {} out-of-bounds VVD indices (vvdVerts.size={} maxIdx={})",
                        globalMeshIdx, oobCount, vvdVerts.size(), maxVvdIdx);
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
                    boolean alphaTest = false;
                    boolean noCull = false;
                    ResourceLocation texture = null;
                    String vtfKey = null;
                    float[] colorTint = null;
                    if (texInfo != null) {
                        texture = texInfo.texture;
                        translucent = texInfo.translucent;
                        alphaTest = texInfo.alphaTest;
                        noCull = texInfo.noCull;
                        vtfKey = texInfo.vtfKey;
                        colorTint = texInfo.colorTint;
                    }

                    int materialIdx = (globalMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(globalMeshIdx).material : -1;

                    result.meshes.add(new SourceModelData.MeshData(
                        vertArray, idxArray, texture, translucent, alphaTest, noCull,
                        model.bodypartIndex, modelIdx, materialIdx, vtfKey, colorTint));
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

    /**
     * Find ALL possible materials directories that could contain textures for this model.
     * Searches more aggressively than findMaterialsDir:
     * 1. Direct child 'materials/' of packageDir
     * 2. Ancestor 'materials/' directories (upward walk)
     * 3. Sibling 'materials/' directories (same parent level)
     * 4. Addon root 'materials/' (walks up to 'addons/' boundary)
     */
    static List<Path> findAllMaterialsDirs(Path packageDir) {
        List<Path> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Direct child
        Path directChild = packageDir.resolve("materials");
        if (Files.exists(directChild) && Files.isDirectory(directChild)) {
            String key = directChild.toAbsolutePath().toString();
            if (seen.add(key)) results.add(directChild);
        }

        // 2. Ancestor walk (up to 10 levels)
        Path parent = packageDir.getParent();
        int depth = 0;
        while (parent != null && depth < 10) {
            Path candidate = parent.resolve("materials");
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                String key = candidate.toAbsolutePath().toString();
                if (seen.add(key)) results.add(candidate);
            }
            parent = parent.getParent();
            depth++;
        }

        // 3. Sibling directories - look for 'materials/' in sibling folders
        Path parentOfPackage = packageDir.getParent();
        if (parentOfPackage != null) {
            try (Stream<Path> siblings = Files.list(parentOfPackage)) {
                siblings.filter(Files::isDirectory)
                    .filter(s -> !s.equals(packageDir))
                    .forEach(sibling -> {
                        Path matDir = sibling.resolve("materials");
                        if (Files.exists(matDir) && Files.isDirectory(matDir)) {
                            String key = matDir.toAbsolutePath().toString();
                            if (seen.add(key)) {
                                synchronized (results) { results.add(matDir); }
                            }
                        }
                    });
            } catch (IOException ignored) {}
        }

        // 4. Walk up to find addon root (directory containing 'addons' in path or 'lua/' subdirectory)
        Path walkUp = packageDir;
        int walkDepth = 0;
        while (walkUp != null && walkDepth < 15) {
            boolean isAddonRoot = Files.exists(walkUp.resolve("lua"))
                || Files.exists(walkUp.resolve("addon.json"))
                || Files.exists(walkUp.resolve("workshop.txt"));
            if (isAddonRoot) {
                Path matDir = walkUp.resolve("materials");
                if (Files.exists(matDir) && Files.isDirectory(matDir)) {
                    String key = matDir.toAbsolutePath().toString();
                    if (seen.add(key)) results.add(matDir);
                }
                break;
            }
            walkUp = walkUp.getParent();
            walkDepth++;
        }

        return results;
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
                return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx");
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<Integer, SourceModelData.MeshTextureInfo> loadTextures(
            MdlParser.ParsedModel mdl, Path primaryMaterialsDir,
            List<Path> allMaterialsDirs, List<String> luaMaterialHints,
            List<String> cdPrefixes) {
        Map<Integer, SourceModelData.MeshTextureInfo> meshTexMap = new HashMap<>();

        Map<String, VtfParser.VtfImageData> vtfCache = new HashMap<>();
        Map<String, VmtParser.VmtMaterial> vmtCache = new HashMap<>();

        int vmtCount = 0, vtfCount = 0, vmtFailCount = 0, vtfFailCount = 0;

        // Scan ALL materials directories for VMT/VTF files
        List<Path> dirsToScan = allMaterialsDirs != null && !allMaterialsDirs.isEmpty()
            ? allMaterialsDirs : (primaryMaterialsDir != null ? List.of(primaryMaterialsDir) : List.of());

        for (Path materialsDir : dirsToScan) {
            if (materialsDir == null || !Files.exists(materialsDir)) continue;
            try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                LOGGER.info("[ModelLoadManager] Found {} total files in materials dir: {}", files.size(), materialsDir);

                for (Path f : files) {
                    String name = f.getFileName().toString().toLowerCase();
                    try {
                        if (name.endsWith(".vmt")) {
                            vmtCount++;
                            VmtParser.VmtMaterial mat = VmtParser.parse(Files.readAllBytes(f));
                            String key = relativePath(materialsDir, f);
                            // Don't overwrite existing entries (primary dir takes precedence)
                            if (!vmtCache.containsKey(key)) {
                                vmtCache.put(key, mat);
                            }
                        } else if (name.endsWith(".vtf")) {
                            vtfCount++;
                            VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                            String key = relativePath(materialsDir, f);
                            if (!vtfCache.containsKey(key)) {
                                vtfCache.put(key, vtf);
                            }
                        }
                    } catch (Exception e) {
                        if (name.endsWith(".vmt")) vmtFailCount++;
                        else if (name.endsWith(".vtf")) vtfFailCount++;
                        LOGGER.debug("[ModelLoadManager] Failed to parse texture file {}: {}", f.getFileName(), e.toString());
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("[ModelLoadManager] Error scanning materials dir {}: {}", materialsDir, e.getMessage());
            }
        }

        if (vmtFailCount > 0 || vtfFailCount > 0) {
            LOGGER.warn("[ModelLoadManager] Parse results: VMT found={} failed={}, VTF found={} failed={}",
                vmtCount, vmtFailCount, vtfCount, vtfFailCount);
        }

        LOGGER.info("[ModelLoadManager] Texture scan: {} VMTs, {} VTFs from {} directories",
            vmtCache.size(), vtfCache.size(), dirsToScan.size());

        // Use provided cdPrefixes (already merged with Lua hints by caller)
        List<String> allCdPrefixes = cdPrefixes != null ? cdPrefixes : new ArrayList<>();
        if (allCdPrefixes.isEmpty()) {
            for (String cdTex : mdl.cdTextures) {
                String prefix = cdTex.replace('\\', '/').toLowerCase();
                if (!prefix.endsWith("/")) prefix += "/";
                allCdPrefixes.add(prefix);
            }
        }
        LOGGER.info("[ModelLoadManager] cdtexture prefixes: {}", allCdPrefixes);

        // Build a map from $basetexture (VMT reference, with $cdmaterials) to VMT key
        Map<String, String> baseTexToVmtKey = new HashMap<>();
        for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
            String fullPath = e.getValue().getFullBaseTexturePath();
            if (fullPath != null) {
                baseTexToVmtKey.put(fullPath, e.getKey());
            }
        }

        // Build reverse map from VMT key to VTF key (via $basetexture + $cdmaterials)
        Map<String, String> vmtKeyToVtfKey = new HashMap<>();
        for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
            String fullPath = e.getValue().getFullBaseTexturePath();
            if (fullPath != null) {
                for (String vtfKey : vtfCache.keySet()) {
                    String vtfKeyLower = vtfKey.toLowerCase();
                    if (vtfKeyLower.equals(fullPath) || vtfKeyLower.equals(fullPath + ".vtf")) {
                        vmtKeyToVtfKey.put(e.getKey(), vtfKey);
                        break;
                    }
                }
            }
        }

        // Build ordered list of ALL VTF keys (sorted by path for consistent ordering)
        List<String> orderedVtfKeys = vtfCache.keySet().stream()
            .filter(k -> !k.contains("vgui"))  // Skip UI textures
            .sorted()
            .collect(Collectors.toList());
        LOGGER.info("[ModelLoadManager] Found {} renderable VTFs (excluding vgui)", orderedVtfKeys.size());

        // Log skin table info
        LOGGER.info("[ModelLoadManager] Skin table: {} entries, numskinref={}, numskinfamilies={}",
            mdl.skinTable.size(), mdl.header.numskinref, mdl.header.numskinfamilies);
        if (!mdl.skinTable.isEmpty() && mdl.skinTable.size() <= 64) {
            LOGGER.info("[ModelLoadManager] Skin table: {}", mdl.skinTable);
        }

        // Log texture info
        LOGGER.info("[ModelLoadManager] MDL textures: {} entries", mdl.textures.size());
        for (int i = 0; i < mdl.textures.size(); i++) {
            LOGGER.info("[ModelLoadManager]   Texture[{}]: name='{}'", i, mdl.textures.get(i).name);
        }

        // Log mesh material indices
        LOGGER.info("[ModelLoadManager] MDL meshes: {} total, logging material indices", mdl.meshes.size());
        for (int i = 0; i < mdl.meshes.size(); i++) {
            LOGGER.info("[ModelLoadManager]   Mesh[{}]: material={}", i, mdl.meshes.get(i).material);
        }

        // For each mesh, build a list of candidate texture names based on texture index
        int resolvedCount = 0;
        int unresolvedCount = 0;
        for (int meshIdx = 0; meshIdx < mdl.meshes.size(); meshIdx++) {
            SourceModelData.MeshTextureInfo info = resolveMeshTexture(
                mdl, meshIdx, vmtCache, vtfCache,
                    allCdPrefixes, orderedVtfKeys, baseTexToVmtKey,
                vmtKeyToVtfKey, luaMaterialHints
            );
            if (info != null && info.texture != null) {
                meshTexMap.put(meshIdx, info);
                resolvedCount++;
            } else {
                unresolvedCount++;
            }
        }

        if (unresolvedCount > 0) {
            LOGGER.warn("[ModelLoadManager] Resolved textures for {} / {} meshes ({} UNRESOLVED)",
                resolvedCount, mdl.meshes.size(), unresolvedCount);
            // Log unresolved mesh details for debugging
            for (int meshIdx = 0; meshIdx < mdl.meshes.size(); meshIdx++) {
                if (!meshTexMap.containsKey(meshIdx)) {
                    int materialIdx = mdl.meshes.get(meshIdx).material;
                    int texIndex = materialIdx;
                    if (!mdl.skinTable.isEmpty() && mdl.header.numskinref > 0) {
                        int wrapped = materialIdx >= 0 ? materialIdx % mdl.header.numskinref : 0;
                        if (wrapped < mdl.skinTable.size()) {
                            texIndex = mdl.skinTable.get(wrapped);
                        }
                    }
                    String texName = (texIndex >= 0 && texIndex < mdl.textures.size())
                        ? mdl.textures.get(texIndex).name : "(null)";
                    LOGGER.warn("[ModelLoadManager]   Mesh[{}] material={} texIndex={} texName='{}'",
                        meshIdx, materialIdx, texIndex, texName);
                }
            }
        } else {
            LOGGER.info("[ModelLoadManager] Resolved textures for ALL {} meshes", mdl.meshes.size());
        }

        var finalStats = colorResolver.getStatistics();
        if (finalStats.hasFailures()) {
            LOGGER.warn("[ModelLoadManager] Texture parse state: {}", finalStats);
        }

        vtfCache.clear();
        vmtCache.clear();

        return meshTexMap;
    }

    private static String relativePath(Path base, Path full) {
        String rel = base.relativize(full).toString().replace('\\', '/');
        if (rel.endsWith(".vmt") || rel.endsWith(".vtf")) {
            rel = rel.substring(0, rel.length() - 4);
        }
        return rel;
    }

    private static SourceModelData.MeshTextureInfo resolveMeshTexture(
        MdlParser.ParsedModel mdl, int meshIdx,
        Map<String, VmtParser.VmtMaterial> vmtCache,
        Map<String, VtfParser.VtfImageData> vtfCache,
        List<String> cdPrefixes,
        List<String> orderedVtfKeys,
        Map<String, String> baseTexToVmtKey,
        Map<String, String> vmtKeyToVtfKey,
        List<String> luaMaterialHints
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

        // Strategy 0: Try Lua material hints first (highest priority)
        if (luaMaterialHints != null && !luaMaterialHints.isEmpty()) {
            for (String luaPath : luaMaterialHints) {
                String luaNorm = luaPath.replace('\\', '/').toLowerCase();
                if (luaNorm.endsWith(".vtf")) luaNorm = luaNorm.substring(0, luaNorm.length() - 4);

                // Try direct VTF match
                String vtfMatch = findVtfForBaseTexture(luaNorm, vtfCache);
                if (vtfMatch != null) {
                    VtfParser.VtfImageData vtf = vtfCache.get(vtfMatch);
                    if (vtf != null && vtf.image != null) {
                        VmtParser.VmtMaterial mat = findVmtForTexture(luaNorm, vmtCache);
                        ResourceLocation loc = registerTexture(vtfMatch, vtf.image);
                        if (mat != null) {
                            float[] colorTint = mat.getColor2();
                            if (colorTint == null) colorTint = mat.getColor();
                            return new SourceModelData.MeshTextureInfo(loc,
                                mat.isTransparent(), mat.isAlphaTest(), mat.isNoCull(), vtfMatch, colorTint);
                        }
                        return new SourceModelData.MeshTextureInfo(loc, false, false, false, vtfMatch);
                    }
                }
                // Try matching Lua path against VMT $basetexture
                for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
                    String fullBt = e.getValue().getFullBaseTexturePath();
                    if (fullBt != null && (fullBt.equals(luaNorm) || fullBt.endsWith("/" + luaNorm) || luaNorm.endsWith("/" + fullBt))) {
                        String vtfKey = vmtKeyToVtfKey.get(e.getKey());
                        if (vtfKey == null) vtfKey = findVtfForBaseTexture(fullBt, vtfCache);
                        if (vtfKey != null) {
                            VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
                            if (vtf != null && vtf.image != null) {
                                ResourceLocation loc = registerTexture(vtfKey, vtf.image);
                                float[] colorTint = e.getValue().getColor2();
                                if (colorTint == null) colorTint = e.getValue().getColor();
                                return new SourceModelData.MeshTextureInfo(loc,
                                    e.getValue().isTransparent(), e.getValue().isAlphaTest(), e.getValue().isNoCull(), vtfKey, colorTint);
                            }
                        }
                    }
                }
            }
        }

        // Strategy 1: Match by VMT $basetexture (with $cdmaterials prefix)
        if (!texName.isEmpty()) {
            String cleanTexName = texName;
            if (cleanTexName.endsWith(".vtf") || cleanTexName.endsWith(".vmt")) {
                cleanTexName = cleanTexName.substring(0, cleanTexName.length() - 4);
            }
            String normalized = cleanTexName.replace('\\', '/').toLowerCase();

            // Build all candidate paths with cdPrefixes
            List<String> candidates = new ArrayList<>();
            candidates.add(normalized);
            for (String cdPrefix : cdPrefixes) {
                if (!cdPrefix.isEmpty() && !normalized.startsWith(cdPrefix)) {
                    candidates.add(cdPrefix + normalized);
                }
            }

            // Try exact match in VMT cache using full path (with $cdmaterials)
            for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
                String fullBtPath = e.getValue().getFullBaseTexturePath();
                if (fullBtPath != null) {
                    for (String candidate : candidates) {
                        if (fullBtPath.equals(candidate)
                            || fullBtPath.endsWith("/" + candidate)
                            || candidate.endsWith("/" + fullBtPath)) {
                            String vtfKey = vmtKeyToVtfKey.get(e.getKey());
                            if (vtfKey == null) vtfKey = findVtfForBaseTexture(fullBtPath, vtfCache);
                            if (vtfKey != null) {
                                VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
                                if (vtf != null && vtf.image != null) {
                                    VmtParser.VmtMaterial mat = e.getValue();
                                    ResourceLocation loc = registerTexture(vtfKey, vtf.image);
                                    float[] colorTint = mat.getColor2();
                                    if (colorTint == null) colorTint = mat.getColor();
                                    return new SourceModelData.MeshTextureInfo(loc,
                                        mat.isTransparent(), mat.isAlphaTest(), mat.isNoCull(), vtfKey, colorTint);
                                }
                            }
                        }
                    }
                }
                // Also try with raw $basetexture (without $cdmaterials) for compatibility
                String bt = e.getValue().getBaseTexture();
                if (bt != null) {
                    String btNorm = bt.replace('\\', '/').toLowerCase();
                    if (btNorm.endsWith(".vtf")) {
                        btNorm = btNorm.substring(0, btNorm.length() - 4);
                    }
                    for (String candidate : candidates) {
                        if (btNorm.equals(candidate)
                            || btNorm.endsWith("/" + candidate)
                            || candidate.endsWith("/" + btNorm)) {
                            String vtfKey = vmtKeyToVtfKey.get(e.getKey());
                            if (vtfKey == null) vtfKey = findVtfForBaseTexture(btNorm, vtfCache);
                            if (vtfKey != null) {
                                VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
                                if (vtf != null && vtf.image != null) {
                                    VmtParser.VmtMaterial mat = e.getValue();
                                    ResourceLocation loc = registerTexture(vtfKey, vtf.image);
                                    float[] colorTint = mat.getColor2();
                                    if (colorTint == null) colorTint = mat.getColor();
                                    return new SourceModelData.MeshTextureInfo(loc,
                                        mat.isTransparent(), mat.isAlphaTest(), mat.isNoCull(), vtfKey, colorTint);
                                }
                            }
                        }
                    }
                }
            }

            // Try matching as a VTF key directly (no VMT properties available)
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String key = e.getKey().toLowerCase();
                for (String candidate : candidates) {
                    if (key.equals(candidate)
                        || key.endsWith("/" + candidate)
                        || candidate.endsWith("/" + key)) {
                        ResourceLocation loc = registerTexture(e.getKey(), e.getValue().image);
                        return new SourceModelData.MeshTextureInfo(loc, false, false, false, e.getKey());
                    }
                }
            }

            // Try matching by filename only (last path component)
            String simpleName = normalized.contains("/") ?
                normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String kSimple = e.getKey().toLowerCase();
                kSimple = kSimple.contains("/") ? kSimple.substring(kSimple.lastIndexOf('/') + 1) : kSimple;
                if (kSimple.equals(simpleName)) {
                    ResourceLocation loc = registerTexture(e.getKey(), e.getValue().image);
                    return new SourceModelData.MeshTextureInfo(loc, false, false, false, e.getKey());
                }
            }

            // Try fuzzy match: texture name appears within VTF path
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String key = e.getKey().toLowerCase();
                if (key.contains(normalized.replace("/", "_"))
                    || key.contains(normalized.replace("/", ""))) {
                    ResourceLocation loc = registerTexture(e.getKey(), e.getValue().image);
                    return new SourceModelData.MeshTextureInfo(loc, false, false, false, e.getKey());
                }
            }
        }

        // Strategy 2: Use material index to select Nth VTF from the ordered list
        if (!orderedVtfKeys.isEmpty()) {
            int idx = materialIdx >= 0 ? materialIdx % orderedVtfKeys.size() : meshIdx % orderedVtfKeys.size();
            String vtfKey = orderedVtfKeys.get(idx);
            VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
            if (vtf != null && vtf.image != null) {
                LOGGER.debug("[ModelLoadManager] Mesh {} materialIdx {} -> VTF[{}] = {}", meshIdx, materialIdx, idx, vtfKey);
                ResourceLocation loc = registerTexture(vtfKey, vtf.image);
                return new SourceModelData.MeshTextureInfo(loc, false, false, false, vtfKey);
            }
        }

        // Strategy 3: Match by VMT path mapping (pair VMT with same-named VTF)
        if (!vmtCache.isEmpty()) {
            String texNameLower = texName.toLowerCase().replace('\\', '/');
            for (Map.Entry<String, String> entry : vmtKeyToVtfKey.entrySet()) {
                String vmtPath = entry.getKey().toLowerCase();
                if (texNameLower.endsWith("/" + vmtPath) || texNameLower.equals(vmtPath)) {
                    VtfParser.VtfImageData vtf = vtfCache.get(entry.getValue());
                    if (vtf != null && vtf.image != null) {
                        VmtParser.VmtMaterial mat = vmtCache.get(entry.getKey());
                        ResourceLocation loc = registerTexture(entry.getValue(), vtf.image);
                        if (mat != null) {
                            float[] colorTint = mat.getColor2();
                            if (colorTint == null) colorTint = mat.getColor();
                            return new SourceModelData.MeshTextureInfo(loc,
                                mat.isTransparent(), mat.isAlphaTest(), mat.isNoCull(), entry.getValue(), colorTint);
                        }
                        return new SourceModelData.MeshTextureInfo(loc, false, false, false, entry.getValue());
                    }
                }
            }
        }

        // Strategy 4: Fallback - use mesh index % count
        if (!orderedVtfKeys.isEmpty()) {
            int idx = meshIdx % orderedVtfKeys.size();
            VtfParser.VtfImageData vtf = vtfCache.get(orderedVtfKeys.get(idx));
            if (vtf != null && vtf.image != null) {
                ResourceLocation loc = registerTexture(orderedVtfKeys.get(idx), vtf.image);
                return new SourceModelData.MeshTextureInfo(loc, false, false, false, orderedVtfKeys.get(idx));
            }
        }

        return null;
    }

    /**
     * Find a VMT that references the given texture path (via $basetexture).
     */
    private static VmtParser.VmtMaterial findVmtForTexture(String texPath, Map<String, VmtParser.VmtMaterial> vmtCache) {
        String norm = texPath.toLowerCase();
        for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
            String fullBt = e.getValue().getFullBaseTexturePath();
            if (fullBt != null && (fullBt.equals(norm) || fullBt.endsWith("/" + norm) || norm.endsWith("/" + fullBt))) {
                return e.getValue();
            }
            String bt = e.getValue().getBaseTexture();
            if (bt != null) {
                String btNorm = bt.replace('\\', '/').toLowerCase();
                if (btNorm.endsWith(".vtf")) btNorm = btNorm.substring(0, btNorm.length() - 4);
                if (btNorm.equals(norm) || btNorm.endsWith("/" + norm) || norm.endsWith("/" + btNorm)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String findVtfForBaseTexture(String baseTexPath, Map<String, VtfParser.VtfImageData> vtfCache) {
        // Exact match first
        if (vtfCache.containsKey(baseTexPath)) return baseTexPath;
        // Case-insensitive match
        for (String key : vtfCache.keySet()) {
            if (key.equalsIgnoreCase(baseTexPath)) return key;
        }
        // Try with .vtf suffix
        if (!baseTexPath.endsWith(".vtf")) {
            String withExt = baseTexPath + ".vtf";
            if (vtfCache.containsKey(withExt)) return withExt;
            for (String key : vtfCache.keySet()) {
                if (key.equalsIgnoreCase(withExt)) return key;
            }
        }
        // Try matching by filename only (last path segment)
        String simpleName = baseTexPath.contains("/")
            ? baseTexPath.substring(baseTexPath.lastIndexOf('/') + 1) : baseTexPath;
        if (!simpleName.isEmpty()) {
            for (String key : vtfCache.keySet()) {
                String kSimple = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                if (kSimple.equalsIgnoreCase(simpleName)) return key;
            }
        }
        return null;
    }

    private static ResourceLocation registerTexture(String key, BufferedImage image) {
        String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_').replace('.', '_').toLowerCase(Locale.ROOT);

        ResourceLocation existing = colorResolver.getRegistered(regKey);
        if (existing != null) return existing;

        ResourceLocation loc = ResourceLocation.parse("transferstation_whimsicalideas:textures/generated/" + regKey);

        try {
            NativeImage nativeImage = TextureColorResolver.bufferedImageToNativeImage(image);
            DynamicTexture dynamicTex = new DynamicTexture(nativeImage);

            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) {
                mc.getTextureManager().register(loc, dynamicTex);
            } else {
                mc.execute(() -> mc.getTextureManager().register(loc, dynamicTex));
            }

            int color = extractCenterPixelColor(image);
            colorResolver.markComplete(regKey, loc, color, false, false, false);
            LOGGER.debug("[ModelLoadManager] Registered texture: {} ({}x{})", loc, image.getWidth(), image.getHeight());
        } catch (Exception e) {
            colorResolver.markFailed(regKey, e.getMessage());
            LOGGER.warn("[ModelLoadManager] Failed to register texture {}: {}", regKey, e.getMessage());
            return loc;
        }

        return loc;
    }

    private static int extractCenterPixelColor(BufferedImage image) {
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int pixel = image.getRGB(cx, cy);
        int a = (pixel >> 24) & 0xFF;
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        if (a == 0) a = 255;
        return transferstation.transferstation_whimsicalideas.client.ColorUtils.argb(a, r, g, b);
    }

public static void clearTextureRegistry() {
        var stats = colorResolver.getStatistics();
        if (stats.registeredTextures > 0) {
            LOGGER.info("[ModelLoadManager] Clearing texture registry (stats: {})", stats);
        }
        colorResolver.clearAll();
    }
}
