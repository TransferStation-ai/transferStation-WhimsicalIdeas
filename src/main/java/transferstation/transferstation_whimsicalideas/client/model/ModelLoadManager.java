package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ModelLoadManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VVD_VERTEX_SIZE = 48;
    // Bump this whenever parsing logic changes to invalidate old caches
    // 25: 在磁盘缓存中持久化解码后的纹理像素，命中时无需重新解析 VTF
    // 26: 在磁盘缓存中持久化 shaderType，命中时无需重新解析 VMT
    // 29: invBindMatrices 构建方式改为渲染同名世界矩阵求逆（修复静止错位）
    // 30: SourceModelData 增加程序骨骼/序列/flex 元数据（axisInterpBones 等），磁盘缓存同步持久化
    private static final int CACHE_FORMAT_VERSION = 30;

    private static final Map<String, SourceModelData> modelCache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SourceModelData> eldest) {
            return size() > 64;
        }
    });

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long BASE_RETRY_DELAY_MS = 500;

    // Concurrent loading limiter (max 4 concurrent loads)
    private static final int MAX_CONCURRENT_LOADS = 4;
    private static final Semaphore loadSemaphore = new Semaphore(MAX_CONCURRENT_LOADS);

    // Memory monitoring threshold (warn when total model memory exceeds 512 MB)
    private static final long MEMORY_WARNING_THRESHOLD_BYTES = 512L * 1024 * 1024;

    private static final TextureColorResolver colorResolver = new TextureColorResolver();
    private static Path cacheDir = null;

    private static final Map<String, CompletableFuture<SourceModelData>> loadingFutures = new ConcurrentHashMap<>();

    private static final List<ModelLoadCallback> callbacks = Collections.synchronizedList(new ArrayList<>());

    public static void setCacheDir(Path dir) {
        cacheDir = dir;
    }

    public static SourceModelData getCached(String key) {
        return modelCache.get(key);
    }

    public static void clearCache() {
        // Unregister any soft-body simulations tied to cached models so they don't leak.
        for (SourceModelData data : modelCache.values()) {
            if (data.physicsSimId != null) {
                transferstation.transferstation_whimsicalideas.client.physics.PhysicsSimulationManager.unregisterSimulation(data.physicsSimId);
            }
            ModelLoadStatistics.recordModelMemoryFreed(estimateModelMemory(data));
        }
        modelCache.clear();
        loadingFutures.clear();
        colorResolver.clearAll();
    }

    private static void fireModelLoadedCallbacks(String cacheKey, SourceModelData data,
                                                   ModelParserStrategy strategy, long elapsedMs) {
        if (callbacks.isEmpty()) return;
        if (cacheKey == null) return;

        ModelLoadDiagnostics.Builder builder = new ModelLoadDiagnostics.Builder();
        String name = cacheKey;
        int lastSep = cacheKey.lastIndexOf(java.io.File.separatorChar);
        if (lastSep >= 0) name = cacheKey.substring(lastSep + 1);
        builder.modelName(name);
        builder.loadTimeMs(elapsedMs);
        builder.success(true);
        builder.parserStrategy(strategy != null ? strategy.getPlatformName() : "none");

        if (data != null) {
            builder.numBones(data.bones.size());
            builder.numBodyParts(data.bodyParts.size());
            builder.numMeshes(data.meshes.size());
            builder.numVertices(data.totalVertices());
            builder.numTriangles(data.totalTriangles());
            List<String> bpNames = new ArrayList<>();
            for (SourceModelData.BodyPartInfo bp : data.bodyParts) {
                bpNames.add(bp.name);
            }
            builder.bodyPartNames(bpNames);
        } 

        ModelLoadDiagnostics diag = builder.build();
        LOGGER.info("[ModelLoadManager] {}", diag.toSummaryString());

        // Iterate over a snapshot to avoid concurrent modification
        ModelLoadCallback[] snapshot;
        synchronized (callbacks) {
            snapshot = callbacks.toArray(new ModelLoadCallback[0]);
        }
        for (ModelLoadCallback cb : snapshot) {
            try {
                cb.onModelLoaded(cacheKey, diag);
            } catch (Exception e) {
                LOGGER.warn("[ModelLoadManager] Callback threw: {}", e.getMessage());
            }
        }
    }

    public static CompletableFuture<SourceModelData> loadModelAsync(Path packageDir) {
        String cacheKey = packageDir.toAbsolutePath().toString();
        SourceModelData cached = modelCache.get(cacheKey);
        if (cached != null) {
            ModelLoadStatistics.recordCacheHit(false);
            return CompletableFuture.completedFuture(cached);
        }

        return loadingFutures.computeIfAbsent(cacheKey, k -> {
            CompletableFuture<SourceModelData> future = CompletableFuture.supplyAsync(() -> {
                try {
                    loadSemaphore.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                ModelLoadStatistics.recordConcurrentLoad(MAX_CONCURRENT_LOADS - loadSemaphore.availablePermits());
                try {
                    return loadModelWithRetry(packageDir);
                } catch (Exception e) {
                    LOGGER.error("[ModelLoadManager] Async load failed for {}", packageDir, e);
                    return null;
                } finally {
                    loadSemaphore.release();
                }
            });
            future.whenComplete((data, t) -> loadingFutures.remove(k));
            return future;
        });
    }

    /**
     * Attempt to load a model with exponential backoff retry logic (max 3 retries).
     * On native parser failure, gracefully fallback to Java parser.
     */
    private static SourceModelData loadModelWithRetry(Path packageDir) {
        ModelParserStrategy strategy = ModelParserProvider.getStrategy();
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long delay = BASE_RETRY_DELAY_MS * (1L << (attempt - 1));
                LOGGER.info("[ModelLoadManager] Retry attempt {}/{} for {} after {}ms",
                    attempt, MAX_RETRIES, packageDir, delay);
                ModelLoadStatistics.recordRetry();
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            try {
                ModelLoadStatistics.recordLoadStart();
                long startTime = System.nanoTime();
                SourceModelData data = loadModel(packageDir, strategy);
                long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

                if (data != null && !data.meshes.isEmpty()) {
                    ModelLoadStatistics.recordLoadSuccess(elapsedMs);
                    checkMemoryThreshold();
                    return data;
                }

                // Empty result - retry with Java fallback if native was used
                if (strategy.isAvailable() && !(strategy instanceof JavaModelParserStrategy)) {
                    LOGGER.warn("[ModelLoadManager] Native parser returned empty for {}, trying Java fallback",
                        packageDir);
                    ModelLoadStatistics.recordNativeFallback();
                    strategy = new JavaModelParserStrategy();
                    try {
                        ModelLoadStatistics.recordLoadStart();
                        long fallbackStart = System.nanoTime();
                        data = loadModel(packageDir, strategy);
                        long fallbackMs = (System.nanoTime() - fallbackStart) / 1_000_000;
                        if (data != null && !data.meshes.isEmpty()) {
                            ModelLoadStatistics.recordLoadSuccess(fallbackMs);
                            checkMemoryThreshold();
                            return data;
                        }
                    } catch (Exception fallbackEx) {
                        lastException = fallbackEx;
                        ModelLoadStatistics.recordLoadFailure();
                    }
                }
                lastException = new IOException("Load returned empty result");
            } catch (Exception e) {
                lastException = e;
                ModelLoadStatistics.recordLoadFailure();
                LOGGER.warn("[ModelLoadManager] Load attempt {}/{} failed for {}: {}",
                    attempt + 1, MAX_RETRIES + 1, packageDir, e.getMessage());

                // On native parser error, try Java fallback before next retry
                if (strategy.isAvailable() && !(strategy instanceof JavaModelParserStrategy)) {
                    LOGGER.info("[ModelLoadManager] Falling back to Java parser for {}", packageDir);
                    ModelLoadStatistics.recordNativeFallback();
                    strategy = new JavaModelParserStrategy();
                }
            }
        }

        LOGGER.error("[ModelLoadManager] All {} attempts failed for {}", MAX_RETRIES + 1, packageDir, lastException);
        return null;
    }

    public static SourceModelData loadModel(Path packageDir) {
        ModelParserStrategy strategy = ModelParserProvider.getStrategy();
        return loadModel(packageDir, strategy);
    }

    /**
     * Check if total model memory usage exceeds warning threshold.
     */
    private static void checkMemoryThreshold() {
        long totalMemory = ModelLoadStatistics.getTotalMemoryUsageBytes();
        if (totalMemory > MEMORY_WARNING_THRESHOLD_BYTES) {
            LOGGER.warn("[ModelLoadManager] Memory usage warning: {} MB exceeds threshold {} MB ({} models tracked)",
                String.format("%.1f", totalMemory / (1024.0 * 1024.0)),
                MEMORY_WARNING_THRESHOLD_BYTES / (1024 * 1024),
                ModelLoadStatistics.getTrackedModelCount());
        }
    }

    /**
     * Estimate memory usage of a SourceModelData in bytes.
     */
    private static long estimateModelMemory(SourceModelData data) {
        if (data == null) return 0;
        long bytes = 0;
        for (SourceModelData.MeshData mesh : data.meshes) {
            bytes += (long) mesh.vertices.length * 4; // float = 4 bytes
            bytes += (long) mesh.indices.length * 4;  // int = 4 bytes
            if (mesh.boneWeights != null) bytes += (long) mesh.boneWeights.length * 4;
            if (mesh.boneIndices != null) bytes += (long) mesh.boneIndices.length * 4;
            if (mesh.colorTint != null) bytes += (long) mesh.colorTint.length * 4;
        }
        bytes += (long) data.bones.size() * 80; // approx per BoneInfo
        bytes += (long) data.bodyParts.size() * 64;
        bytes += 256; // overhead for lists and metadata
        return bytes;
    }

    public static synchronized SourceModelData loadModel(Path packageDir, ModelParserStrategy strategy) {
        String cacheKey = packageDir.toAbsolutePath().toString();
        SourceModelData cached = modelCache.get(cacheKey);
        if (cached != null) {
            ModelLoadStatistics.recordCacheHit(false);
            // 模型缓存命中时：如果纹理注册表为空（清除后重建场景），
            // 从材料目录重新加载纹理。否则不操作 ——
            // 世代计数器机制已由 TextureColorResolver 处理：
            // - markTexturesStale() 递增世代计数器
            // - reRegisterAllTextures() 批量重建 DynamicTexture
            // - ensureTextureRegistered() 按需逐条目重注册
            if (colorResolver.getStatistics().registeredTextures() == 0) {
                LOGGER.info("[ModelLoadManager] Re-registering textures from cached model: {}", packageDir);
                reRegisterTexturesFromCache(packageDir, cached);
            }
            return cached;
        }

        colorResolver.trimStale();

        // Try disk cache first
        SourceModelData diskData = loadFromDiskCache(packageDir);
        if (diskData != null) {
            modelCache.put(cacheKey, diskData);
            ModelLoadStatistics.recordCacheHit(true);
            long memBytes = estimateModelMemory(diskData);
            ModelLoadStatistics.recordModelMemory(memBytes);
            LOGGER.info("[ModelLoadManager] Restored model from disk cache: {} meshes (est. {} KB)",
                diskData.meshes.size(), memBytes / 1024);
            reRegisterTexturesFromCache(packageDir, diskData);
            return diskData;
        }

        // 开始加载进度追踪
        String modelName = packageDir.getFileName() != null ? packageDir.getFileName().toString() : "model";
        ModelLoadProgress.setModelName(modelName);
        ModelLoadProgress.begin(ModelLoadProgress.Phase.SCANNING);

        try {
            long startTime = System.nanoTime();
            SourceModelData data = loadFromDirectory(packageDir, strategy);
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            if (!data.meshes.isEmpty()) {
                modelCache.put(cacheKey, data);
                long memBytes = estimateModelMemory(data);
                ModelLoadStatistics.recordModelMemory(memBytes);
                fireModelLoadedCallbacks(cacheKey, data, strategy, elapsedMs);
                // 磁盘缓存异步保存，不阻塞进度条和后续模型加载
                final SourceModelData dataToCache = data;
                CompletableFuture.runAsync(() -> saveToDiskCache(packageDir, dataToCache))
                    .thenRun(() -> LOGGER.info("[ModelLoadManager] Disk cache saved for {}: {} meshes, {} triangles, {} vertices (parser: {})",
                        packageDir, dataToCache.meshes.size(), dataToCache.totalTriangles(), dataToCache.totalVertices(),
                        strategy.getPlatformName()))
                    .exceptionally(throwable -> {
                        LOGGER.warn("[ModelLoadManager] Failed to save disk cache for {}: {}",
                            packageDir, throwable.getMessage());
                        return null;
                    });
            }
            ModelLoadProgress.reset();
            return data;
        } catch (Exception e) {
            ModelLoadProgress.reset();
            LOGGER.error("[ModelLoadManager] Failed to load model from {} using {}", packageDir, strategy.getPlatformName(), e);
            return null;
        }
    }

    /**
     * Load a model directly from a VPK archive without extracting files to disk first.
     * This reads MDL/VVD/VTX/SMD data in-memory from the VPK and parses it through
     * the normal pipeline. Materials (VMT/VTF) are extracted to a temp cache directory
     * for texture resolution.
     *
     * @param archive   Opened VPK archive
     * @param modelPath Model directory path within the VPK (e.g. "models/player/soldier")
     * @return Loaded model data, or null if loading failed
     */
    public static SourceModelData loadModelFromVpk(VpkParser.VpkArchive archive, String modelPath) {
        if (archive == null || modelPath == null || modelPath.isEmpty()) return null;

        String cacheKey = archive.dirFile.toAbsolutePath() + "::" + modelPath;
        SourceModelData cached = modelCache.get(cacheKey);
        if (cached != null) return cached;

        try {
            String modelName = modelPath.contains("/")
                ? modelPath.substring(modelPath.lastIndexOf('/') + 1)
                : modelPath;
            ModelLoadProgress.setModelName(modelName);
            ModelLoadProgress.begin(ModelLoadProgress.Phase.SCANNING);

            VpkParser.VpkModelFiles vpkFiles = VpkParser.readModelFiles(archive, modelPath);
            if (vpkFiles == null || !vpkFiles.isValid()) {
                LOGGER.warn("[ModelLoadManager] No valid model files found in VPK for path: {}", modelPath);
                ModelLoadProgress.reset();
                return null;
            }

            ModelLoadProgress.setPhase(ModelLoadProgress.Phase.PARSING);
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            SourceModelData data = loadFromVpkData(archive, modelPath, vpkFiles, strategy);
            if (data != null && !data.meshes.isEmpty()) {
                modelCache.put(cacheKey, data);
                long memBytes = estimateModelMemory(data);
                ModelLoadStatistics.recordModelMemory(memBytes);
                LOGGER.info("[ModelLoadManager] Loaded model from VPK {}: {} meshes, {} triangles, {} vertices (est. {} KB)",
                    modelPath, data.meshes.size(), data.totalTriangles(), data.totalVertices(), memBytes / 1024);
            }
            ModelLoadProgress.reset();
            return data;
        } catch (Exception e) {
            ModelLoadProgress.reset();
            LOGGER.error("[ModelLoadManager] Failed to load model '{}' from VPK {}", modelPath, archive.dirFile, e);
            return null;
        }
    }

    /**
     * Internal: build SourceModelData from VPK-read model file data.
     * For textures, extracts VMT/VTF files from the VPK to a temp cache directory
     * so the existing texture resolution pipeline can find them.
     */
    private static SourceModelData loadFromVpkData(
            VpkParser.VpkArchive archive, String modelPath,
            VpkParser.VpkModelFiles vpkFiles, ModelParserStrategy strategy) throws IOException {

        String modelName = modelPath.contains("/") 
            ? modelPath.substring(modelPath.lastIndexOf('/') + 1) 
            : modelPath;

        // Extract materials from VPK to a temp cache directory
        Path vpkCacheDir = archive.dirFile.getParent().resolve(".vpk_mtl_cache");
        Path materialsDir = prepareVpkMaterials(archive, modelPath, vpkCacheDir);

        MdlDataTypes.ParsedModel mdl;
        VvdParser.ParsedVvd vvd;
        VtxParser.ParsedVtx vtx;

        if (vpkFiles.hasMdlTrio()) {
            if (strategy.isAvailable() && !(strategy instanceof JavaModelParserStrategy)) {
                mdl = strategy.parseMdl(vpkFiles.mdlData);
                vvd = strategy.parseVvd(vpkFiles.vvdData);
                vtx = strategy.parseVtx(vpkFiles.vtxData);
            } else {
                mdl = MdlParser.parse(vpkFiles.mdlData);
                vvd = VvdParser.parse(vpkFiles.vvdData);
                vtx = VtxParser.parse(vpkFiles.vtxData, vvd.vertices.size());
            }
        } else if (vpkFiles.hasSmd()) {
            // SMD fallback - parse SMD directly
            return loadFromSmdVpk(modelName, vpkFiles.smdData, materialsDir);
        } else {
            throw new IOException("No parseable model data in VPK entry: " + modelPath);
        }

        // Scan Lua material hints from VPK
        List<String> luaMaterialHints = new ArrayList<>();
        List<String> luaCdMaterialsHints = new ArrayList<>();
        scanVpkLuaForMaterialHints(archive, modelPath, luaMaterialHints, luaCdMaterialsHints);

        // Find materials directories (extracted from VPK)
        List<Path> allMaterialsDirs = new ArrayList<>();
        if (materialsDir != null && Files.exists(materialsDir)) {
            allMaterialsDirs.add(materialsDir);
        }
        Path primaryMaterialsDir = allMaterialsDirs.isEmpty() ? null : allMaterialsDirs.get(0);

        // Merge cdTextures from MDL with Lua hints
        List<String> allCdPrefixes = getStringList(mdl, luaCdMaterialsHints);

        int textureFileCount = countTextureFiles(allMaterialsDirs);
        ModelLoadProgress.begin(ModelLoadProgress.Phase.TEXTURING, textureFileCount);
        Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap =
            loadTextures(mdl, primaryMaterialsDir, allMaterialsDirs, luaMaterialHints, allCdPrefixes);

        int estimatedMeshCount = vtx.meshTriangles.size();
        if (estimatedMeshCount == 0) estimatedMeshCount = mdl.meshes.size();
        ModelLoadProgress.begin(ModelLoadProgress.Phase.BUILDING, estimatedMeshCount);
        return buildSourceModelData(mdl, vvd, vtx, meshTextureMap, modelName,
            mdl.includeModels, includePath -> loadModelFromVpk(archive, includePath));
    }

    private static @NotNull List<String> getStringList(MdlDataTypes.ParsedModel mdl, List<String> luaCdMaterialsHints) {
        List<String> allCdPrefixes = new ArrayList<>();
        for (String cdTex : mdl.cdTextures) {
            String prefix = cdTex.replace('\\', '/').toLowerCase();
            if (!prefix.endsWith("/")) prefix += "/";
            allCdPrefixes.add(prefix);
        }
        for (String hint : luaCdMaterialsHints) {
            String prefix = hint.replace('\\', '/').toLowerCase();
            if (!prefix.endsWith("/")) prefix += "/";
            if (!allCdPrefixes.contains(prefix)) allCdPrefixes.add(prefix);
        }
        return allCdPrefixes;
    }

    /**
     * Load SMD data read from VPK entry into SourceModelData.
     */
    private static SourceModelData loadFromSmdVpk(String modelName, byte[] smdData, Path materialsDir) throws IOException {
        SmdParser.ParsedSmd smd = SmdParser.parse(smdData);
        if (smd.meshes.isEmpty()) {
            throw new IOException("SMD data has no triangles");
        }

        SourceModelData result = new SourceModelData();
        result.name = modelName;
        result.modelScale = 1.0f;

        for (SmdParser.SmdBone bone : smd.bones) {
            result.bones.add(new SourceModelData.BoneInfo(bone.name, new float[]{0, 0, 0}, bone.parent));
        }

        // Build meshes from SMD triangles
        Map<String, VtfParser.VtfImageData> vtfCache = new HashMap<>();
        Map<String, VmtParser.VmtMaterial> vmtCache = new HashMap<>();
        Map<String, BufferedImage> commonImageCache = new HashMap<>();

        if (materialsDir != null && Files.exists(materialsDir)) {
            scanMaterialsForSmd(List.of(materialsDir), vtfCache, vmtCache, commonImageCache);
        }

        int meshIdx = 0;
        for (SmdParser.SmdMesh smdMesh : smd.meshes) {
            if (smdMesh.vertices.size() < 3) continue;

            List<Float> vertList = new ArrayList<>();
            List<Integer> idxList = new ArrayList<>();
            if (processSmdTriangles(smdMesh, vertList, idxList)) continue;

            float[] vertArray = new float[vertList.size()];
            for (int i = 0; i < vertList.size(); i++) vertArray[i] = vertList.get(i);
            int[] idxArray = new int[idxList.size()];
            for (int i = 0; i < idxList.size(); i++) idxArray[i] = idxList.get(i);

            ResourceLocation texture = resolveSmdMaterialTexture(smdMesh.materialName, vtfCache);
            String vtfKey = texture != null ? extractVtfKey(texture) : null;

            result.meshes.add(new SourceModelData.MeshData.Builder()
                .vertices(vertArray).indices(idxArray)
                .texture(texture).vtfKey(vtfKey)
                .bodyPartIndex(0).modelIndex(0).materialIndex(meshIdx)
                .build());
            meshIdx++;
        }

        computeBounds(result);
        return result;
    }

    private static ResourceLocation resolveSmdMaterialTexture(String materialName,
            Map<String, VtfParser.VtfImageData> vtfCache) {
        if (materialName == null || materialName.isEmpty()) return null;

        String matNorm = materialName.replace('\\', '/').toLowerCase();
        if (matNorm.endsWith(".vtf") || matNorm.endsWith(".vmt")) {
            matNorm = matNorm.substring(0, matNorm.length() - 4);
        }

        // Try direct VTF match
        String matchedVtf = findVtfForBaseTexture(matNorm, vtfCache);
        if (matchedVtf == null) {
            String simpleName = matNorm.contains("/")
                ? matNorm.substring(matNorm.lastIndexOf('/') + 1) : matNorm;
            for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                String key = e.getKey().toLowerCase();
                String kSimple = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                if (kSimple.equals(simpleName) || key.contains(simpleName)) {
                    matchedVtf = e.getKey();
                    break;
                }
            }
        }

        if (matchedVtf != null) {
            VtfParser.VtfImageData vtf = vtfCache.get(matchedVtf);
            if (vtf != null && vtf.image != null) {
                return registerTexture(matchedVtf, vtf.image);
            }
        }

        return null;
    }

    private static String extractVtfKey(ResourceLocation texture) {
        if (texture == null) return null;
        String path = texture.getPath();
        if (path.startsWith("textures/generated/")) {
            String key = path.substring("textures/generated/".length());
            if (key.startsWith("gmod_")) key = key.substring(5);
            return key.replace('_', '/');
        }
        return null;
    }

    /**
     * Process SMD mesh triangles into vertex/index arrays with Source→Minecraft coordinate conversion.
     * @return true if valid geometry was produced (idxList.size() >= 3)
     */
    private static boolean processSmdTriangles(SmdParser.SmdMesh smdMesh,
            List<Float> vertList, List<Integer> idxList) {
        int triCount = smdMesh.vertices.size() / 3;
        if (triCount == 0) return true;

        Map<String, Integer> vertCache = new HashMap<>();

        for (int t = 0; t < triCount; t++) {
            for (int v = 0; v < 3; v++) {
                SmdParser.SmdVertex sv = smdMesh.vertices.get(t * 3 + v);
                String vertKey = String.format("%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%d",
                    sv.x, sv.y, sv.z, sv.nx, sv.ny, sv.nz, sv.u, sv.v, sv.primaryBone());

                Integer cached = vertCache.get(vertKey);
                if (cached != null) {
                    idxList.add(cached);
                    continue;
                }

                // Coordinate conversion: Source (x=fwd, y=left, z=up) -> Minecraft (x=right, y=up, z=south)
                vertList.add(-sv.y);
                vertList.add(sv.z);
                vertList.add(sv.x);
                vertList.add(-sv.ny);
                vertList.add(sv.nz);
                vertList.add(sv.nx);
                vertList.add(sv.u);
                vertList.add(1.0f - sv.v);

                int newIdx = (vertList.size() / 8) - 1;
                vertCache.put(vertKey, newIdx);
                idxList.add(newIdx);
            }
        }
        return idxList.size() < 3;
    }

    /**
     * Extract VMT/VTF files from a VPK archive for a given model to a cache directory.
     * This enables the existing texture resolution pipeline to find materials.
     */
    private static Path prepareVpkMaterials(VpkParser.VpkArchive archive, String modelPath, Path cacheDir) {
        try {
            // Determine the model's addon/model search root
            String modelPrefix = modelPath.replace('\\', '/').toLowerCase(Locale.ROOT);
            // Walk up to find the top-level directory (e.g., "models/")
            int modelsIdx = modelPrefix.indexOf("models/");
            String searchRoot = "";
            if (modelsIdx >= 0) {
                searchRoot = modelPrefix.substring(0, modelsIdx);
            }

            // Extract materials that are relevant to this model
            boolean extracted = false;
            for (VpkParser.VpkEntry entry : archive.entries) {
                String ext = entry.extension;
                if (!ext.equals("vmt") && !ext.equals("vtf")) continue;

                String entryPath = entry.path.toLowerCase(Locale.ROOT);
                // Only extract materials under the same root as the model
                if (!searchRoot.isEmpty() && !entryPath.startsWith(searchRoot)) continue;
                if (!entryPath.startsWith("materials") && !entryPath.contains("/materials")) continue;

                String relPath = entry.path + "/" + entry.filename + "." + entry.extension;
                Path targetFile = cacheDir.resolve(relPath).normalize();
                if (!targetFile.startsWith(cacheDir.normalize())) continue;

                if (!Files.exists(targetFile)) {
                    Files.createDirectories(targetFile.getParent());
                    byte[] data = archive.readEntry(entry);
                    Files.write(targetFile, data);
                    extracted = true;
                }
            }

            if (extracted) {
                LOGGER.info("[ModelLoadManager] Extracted VPK materials to cache: {}", cacheDir);
            }
            return cacheDir;
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to extract VPK materials: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Scan Lua files within a VPK archive for material/texture hints.
     */
    private static void scanVpkLuaForMaterialHints(VpkParser.VpkArchive archive, String modelPath,
            List<String> materialHints, List<String> cdMaterialsHints) {
        String modelPrefix = modelPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        int modelsIdx = modelPrefix.indexOf("models/");
        String searchRoot = (modelsIdx >= 0) ? modelPrefix.substring(0, modelsIdx) : "";

        for (VpkParser.VpkEntry entry : archive.entries) {
            if (!entry.extension.equals("lua")) continue;

            String entryPath = entry.path.toLowerCase(Locale.ROOT);
            if (!searchRoot.isEmpty() && !entryPath.startsWith(searchRoot)) continue;

            try {
                byte[] data = archive.readEntry(entry);
                String content = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                String[] lines = content.split("\n");
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.startsWith("--")) continue;
                    String lower = line.toLowerCase();

                    String[] matPatterns = {".material =", ".material=", "material =", "material="};
                    for (String pattern : matPatterns) {
                        int idx = lower.indexOf(pattern);
                        if (idx >= 0) {
                            String rest = line.substring(idx + pattern.length()).trim();
                            String val = ModelPackage.stripQuotes(rest);
                            if (val != null && !val.isEmpty() && !val.toLowerCase(Locale.ROOT).endsWith(".mdl")) {
                                materialHints.add(val.replace('\\', '/'));
                            }
                        }
                    }

                    String[] cdPatterns = {"$cdmaterials", "cdmaterials"};
                    for (String pattern : cdPatterns) {
                        int idx = lower.indexOf(pattern);
                        if (idx >= 0) {
                            int afterLen = pattern.length();
                            String rest = line.substring(idx + afterLen).trim();
                            if (rest.startsWith("=")) rest = rest.substring(1).trim();
                            String val = ModelPackage.stripQuotes(rest);
                            if (val != null && !val.isEmpty()) {
                                cdMaterialsHints.add(val.replace('\\', '/'));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private static void computeBounds(SourceModelData result) {
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
        }
    }

    /**
     * Inverse of a column-major 4x4 (general affine, supports scale). The bind
     * world matrices for invBind are already MC-space, so no conjugation is needed.
     */
    private static float[] invertMatrix(float[] m) {
        org.joml.Matrix4f mat = new org.joml.Matrix4f().set(m);
        mat.invert();
        float[] out = new float[16];
        mat.get(out);
        for (float v : out) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                LOGGER.warn("[ModelLoadManager] Degenerate bind matrix during invBind computation");
                return new float[16];
            }
        }
        return out;
    }

    /**
     * Populate {@code result.invBindMatrices} with the inverse of the renderer's own
     * bind world (AnimationProcessor.computeBindWorldMatrices, MC space). Keeps the
     * skin matrix ({@code worldBone * invBind}) identity in the rest pose even when
     * the MDL's poseToBone and pos/quat src-bone spaces disagree.
     */
    static void computeInvBindMatrices(SourceModelData data, String modelName) {
        data.invBindMatrices.clear();
        float[][] bindWorld = AnimationProcessor.computeBindWorldMatrices(data);
        if (bindWorld != null && bindWorld.length == data.bones.size()) {
            for (float[] world : bindWorld) {
                data.invBindMatrices.add(invertMatrix(world));
            }
        } else {
            LOGGER.warn("[ModelLoadManager] Could not compute bind world for {}, bones={}",
                modelName, data.bones.size());
        }
    }

    /**
     * Build a SourceModelData from parsed MDL/VVD/VTX data and resolved textures.
     * Consolidates the common post-parsing logic shared by directory-based
     * and VPK-based model loading paths: body part construction, mesh building,
     * bone/metadata transfer, include model resolution, and bounds computation.
     */
    private static SourceModelData buildSourceModelData(
            MdlDataTypes.ParsedModel mdl,
            VvdParser.ParsedVvd vvd,
            VtxParser.ParsedVtx vtx,
            Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap,
            String modelName,
            List<String> includeModels,
            java.util.function.Function<String, SourceModelData> includeModelLoader) {
        SourceModelData result = new SourceModelData();
        result.name = mdl.header.name != null ? mdl.header.name : modelName;
        result.modelScale = 1.0f;

        for (MdlDataTypes.BodyPart bp : mdl.bodyParts) {
            SourceModelData.BodyPartInfo info = new SourceModelData.BodyPartInfo(bp.name, bp.nummodels, bp.baseIndex);
            for (MdlDataTypes.Model m : mdl.models) {
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

        for (MdlDataTypes.Bone bone : mdl.bones) {
            result.bones.add(new SourceModelData.BoneInfo(
                bone.name,
                new float[]{bone.pos[0], bone.pos[1], bone.pos[2]},
                bone.quat != null ? new float[]{bone.quat[0], bone.quat[1], bone.quat[2], bone.quat[3]} : null,
                bone.rot != null ? new float[]{bone.rot[0], bone.rot[1], bone.rot[2]} : null,
                bone.parent));
        }

        // invBind must be the inverse of the SAME world bind matrices the renderer
        // uses (initializeBindPose + computeWorldBone, MC space). Building it from
        // mdl.invBindPose (a poseToBone world) broke rest pose: worldBone * invBind
        // != I, scattering vertices while leaving UVs intact. Requires srcBoneTransforms
        // to already be on the result, so transfer them before computing invBind.
        result.srcBoneTransforms.addAll(mdl.srcBoneTransforms);
        computeInvBindMatrices(result, modelName);

        result.attachments.addAll(mdl.attachments);
        result.boneControllers.addAll(mdl.boneControllers);
        result.hitboxSets.addAll(mdl.hitboxSets);
        result.sequences.addAll(mdl.sequences);
        result.ikChains.addAll(mdl.ikChains);
        result.flexDescs.addAll(mdl.flexDescs);
        result.flexControllers.addAll(mdl.flexControllers);
        result.flexRules.addAll(mdl.flexRules);
        result.localAnims.addAll(mdl.localAnims);
        result.poseParams.addAll(mdl.poseParams);
        result.localNodes.addAll(mdl.localNodes);
        result.ikAutoplayLocks.addAll(mdl.ikAutoplayLocks);
        result.mouths.addAll(mdl.mouths);
        result.keyValues = mdl.keyValues;
        result.surfaceProp = mdl.surfaceProp;
        result.hdr2 = mdl.hdr2;
        result.sequenceAnimData.addAll(mdl.sequenceAnimData);
        result.referenceSequenceIndices.addAll(mdl.referenceSequenceIndices);
        result.aPoseSequenceIndices.addAll(mdl.aPoseSequenceIndices);
        result.axisInterpBones.addAll(mdl.axisInterpBones);
        result.quatInterpBones.addAll(mdl.quatInterpBones);
        result.jiggleBones.addAll(mdl.jiggleBones);
        result.aimAtBones.addAll(mdl.aimAtBones);
        result.sequenceIKRules.addAll(mdl.sequenceIKRules);
        result.sequenceAutolayers.addAll(mdl.sequenceAutolayers);
        result.sequenceActivityModifiers.addAll(mdl.sequenceActivityModifiers);
        result.sequenceMovements.addAll(mdl.sequenceMovements);
        result.localHierarchies.addAll(mdl.localHierarchies);
        result.meshFlexAnimations.addAll(mdl.meshFlexAnimations);

        if (includeModelLoader != null && includeModels != null && !includeModels.isEmpty()) {
            LOGGER.info("[ModelLoadManager] Processing {} include models", includeModels.size());
            for (String includePath : includeModels) {
                try {
                    SourceModelData subData = includeModelLoader.apply(includePath);
                    if (subData != null && !subData.meshes.isEmpty()) {
                        result.meshes.addAll(subData.meshes);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[ModelLoadManager] Failed to load include model {}: {}", includePath, e.getMessage());
                }
            }
        }

        computeBounds(result);

        if (result.meshes.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No meshes built from model: {}", modelName);
        } else {
            LOGGER.info("[ModelLoadManager] Built {} meshes ({} triangles, {} vertices) from {}",
                result.meshes.size(), result.totalTriangles(), result.totalVertices(), modelName);
        }

        return result;
    }

    private static Path getCacheFilePath(Path packageDir) {
        if (cacheDir == null) return null;
        String key = packageDir.toAbsolutePath().toString().replace('\\', '/');
        String hash = sha256(key);
        return cacheDir.resolve(hash + ".bin.gz");
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    //因为遍历全部文件对于cpu的性能来说有着巨量的要求因此在这里给模型几何文件（mdl/vvd/dx90.vtx/smd）做轻量签名应该不会有问题
    //（材质/贴图变更不再使几何缓存失效）以防止后面开发的时候出现些其他的问题
    private static long computeModelSignature(Path packageDir) {
        try (Stream<Path> files = Files.walk(packageDir, 8)) {
            long h = 1125899906842597L;
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return n.endsWith(".mdl") || n.endsWith(".vvd")
                            || n.endsWith(".dx90.vtx") || n.endsWith(".smd");
                    })::iterator) {
                try {
                    long size = Files.size(f);
                    long mtime = Files.getLastModifiedTime(f).toMillis();
                    h = 31 * h + size;
                    h = 31 * h + mtime;
                } catch (IOException ignored) {
                    // 跳过无法访问的文件 有些文件可能带有加密或者是什么别的导致 AI无法识别
                }
            }
            return h;
        } catch (IOException e) {
            return 0L;
        }
    }

    // 保持方法签名不变，仅将语义从“最大修改时间”改为“模型几何签名哈希”
    private static long getLatestModifiedTime(Path packageDir) {
        return computeModelSignature(packageDir);
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

                int boneCount = dis.readInt();
                for (int b = 0; b < boneCount; b++) {
                    String boneName = dis.readUTF();
                    float px = dis.readFloat();
                    float py = dis.readFloat();
                    float pz = dis.readFloat();
                    float[] quat = dis.readBoolean() ? new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat(), dis.readFloat()} : null;
                    float[] rot = dis.readBoolean() ? new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat()} : null;
                    int parent = dis.readInt();
                    data.bones.add(new SourceModelData.BoneInfo(boneName, new float[]{px, py, pz}, quat, rot, parent));
                }

                int invBindCount = dis.readInt();
                if (invBindCount > 0 && invBindCount <= data.bones.size()) {
                    for (int ib = 0; ib < invBindCount; ib++) {
                        float[] invBind = new float[16];
                        for (int j = 0; j < 16; j++) invBind[j] = dis.readFloat();
                        data.invBindMatrices.add(invBind);
                    }
                }

                int meshCount = dis.readInt();
                for (int m = 0; m < meshCount; m++) {
                    int vertCount = dis.readInt();
                    float[] verts = new float[vertCount];
                    for (int i = 0; i < vertCount; i++) verts[i] = dis.readFloat();

                    int idxCount = dis.readInt();
                    // Validate against the mesh's vertex count: a triangle list can
                    // never reference more than 3 * vertCount indices. A corrupt or
                    // truncated cache could otherwise request a huge allocation or
                    // read past EOF.
                    int maxIdx = vertCount * 3;
                    if (idxCount < 0 || idxCount > maxIdx || idxCount > 50_000_000) {
                        throw new IOException("Corrupt disk cache: invalid idxCount=" + idxCount
                                + " for mesh with vertCount=" + vertCount);
                    }
                    int[] indices = new int[idxCount];
                    for (int i = 0; i < idxCount; i++) indices[i] = dis.readInt();

                    float[] boneWeights = null;
                    if (dis.readBoolean()) {
                        int bwLen = dis.readInt();
                        boneWeights = new float[bwLen];
                        for (int i = 0; i < bwLen; i++) boneWeights[i] = dis.readFloat();
                    }
                    int[] boneIndices = null;
                    if (dis.readBoolean()) {
                        int biLen = dis.readInt();
                        boneIndices = new int[biLen];
                        for (int i = 0; i < biLen; i++) boneIndices[i] = dis.readInt();
                    }

                    boolean translucent = dis.readBoolean();
                    boolean alphaTest = dis.readBoolean();
                    boolean noCull = dis.readBoolean();
                    boolean selfIllum = dis.readBoolean();
                    boolean hasPhong = dis.readBoolean();
                    boolean halfLambert = dis.readBoolean();
                    float phongBoost = dis.readFloat();
                    float alpha = dis.readFloat();
                    int detailBlendMode = dis.readInt();
                    float[] phongFresnelRanges = dis.readBoolean() ?
                        new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat()} : null;
                    String surfaceProp = dis.readBoolean() ? dis.readUTF() : null;

                    String txNamespace = dis.readBoolean() ? dis.readUTF() : null;
                    String txPath = dis.readBoolean() ? dis.readUTF() : null;
                    ResourceLocation texture = (txNamespace != null)
                        ? ResourceLocation.parse(txNamespace + ":" + txPath)
                        : null;

                    String nmNamespace = dis.readBoolean() ? dis.readUTF() : null;
                    String nmPath = dis.readBoolean() ? dis.readUTF() : null;
                    ResourceLocation normalMap = (nmNamespace != null)
                        ? ResourceLocation.parse(nmNamespace + ":" + nmPath)
                        : null;

                    int bodyPartIdx = dis.readInt();
                    int modelIdx = dis.readInt();
                    int materialIdx = dis.readInt();

                    String vtfKey = dis.readBoolean() ? dis.readUTF() : null;

                    float[] colorTint = null;
                    if (dis.readBoolean()) {
                        colorTint = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat(), dis.readFloat()};
                    }

                    ResourceLocation ssbumpMap = readResourceLocation(dis);
                    ResourceLocation envMapMask = readResourceLocation(dis);
                    ResourceLocation parallaxMap = readResourceLocation(dis);
                    ResourceLocation detailMap = readResourceLocation(dis);
                    ResourceLocation selfIllumMask = readResourceLocation(dis);
                    ResourceLocation phongExponentTexture = readResourceLocation(dis);

                    String shaderType = dis.readBoolean() ? dis.readUTF() : null;

                    data.meshes.add(new SourceModelData.MeshData.Builder()
                        .vertices(verts).indices(indices)
                        .boneWeights(boneWeights).boneIndices(boneIndices)
                        .texture(texture).normalMap(normalMap)
                        .ssbumpMap(ssbumpMap).envMapMask(envMapMask)
                        .parallaxMap(parallaxMap).detailMap(detailMap).selfIllumMask(selfIllumMask)
                        .translucent(translucent).alphaTest(alphaTest).noCull(noCull)
                        .selfIllum(selfIllum).hasPhong(hasPhong).halfLambert(halfLambert)
                        .phongBoost(phongBoost).phongFresnelRanges(phongFresnelRanges)
                        .phongExponentTexture(phongExponentTexture)
                        .bodyPartIndex(bodyPartIdx).modelIndex(modelIdx).materialIndex(materialIdx)
                        .vtfKey(vtfKey).colorTint(colorTint).alpha(alpha)
                        .surfaceProp(surfaceProp).detailBlendMode(detailBlendMode)
                        .shaderType(shaderType)
                        .build());
                }

                // Deserialize reference bone transforms
                int srcBoneCount = dis.readInt();
                for (int s = 0; s < srcBoneCount; s++) {
                    MdlDataTypes.SrcBoneTransform bt = new MdlDataTypes.SrcBoneTransform();
                    bt.pos = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat()};
                    bt.quat = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat(), dis.readFloat()};
                    bt.scale = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat()};
                    data.srcBoneTransforms.add(bt);
                }

                // Deserialize reference and A-pose sequence indices
                int refCount = dis.readInt();
                for (int r = 0; r < refCount; r++) data.referenceSequenceIndices.add(dis.readInt());
                int aPoseCount = dis.readInt();
                for (int a = 0; a < aPoseCount; a++) data.aPoseSequenceIndices.add(dis.readInt());

                // Deserialize sequence animation frame data
                int seqAnimCount = dis.readInt();
                for (int s = 0; s < seqAnimCount; s++) {
                    MdlDataTypes.SequenceAnimData sa = new MdlDataTypes.SequenceAnimData();
                    sa.isReference = dis.readBoolean();
                    sa.isAPose = dis.readBoolean();
                    int frameCount = dis.readInt();
                    for (int f = 0; f < frameCount; f++) {
                        MdlDataTypes.AnimFrameData frame = new MdlDataTypes.AnimFrameData();
                        frame.frame = dis.readInt();
                        int frameBoneCount = dis.readInt();
                        for (int b = 0; b < frameBoneCount; b++) {
                            MdlDataTypes.AnimFrameBone fb = new MdlDataTypes.AnimFrameBone();
                            fb.boneIndex = dis.readInt();
                            fb.boneName = dis.readUTF();
                            fb.pos = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat()};
                            fb.quat = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat(), dis.readFloat()};
                            fb.scale = new float[]{dis.readFloat(), dis.readFloat(), dis.readFloat()};
                            frame.boneTransforms.add(fb);
                        }
                        sa.frames.add(frame);
                    }
                    data.sequenceAnimData.add(sa);
                }

                // Deserialize procedural bone metadata (parallel to bones).
                int axisCount = dis.readInt();
                for (int i = 0; i < axisCount; i++) {
                    MdlProceduralBones.AxisInterpBone ab = new MdlProceduralBones.AxisInterpBone();
                    ab.control = dis.readInt();
                    ab.axis = dis.readInt();
                    for (int j = 0; j < 6; j++) {
                        for (int k = 0; k < 3; k++) ab.pos[j][k] = dis.readFloat();
                        for (int k = 0; k < 4; k++) ab.quat[j][k] = dis.readFloat();
                    }
                    data.axisInterpBones.add(ab);
                }
                int quatCount = dis.readInt();
                for (int i = 0; i < quatCount; i++) {
                    MdlProceduralBones.QuatInterpBone qb = new MdlProceduralBones.QuatInterpBone();
                    qb.control = dis.readInt();
                    int trigCount = dis.readInt();
                    for (int t = 0; t < trigCount; t++) {
                        MdlProceduralBones.QuatInterpTrigger tr = new MdlProceduralBones.QuatInterpTrigger();
                        tr.invTolerance = dis.readFloat();
                        for (int k = 0; k < 4; k++) tr.trigger[k] = dis.readFloat();
                        for (int k = 0; k < 3; k++) tr.pos[k] = dis.readFloat();
                        for (int k = 0; k < 4; k++) tr.quat[k] = dis.readFloat();
                        qb.triggers.add(tr);
                    }
                    data.quatInterpBones.add(qb);
                }
                int jiggleCount = dis.readInt();
                for (int i = 0; i < jiggleCount; i++) {
                    MdlProceduralBones.JiggleBone jb = new MdlProceduralBones.JiggleBone();
                    jb.flags = dis.readInt();
                    jb.length = dis.readFloat();
                    jb.tipMass = dis.readFloat();
                    jb.yawStiffness = dis.readFloat(); jb.yawDamping = dis.readFloat();
                    jb.pitchStiffness = dis.readFloat(); jb.pitchDamping = dis.readFloat();
                    jb.alongStiffness = dis.readFloat(); jb.alongDamping = dis.readFloat();
                    jb.angleLimit = dis.readFloat();
                    jb.minYaw = dis.readFloat(); jb.maxYaw = dis.readFloat();
                    jb.yawFriction = dis.readFloat(); jb.yawBounce = dis.readFloat();
                    jb.minPitch = dis.readFloat(); jb.maxPitch = dis.readFloat();
                    jb.pitchFriction = dis.readFloat(); jb.pitchBounce = dis.readFloat();
                    jb.baseMass = dis.readFloat(); jb.baseStiffness = dis.readFloat(); jb.baseDamping = dis.readFloat();
                    jb.baseMinLeft = dis.readFloat(); jb.baseMaxLeft = dis.readFloat(); jb.baseLeftFriction = dis.readFloat();
                    jb.baseMinUp = dis.readFloat(); jb.baseMaxUp = dis.readFloat(); jb.baseUpFriction = dis.readFloat();
                    jb.baseMinForward = dis.readFloat(); jb.baseMaxForward = dis.readFloat(); jb.baseForwardFriction = dis.readFloat();
                    jb.boingImpactSpeed = dis.readFloat(); jb.boingImpactAngle = dis.readFloat();
                    jb.boingDampingRate = dis.readFloat(); jb.boingFrequency = dis.readFloat(); jb.boingAmplitude = dis.readFloat();
                    data.jiggleBones.add(jb);
                }
                int aimCount = dis.readInt();
                for (int i = 0; i < aimCount; i++) {
                    MdlProceduralBones.AimAtBone ab = new MdlProceduralBones.AimAtBone();
                    ab.parent = dis.readInt();
                    ab.aim = dis.readInt();
                    for (int k = 0; k < 3; k++) ab.aimvector[k] = dis.readFloat();
                    for (int k = 0; k < 3; k++) ab.upvector[k] = dis.readFloat();
                    for (int k = 0; k < 3; k++) ab.basepos[k] = dis.readFloat();
                    data.aimAtBones.add(ab);
                }

                // Deserialize per-sequence metadata (parallel to sequences).
                int ikListCount = dis.readInt();
                for (int i = 0; i < ikListCount; i++) {
                    List<MdlSequenceData.IKRule> rules = new ArrayList<>();
                    int ruleCount = dis.readInt();
                    for (int r = 0; r < ruleCount; r++) {
                        MdlSequenceData.IKRule rule = new MdlSequenceData.IKRule();
                        rule.chain = dis.readInt(); rule.bone = dis.readInt(); rule.slot = dis.readInt(); rule.type = dis.readInt();
                        rule.height = dis.readFloat(); rule.radius = dis.readFloat(); rule.floor = dis.readFloat();
                        for (int k = 0; k < 3; k++) rule.pos[k] = dis.readFloat();
                        for (int k = 0; k < 4; k++) rule.quat[k] = dis.readFloat();
                        rule.start = dis.readFloat(); rule.peak = dis.readFloat(); rule.tail = dis.readFloat(); rule.end = dis.readFloat();
                        rule.contact = dis.readFloat(); rule.drop = dis.readFloat(); rule.top = dis.readFloat();
                        rule.attachment = dis.readUTF();
                        for (int k = 0; k < 6; k++) rule.errorScale[k] = dis.readFloat();
                        for (int k = 0; k < 6; k++) rule.errorOffset[k] = dis.readShort();
                        rules.add(rule);
                    }
                    data.sequenceIKRules.add(rules);
                }
                int layerListCount = dis.readInt();
                for (int i = 0; i < layerListCount; i++) {
                    List<MdlSequenceData.Autolayer> layers = new ArrayList<>();
                    int layerCount = dis.readInt();
                    for (int a = 0; a < layerCount; a++) {
                        MdlSequenceData.Autolayer al = new MdlSequenceData.Autolayer();
                        al.sequence = dis.readShort(); al.pose = dis.readShort();
                        al.flags = dis.readInt();
                        al.start = dis.readFloat(); al.peak = dis.readFloat(); al.tail = dis.readFloat(); al.end = dis.readFloat();
                        layers.add(al);
                    }
                    data.sequenceAutolayers.add(layers);
                }
                int modListCount = dis.readInt();
                for (int i = 0; i < modListCount; i++) {
                    List<MdlSequenceData.ActivityModifier> mods = new ArrayList<>();
                    int modCount = dis.readInt();
                    for (int a = 0; a < modCount; a++) {
                        MdlSequenceData.ActivityModifier am = new MdlSequenceData.ActivityModifier();
                        am.name = dis.readUTF();
                        mods.add(am);
                    }
                    data.sequenceActivityModifiers.add(mods);
                }
                int movListCount = dis.readInt();
                for (int i = 0; i < movListCount; i++) {
                    List<MdlSequenceData.Movement> movs = new ArrayList<>();
                    int movCount = dis.readInt();
                    for (int a = 0; a < movCount; a++) {
                        MdlSequenceData.Movement mv = new MdlSequenceData.Movement();
                        mv.endframe = dis.readInt(); mv.motionflags = dis.readInt();
                        mv.v0 = dis.readFloat(); mv.v1 = dis.readFloat(); mv.angle = dis.readFloat();
                        for (int k = 0; k < 3; k++) mv.vector[k] = dis.readFloat();
                        for (int k = 0; k < 3; k++) mv.position[k] = dis.readFloat();
                        movs.add(mv);
                    }
                    data.sequenceMovements.add(movs);
                }
                int hierCount = dis.readInt();
                for (int i = 0; i < hierCount; i++) {
                    MdlSequenceData.LocalHierarchy lh = new MdlSequenceData.LocalHierarchy();
                    lh.bone = dis.readInt(); lh.newParent = dis.readInt();
                    lh.start = dis.readFloat(); lh.peak = dis.readFloat(); lh.tail = dis.readFloat(); lh.end = dis.readFloat();
                    lh.startFrame = dis.readInt();
                    data.localHierarchies.add(lh);
                }

                // Deserialize per-mesh flex animation data (parallel to meshes).
                int flexMeshCount = dis.readInt();
                for (int i = 0; i < flexMeshCount; i++) {
                    List<MdlFlexAnimation.FlexAnimation> flexList = new ArrayList<>();
                    int flexCount = dis.readInt();
                    for (int f = 0; f < flexCount; f++) {
                        MdlFlexAnimation.FlexAnimation fa = new MdlFlexAnimation.FlexAnimation();
                        fa.flexDesc = dis.readInt();
                        for (int k = 0; k < 4; k++) fa.targets[k] = dis.readFloat();
                        fa.vertAnimType = dis.readInt();
                        fa.flexPair = dis.readInt();
                        int vertexCount = dis.readInt();
                        for (int v = 0; v < vertexCount; v++) {
                            MdlFlexAnimation.FlexVertex fv = new MdlFlexAnimation.FlexVertex();
                            fv.vertexIndex = dis.readInt();
                            for (int k = 0; k < 3; k++) fv.delta[k] = dis.readFloat();
                            for (int k = 0; k < 3; k++) fv.ndelta[k] = dis.readFloat();
                            fv.wrinkle = dis.readFloat();
                            fv.speed = dis.readByte();
                            fv.side = dis.readByte();
                            fa.vertices.add(fv);
                        }
                        flexList.add(fa);
                    }
                    data.meshFlexAnimations.add(flexList);
                }

                // 从缓存中恢复解码后的纹理像素，避免重新解析 VTF
                try {
                    String regKey;
                    while (!(regKey = dis.readUTF()).equals("__END__")) {
                        int w = dis.readInt();
                        int h = dis.readInt();
                        int len = dis.readInt();
                        int[] px = new int[len];
                        for (int i = 0; i < len; i++) px[i] = dis.readInt();
                        if (colorResolver.getRegistered(regKey) == null) {
                            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                            img.setRGB(0, 0, w, h, px, 0, w);
                            String key = regKey.substring("gmod_".length()).replace('_', '/');
                            ResourceLocation loc = registerTexture(key, img);
                            colorResolver.markComplete(regKey, loc, extractCenterPixelColor(img), false, false, false);
                        }
                    }
                    LOGGER.debug("[ModelLoadManager] Restored decoded textures from disk cache for {}", packageDir);
                } catch (Exception e) {
                    LOGGER.debug("[ModelLoadManager] Failed to restore decoded textures from cache (falling back to VTF re-parse): {}", e.getMessage());
                }

                // 确保所有 mesh 纹理在资源重载后能正确重新注册
                // 如果 DynamicTexture 因资源重载而被销毁，则 ensureTextureRegistered
                // 会为每个 mesh 的 texture 重新创建 DynamicTexture 并注册到 TextureManager
                for (SourceModelData.MeshData mesh : data.meshes) {
                    if (mesh.texture != null) {
                        colorResolver.ensureTextureRegistered(mesh.texture);
                    }
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
        String modelName = packageDir.getFileName() != null ? packageDir.getFileName().toString() : "model";
        ModelLoadProgress.setModelName(modelName);
        Path materialsDir = findMaterialsDir(packageDir);
        if (materialsDir == null || !Files.exists(materialsDir)) {
            LOGGER.warn("[ModelLoadManager] No materials dir for cached model, textures may be missing: {}", packageDir);
            return;
        }

        Map<String, VtfParser.VtfImageData> vtfDataMap = new HashMap<>();
        TextureColorResolver.TextureParseStateTracker tracker;

        try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".vtf"))
                .toList();

            tracker = new TextureColorResolver.TextureParseStateTracker(files.size());
            ModelLoadProgress.begin(ModelLoadProgress.Phase.TEXTURING, files.size());

            for (Path f : files) {
                String fileName = f.getFileName().toString();
                ModelLoadProgress.progress(fileName);
                String key = relativePath(materialsDir, f);
                String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_').replace('.', '_').toLowerCase(Locale.ROOT);
                try {
                    VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                    if (vtf.image != null) {
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
                    ModelLoadProgress.fail(fileName);
                    LOGGER.debug("[ModelLoadManager] Skipping VTF for re-register: {} - {}", f.getFileName(), e.getMessage());
                }
            }
            LOGGER.info("[ModelLoadManager] Scanned {} VTFs from materials dir (registry had {} entries) - {}",
                vtfDataMap.size(), colorResolver.getAllEntries().size(), tracker);
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to scan textures for cached model: {}", packageDir, e);
        }

        // Re-register textures using persisted vtfKey from mesh data
        int nullKeyCount = 0;
        int restoredCount = 0;
        for (SourceModelData.MeshData mesh : data.meshes) {
            if (mesh.texture == null) {
                nullKeyCount++;
                continue;
            }
            String texPath = mesh.texture.getPath();
            if (!texPath.startsWith("textures/generated/")) continue;
            String regKey = texPath.substring("textures/generated/".length());
            if (colorResolver.isRegistered(regKey)) continue;

            String lookupKey = mesh.vtfKey;
            if (lookupKey == null) {
                // Try to recover from path instead
                lookupKey = regKey.substring("gmod_".length()).replace('_', '/').toLowerCase(Locale.ROOT);
            }

            String persistedRegKey = "gmod_" + lookupKey.replace('/', '_').replace('\\', '_')
                .replace('.', '_').toLowerCase(Locale.ROOT);
            VtfParser.VtfImageData vtf = vtfDataMap.get(persistedRegKey);
            if (vtf != null && vtf.image != null) {
                registerTexture(lookupKey, vtf.image);
                restoredCount++;
            } else {
                // Last resort: search vtfDataMap by filename match
                String fileName = persistedRegKey.substring(persistedRegKey.lastIndexOf('_') + 1);
                boolean found = false;
                for (Map.Entry<String, VtfParser.VtfImageData> e : vtfDataMap.entrySet()) {
                    if (e.getKey().contains(fileName)) {
                        registerTexture(lookupKey, e.getValue().image);
                        restoredCount++;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    LOGGER.warn("[ModelLoadManager] Could not restore texture {} from cache", lookupKey);
                }
            }
        }
        if (nullKeyCount > 0 || restoredCount > 0) {
            LOGGER.info("[ModelLoadManager] Texture check for {}: {} null texture keys, {} restored",
                packageDir.getFileName(), nullKeyCount, restoredCount);
        }

        vtfDataMap.clear();

        ModelLoadProgress.reset();

        var finalStats = colorResolver.getStatistics();
        if (finalStats.hasFailures()) {
            LOGGER.warn("[ModelLoadManager] Texture parse state for cached model: {}", finalStats);
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

                dos.writeInt(data.bones.size());
                for (SourceModelData.BoneInfo bone : data.bones) {
                    dos.writeUTF(bone.name() != null ? bone.name() : "");
                    dos.writeFloat(bone.pos()[0]);
                    dos.writeFloat(bone.pos()[1]);
                    dos.writeFloat(bone.pos()[2]);
                    if (bone.quat() != null) { dos.writeBoolean(true); dos.writeFloat(bone.quat()[0]); dos.writeFloat(bone.quat()[1]); dos.writeFloat(bone.quat()[2]); dos.writeFloat(bone.quat()[3]); }
                    else { dos.writeBoolean(false); }
                    if (bone.rot() != null) { dos.writeBoolean(true); dos.writeFloat(bone.rot()[0]); dos.writeFloat(bone.rot()[1]); dos.writeFloat(bone.rot()[2]); }
                    else { dos.writeBoolean(false); }
                    dos.writeInt(bone.parent());
                }

                dos.writeInt(data.invBindMatrices.size());
                for (float[] invBind : data.invBindMatrices) {
                    for (float f : invBind) dos.writeFloat(f);
                }

                dos.writeInt(data.meshes.size());
                for (SourceModelData.MeshData mesh : data.meshes) {
                    dos.writeInt(mesh.vertices.length);
                    for (float v : mesh.vertices) dos.writeFloat(v);

                    dos.writeInt(mesh.indices.length);
                    for (int idx : mesh.indices) dos.writeInt(idx);

                    // Bone weight data
                    if (mesh.boneWeights != null) {
                        dos.writeBoolean(true);
                        dos.writeInt(mesh.boneWeights.length);
                        for (float w : mesh.boneWeights) dos.writeFloat(w);
                    } else { dos.writeBoolean(false); }
                    if (mesh.boneIndices != null) {
                        dos.writeBoolean(true);
                        dos.writeInt(mesh.boneIndices.length);
                        for (int bi : mesh.boneIndices) dos.writeInt(bi);
                    } else { dos.writeBoolean(false); }

                    dos.writeBoolean(mesh.translucent);
                    dos.writeBoolean(mesh.alphaTest);
                    dos.writeBoolean(mesh.noCull);
                    dos.writeBoolean(mesh.selfIllum);
                    dos.writeBoolean(mesh.hasPhong);
                    dos.writeBoolean(mesh.halfLambert);
                    dos.writeFloat(mesh.phongBoost);
                    dos.writeFloat(mesh.alpha);
                    dos.writeInt(mesh.detailBlendMode);
                    if (mesh.phongFresnelRanges != null && mesh.phongFresnelRanges.length >= 3) {
                        dos.writeBoolean(true);
                        dos.writeFloat(mesh.phongFresnelRanges[0]);
                        dos.writeFloat(mesh.phongFresnelRanges[1]);
                        dos.writeFloat(mesh.phongFresnelRanges[2]);
                    } else {
                        dos.writeBoolean(false);
                    }
                    if (mesh.surfaceProp != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.surfaceProp);
                    } else {
                        dos.writeBoolean(false);
                    }

                    if (mesh.texture != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.texture.getNamespace());
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.texture.getPath());
                    } else {
                        dos.writeBoolean(false);
                        dos.writeBoolean(false);
                    }

                    if (mesh.normalMap != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.normalMap.getNamespace());
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.normalMap.getPath());
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

                    // Serialize additional texture maps
                    writeResourceLocation(dos, mesh.ssbumpMap);
                    writeResourceLocation(dos, mesh.envMapMask);
                    writeResourceLocation(dos, mesh.parallaxMap);
                    writeResourceLocation(dos, mesh.detailMap);
                    writeResourceLocation(dos, mesh.selfIllumMask);
                    writeResourceLocation(dos, mesh.phongExponentTexture);

                    if (mesh.shaderType != null) {
                        dos.writeBoolean(true);
                        dos.writeUTF(mesh.shaderType);
                    } else {
                        dos.writeBoolean(false);
                    }
                }

                // Serialize reference bone transforms (srcBoneTransforms)
                dos.writeInt(data.srcBoneTransforms.size());
                for (MdlDataTypes.SrcBoneTransform bt : data.srcBoneTransforms) {
                    for (int i = 0; i < 3; i++) dos.writeFloat(bt.pos[i]);
                    for (int i = 0; i < 4; i++) dos.writeFloat(bt.quat[i]);
                    for (int i = 0; i < 3; i++) dos.writeFloat(bt.scale[i]);
                }

                // Serialize reference and A-pose sequence indices
                dos.writeInt(data.referenceSequenceIndices.size());
                for (int idx : data.referenceSequenceIndices) dos.writeInt(idx);
                dos.writeInt(data.aPoseSequenceIndices.size());
                for (int idx : data.aPoseSequenceIndices) dos.writeInt(idx);

                // Serialize sequence animation frame data (simplified: store frame 0 bone transforms per sequence)
                dos.writeInt(data.sequenceAnimData.size());
                for (MdlDataTypes.SequenceAnimData sa : data.sequenceAnimData) {
                    dos.writeBoolean(sa.isReference);
                    dos.writeBoolean(sa.isAPose);
                    dos.writeInt(sa.frames.size());
                    for (MdlDataTypes.AnimFrameData f : sa.frames) {
                        dos.writeInt(f.frame);
                        dos.writeInt(f.boneTransforms.size());
                        for (MdlDataTypes.AnimFrameBone fb : f.boneTransforms) {
                            dos.writeInt(fb.boneIndex);
                            dos.writeUTF(fb.boneName != null ? fb.boneName : "");
                            for (int i = 0; i < 3; i++) dos.writeFloat(fb.pos[i]);
                            for (int i = 0; i < 4; i++) dos.writeFloat(fb.quat[i]);
                            for (int i = 0; i < 3; i++) dos.writeFloat(fb.scale[i]);
                        }
                    }
                }

                // Serialize procedural bone metadata (parallel to bones).
                dos.writeInt(data.axisInterpBones.size());
                for (MdlProceduralBones.AxisInterpBone ab : data.axisInterpBones) {
                    dos.writeInt(ab.control);
                    dos.writeInt(ab.axis);
                    for (int j = 0; j < 6; j++) {
                        for (int k = 0; k < 3; k++) dos.writeFloat(ab.pos[j][k]);
                        for (int k = 0; k < 4; k++) dos.writeFloat(ab.quat[j][k]);
                    }
                }
                dos.writeInt(data.quatInterpBones.size());
                for (MdlProceduralBones.QuatInterpBone qb : data.quatInterpBones) {
                    dos.writeInt(qb.control);
                    dos.writeInt(qb.triggers.size());
                    for (MdlProceduralBones.QuatInterpTrigger t : qb.triggers) {
                        dos.writeFloat(t.invTolerance);
                        for (int k = 0; k < 4; k++) dos.writeFloat(t.trigger[k]);
                        for (int k = 0; k < 3; k++) dos.writeFloat(t.pos[k]);
                        for (int k = 0; k < 4; k++) dos.writeFloat(t.quat[k]);
                    }
                }
                dos.writeInt(data.jiggleBones.size());
                for (MdlProceduralBones.JiggleBone jb : data.jiggleBones) {
                    dos.writeInt(jb.flags);
                    dos.writeFloat(jb.length);
                    dos.writeFloat(jb.tipMass);
                    dos.writeFloat(jb.yawStiffness); dos.writeFloat(jb.yawDamping);
                    dos.writeFloat(jb.pitchStiffness); dos.writeFloat(jb.pitchDamping);
                    dos.writeFloat(jb.alongStiffness); dos.writeFloat(jb.alongDamping);
                    dos.writeFloat(jb.angleLimit);
                    dos.writeFloat(jb.minYaw); dos.writeFloat(jb.maxYaw);
                    dos.writeFloat(jb.yawFriction); dos.writeFloat(jb.yawBounce);
                    dos.writeFloat(jb.minPitch); dos.writeFloat(jb.maxPitch);
                    dos.writeFloat(jb.pitchFriction); dos.writeFloat(jb.pitchBounce);
                    dos.writeFloat(jb.baseMass); dos.writeFloat(jb.baseStiffness); dos.writeFloat(jb.baseDamping);
                    dos.writeFloat(jb.baseMinLeft); dos.writeFloat(jb.baseMaxLeft); dos.writeFloat(jb.baseLeftFriction);
                    dos.writeFloat(jb.baseMinUp); dos.writeFloat(jb.baseMaxUp); dos.writeFloat(jb.baseUpFriction);
                    dos.writeFloat(jb.baseMinForward); dos.writeFloat(jb.baseMaxForward); dos.writeFloat(jb.baseForwardFriction);
                    dos.writeFloat(jb.boingImpactSpeed); dos.writeFloat(jb.boingImpactAngle);
                    dos.writeFloat(jb.boingDampingRate); dos.writeFloat(jb.boingFrequency); dos.writeFloat(jb.boingAmplitude);
                }
                dos.writeInt(data.aimAtBones.size());
                for (MdlProceduralBones.AimAtBone ab : data.aimAtBones) {
                    dos.writeInt(ab.parent);
                    dos.writeInt(ab.aim);
                    for (int k = 0; k < 3; k++) dos.writeFloat(ab.aimvector[k]);
                    for (int k = 0; k < 3; k++) dos.writeFloat(ab.upvector[k]);
                    for (int k = 0; k < 3; k++) dos.writeFloat(ab.basepos[k]);
                }

                // Serialize per-sequence metadata (parallel to sequences).
                dos.writeInt(data.sequenceIKRules.size());
                for (List<MdlSequenceData.IKRule> rules : data.sequenceIKRules) {
                    dos.writeInt(rules.size());
                    for (MdlSequenceData.IKRule r : rules) {
                        dos.writeInt(r.chain); dos.writeInt(r.bone); dos.writeInt(r.slot); dos.writeInt(r.type);
                        dos.writeFloat(r.height); dos.writeFloat(r.radius); dos.writeFloat(r.floor);
                        for (int k = 0; k < 3; k++) dos.writeFloat(r.pos[k]);
                        for (int k = 0; k < 4; k++) dos.writeFloat(r.quat[k]);
                        dos.writeFloat(r.start); dos.writeFloat(r.peak); dos.writeFloat(r.tail); dos.writeFloat(r.end);
                        dos.writeFloat(r.contact); dos.writeFloat(r.drop); dos.writeFloat(r.top);
                        dos.writeUTF(r.attachment != null ? r.attachment : "");
                        for (int k = 0; k < 6; k++) dos.writeFloat(r.errorScale[k]);
                        for (int k = 0; k < 6; k++) dos.writeShort(r.errorOffset[k]);
                    }
                }
                dos.writeInt(data.sequenceAutolayers.size());
                for (List<MdlSequenceData.Autolayer> layers : data.sequenceAutolayers) {
                    dos.writeInt(layers.size());
                    for (MdlSequenceData.Autolayer al : layers) {
                        dos.writeShort(al.sequence); dos.writeShort(al.pose);
                        dos.writeInt(al.flags);
                        dos.writeFloat(al.start); dos.writeFloat(al.peak); dos.writeFloat(al.tail); dos.writeFloat(al.end);
                    }
                }
                dos.writeInt(data.sequenceActivityModifiers.size());
                for (List<MdlSequenceData.ActivityModifier> mods : data.sequenceActivityModifiers) {
                    dos.writeInt(mods.size());
                    for (MdlSequenceData.ActivityModifier am : mods) {
                        dos.writeUTF(am.name != null ? am.name : "");
                    }
                }
                dos.writeInt(data.sequenceMovements.size());
                for (List<MdlSequenceData.Movement> movs : data.sequenceMovements) {
                    dos.writeInt(movs.size());
                    for (MdlSequenceData.Movement mv : movs) {
                        dos.writeInt(mv.endframe); dos.writeInt(mv.motionflags);
                        dos.writeFloat(mv.v0); dos.writeFloat(mv.v1); dos.writeFloat(mv.angle);
                        for (int k = 0; k < 3; k++) dos.writeFloat(mv.vector[k]);
                        for (int k = 0; k < 3; k++) dos.writeFloat(mv.position[k]);
                    }
                }
                dos.writeInt(data.localHierarchies.size());
                for (MdlSequenceData.LocalHierarchy lh : data.localHierarchies) {
                    dos.writeInt(lh.bone); dos.writeInt(lh.newParent);
                    dos.writeFloat(lh.start); dos.writeFloat(lh.peak); dos.writeFloat(lh.tail); dos.writeFloat(lh.end);
                    dos.writeInt(lh.startFrame);
                }

                // Serialize per-mesh flex animation data (parallel to meshes).
                dos.writeInt(data.meshFlexAnimations.size());
                for (List<MdlFlexAnimation.FlexAnimation> flexList : data.meshFlexAnimations) {
                    dos.writeInt(flexList.size());
                    for (MdlFlexAnimation.FlexAnimation fa : flexList) {
                        dos.writeInt(fa.flexDesc);
                        for (int k = 0; k < 4; k++) dos.writeFloat(fa.targets[k]);
                        dos.writeInt(fa.vertAnimType);
                        dos.writeInt(fa.flexPair);
                        dos.writeInt(fa.vertices.size());
                        for (MdlFlexAnimation.FlexVertex fv : fa.vertices) {
                            dos.writeInt(fv.vertexIndex);
                            for (int k = 0; k < 3; k++) dos.writeFloat(fv.delta[k]);
                            for (int k = 0; k < 3; k++) dos.writeFloat(fv.ndelta[k]);
                            dos.writeFloat(fv.wrinkle);
                            dos.writeByte(fv.speed);
                            dos.writeByte(fv.side);
                        }
                    }
                }

                // 持久化解码后的纹理像素，使磁盘缓存命中时无需重新解析 VTF
                try {
                    Path materialsDir = findMaterialsDir(packageDir);
                    if (materialsDir != null && Files.exists(materialsDir)) {
                        try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
                            List<Path> files = walk.filter(Files::isRegularFile)
                                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".vtf"))
                                .toList();
                            for (Path f : files) {
                                String key = relativePath(materialsDir, f);
                                String regKey = "gmod_" + key.replace('/', '_').replace('\\', '_').replace('.', '_').toLowerCase(Locale.ROOT);
                                try {
                                    VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                                    if (vtf.image != null) {
                                        int w = vtf.image.getWidth();
                                        int h = vtf.image.getHeight();
                                        int[] px = new int[w * h];
                                        vtf.image.getRGB(0, 0, w, h, px, 0, w);
                                        dos.writeUTF(regKey);
                                        dos.writeInt(w);
                                        dos.writeInt(h);
                                        dos.writeInt(px.length);
                                        for (int p : px) dos.writeInt(p);
                                    }
                                } catch (Exception e) {
                                    LOGGER.debug("[ModelLoadManager] Skipping VTF for texture persistence: {} - {}", f.getFileName(), e.getMessage());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[ModelLoadManager] Texture persistence failed (geometry cache still saved): {}", e.getMessage());
                }
                dos.writeUTF("__END__");
            }
            Files.write(cacheFile, bos.toByteArray());
            LOGGER.debug("[ModelLoadManager] Saved model to disk cache: {} ({} bytes)", cacheFile.getFileName(), bos.size());
        } catch (Exception e) {
            LOGGER.warn("[ModelLoadManager] Failed to write disk cache for {}", packageDir, e);
        }
    }

    private static void writeResourceLocation(DataOutputStream dos, ResourceLocation loc) throws IOException {
        if (loc != null) {
            dos.writeBoolean(true);
            dos.writeUTF(loc.getNamespace());
            dos.writeBoolean(true);
            dos.writeUTF(loc.getPath());
        } else {
            dos.writeBoolean(false);
        }
    }

    private static ResourceLocation readResourceLocation(DataInputStream dis) throws IOException {
        if (dis.readBoolean()) {
            String namespace = dis.readBoolean() ? dis.readUTF() : null;
            String path = dis.readBoolean() ? dis.readUTF() : null;
            if (namespace != null && path != null) {
                return ResourceLocation.parse(namespace + ":" + path);
            }
        }
        return null;
    }

    public static void unloadModel(String cacheKey) {
        SourceModelData data = modelCache.remove(cacheKey);
        if (data != null) {
            long memBytes = estimateModelMemory(data);
            ModelLoadStatistics.recordModelMemoryFreed(memBytes);
            for (SourceModelData.MeshData mesh : data.meshes) {
                if (mesh.vtfKey != null) {
                    colorResolver.unregisterTexture(mesh.vtfKey);
                }
                if (mesh.texture != null) {
                    String texKey = mesh.texture.getPath();
                    if (texKey.startsWith("textures/generated/")) {
                        String regKey = texKey.substring("textures/generated/".length());
                        colorResolver.unregisterTexture(regKey);
                    }
                }
            }
            LOGGER.info("[ModelLoadManager] Unloaded model: {} ({} meshes freed, ~{} KB memory released)",
                cacheKey, data.meshes.size(), memBytes / 1024);
        }
    }

    public static void unloadAllModels() {
        Map<String, SourceModelData> snapshot;
        snapshot = Map.copyOf(modelCache);
        modelCache.clear();

        long totalFreed = 0;
        for (SourceModelData data : snapshot.values()) {
            if (data != null) {
                totalFreed += estimateModelMemory(data);
                for (SourceModelData.MeshData mesh : data.meshes) {
                    if (mesh.vtfKey != null) {
                        colorResolver.unregisterTexture(mesh.vtfKey);
                    }
                    if (mesh.texture != null) {
                        String texKey = mesh.texture.getPath();
                        if (texKey.startsWith("textures/generated/")) {
                            String regKey = texKey.substring("textures/generated/".length());
                            colorResolver.unregisterTexture(regKey);
                        }
                    }
                }
            }
        }
        ModelLoadStatistics.recordModelMemoryFreed(totalFreed);
        LOGGER.info("[ModelLoadManager] Unloaded all {} models and freed textures (~{} MB memory released)",
            snapshot.size(), String.format("%.1f", totalFreed / (1024.0 * 1024.0)));
    }

    public static TextureColorResolver getColorResolver() {
        return colorResolver;
    }

    public static ModelLoadStatistics getStatistics() {
        return new ModelLoadStatistics();
    }

    /**
     * Log current model loading statistics.
     */
    public static void logStatistics() {
        LOGGER.info("[ModelLoadManager] {}", ModelLoadStatistics.toSummaryString());
    }

    private static SourceModelData loadFromDirectory(Path packageDir, ModelParserStrategy strategy) throws IOException {
        if (!Files.exists(packageDir) || !Files.isDirectory(packageDir)) {
            throw new IOException("Model directory not found: " + packageDir);
        }

        // Collect all MDL/VVD/VTX files grouped by parent directory, so we only pick matched sets
        Map<Path, List<Path>> dirFiles = new HashMap<>();
        int maxDepth = 8;
        // Directories that never contain model geometry and must be skipped during the
        // walk (e.g. an addon's lua/autorun, materials/, scripts/). Walking into them can
        // pick up stray .mdl/.smd files and try to load them as the model, producing
        // spurious "Missing model files" errors and wasted work.
        // NOTE: "models" is intentionally NOT skipped — Source/GMod addons keep their
        // geometry under models/pm, models/npc, etc., and skipping it would make every
        // valid model report "Missing model files (mdl=false vvd=false vtx=false)".
        Set<String> SKIP_DIR_NAMES = Set.of("lua", "materials", "scripts", "sound", "particles", "resource");
        try (Stream<Path> files = Files.walk(packageDir, maxDepth)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path parent = f.getParent();
                if (parent != null) {
                    boolean inSkipDir = false;
                    for (int i = 0; i < parent.getNameCount(); i++) {
                        if (SKIP_DIR_NAMES.contains(parent.getName(i).toString().toLowerCase())) {
                            inSkipDir = true;
                            break;
                        }
                    }
                    if (inSkipDir) continue;
                }
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd") || name.endsWith(".bbmodel")) {
                    dirFiles.computeIfAbsent(parent, k -> new ArrayList<>()).add(f);
                }
            }
        }

        // Check for Blockbench .bbmodel file first
        Path bbmodelPath = null;
        for (Map.Entry<Path, List<Path>> entry : dirFiles.entrySet()) {
            for (Path f : entry.getValue()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".bbmodel")) {
                    bbmodelPath = f;
                    break;
                }
            }
            if (bbmodelPath != null) break;
        }

        if (bbmodelPath != null) {
            LOGGER.info("[ModelLoadManager] Found Blockbench model file: {}", bbmodelPath);
            ModelLoadProgress.setPhase(ModelLoadProgress.Phase.PARSING);
            try {
                SourceModelData data = BBModelParser.parse(bbmodelPath, packageDir);
                if (!data.meshes.isEmpty()) {
                    LOGGER.info("[ModelLoadManager] BBModel loaded: {} meshes, {} triangles, {} vertices",
                        data.meshes.size(), data.totalTriangles(), data.totalVertices());
                    ModelLoadProgress.reset();
                    return data;
                }
            } catch (Exception e) {
                LOGGER.warn("[ModelLoadManager] BBModel parse failed for {}, falling back: {}", bbmodelPath, e.getMessage());
            }
        }

        // Find the best MDL set: prefer directories that have all three file types together,
        // and prefer models closer to root (shallower depth).
        // Fallback to SMD files if no MDL/VVD/VTX trio is found.
        Path mdlPath = null, vvdPath = null, vtxPath = null, smdPath = null;
        int bestDepth = Integer.MAX_VALUE;
        int bestSmdDepth = Integer.MAX_VALUE;
        // Sibling sub-model folders (npc/arms/shoes) that hold a complete MDL trio at
        // depth 2 and are NOT the chosen main dir. Their meshes are merged into the
        // assembled character after the main model is built.
        List<Path> siblingDirs = new ArrayList<>();
        Path mainDir = null;
        for (Map.Entry<Path, List<Path>> entry : dirFiles.entrySet()) {
            Path mdlCandidate = null, vvdCandidate = null, vtxCandidate = null, smdCandidate = null;
            for (Path f : entry.getValue()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".mdl")) mdlCandidate = f;
                else if (name.endsWith(".vvd")) vvdCandidate = f;
                else if (name.endsWith(".dx90.vtx")) vtxCandidate = f;
                else if (name.endsWith(".smd")) smdCandidate = f;
            }
            int depth = packageDir.relativize(entry.getKey()).getNameCount();
            boolean complete = mdlCandidate != null && vvdCandidate != null && vtxCandidate != null;
            // Prefer MDL trio, fallback to SMD
            if (mdlCandidate != null) {
                boolean better = false;
                if (mdlPath == null) {
                    better = true;
                } else if (complete && (vvdPath == null || vtxPath == null)) {
                    better = true;
                } else if (complete == (vvdPath != null && vtxPath != null)) {
                    if (depth < bestDepth) better = true;
                }
                // Strongly prefer a "pm" subfolder as the MAIN model when present and complete.
                boolean isPm = entry.getKey().getFileName() != null
                    && "pm".equalsIgnoreCase(entry.getKey().getFileName().toString());
                if (isPm && complete) {
                    better = true;
                }
                if (better) {
                    mdlPath = mdlCandidate;
                    vvdPath = vvdCandidate;
                    vtxPath = vtxCandidate;
                    smdPath = null;
                    bestDepth = depth;
                    mainDir = entry.getKey();
                }
            } else if (smdCandidate != null) {
                if (smdPath == null || depth < bestSmdDepth) {
                    smdPath = smdCandidate;
                    mdlPath = null;
                    vvdPath = null;
                    vtxPath = null;
                    bestSmdDepth = depth;
                }
            }
            // Collect depth-2 complete MDL trios as potential siblings (excluding the main).
            if (depth == 2 && complete) {
                if (mainDir == null || !mainDir.equals(entry.getKey())) {
                    if (!siblingDirs.contains(entry.getKey())) {
                        siblingDirs.add(entry.getKey());
                    }
                }
            }
        }
        // Remove the chosen main dir from the sibling list (it may have been added above
        // before mainDir was finalized, e.g. when pm was selected).
        if (mainDir != null) {
            siblingDirs.remove(mainDir);
        }

        if (smdPath != null) {
            LOGGER.info("[ModelLoadManager] No MDL/VVD/VTX trio found, falling back to SMD: {}", smdPath);
            return loadFromSmd(packageDir, smdPath);
        }

        if (mdlPath == null || vvdPath == null || vtxPath == null) {
            throw new IOException("Missing model files in " + packageDir
                + " (mdl=" + (mdlPath != null) + " vvd=" + (vvdPath != null) + " vtx=" + (vtxPath != null) + ")");
        }
        LOGGER.info("[ModelLoadManager] Selected model set: mdl={} vvd={} vtx={}",
            packageDir.relativize(mdlPath), packageDir.relativize(vvdPath), packageDir.relativize(vtxPath));


        ModelLoadProgress.setPhase(ModelLoadProgress.Phase.PARSING);

        MdlDataTypes.ParsedModel mdl;
        VvdParser.ParsedVvd vvd;
        VtxParser.ParsedVtx vtx;

        if (strategy.isAvailable() && !(strategy instanceof JavaModelParserStrategy)) {
            mdl = strategy.parseMdl(Files.readAllBytes(mdlPath));
            vvd = strategy.parseVvd(Files.readAllBytes(vvdPath));
            vtx = strategy.parseVtx(Files.readAllBytes(vtxPath));
            LOGGER.info("[ModelLoadManager] Native parsing used for {} (parser: {})", packageDir, strategy.getPlatformName());
        } else {
            mdl = MdlParser.parse(Files.readAllBytes(mdlPath));
            vvd = VvdParser.parse(Files.readAllBytes(vvdPath));
            vtx = VtxParser.parse(Files.readAllBytes(vtxPath), vvd.vertices.size());
        }

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
                MdlDataTypes.BodyPart bp = mdl.bodyParts.get(i);
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
        List<String> allCdPrefixes = getStrings(mdl, luaCdMaterialsHints);

        // 统计材质文件数，用于纹理阶段进度追踪
        int textureFileCount = countTextureFiles(allMaterialsDirs);
        ModelLoadProgress.begin(ModelLoadProgress.Phase.TEXTURING, textureFileCount);
        Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap =
            loadTextures(mdl, primaryMaterialsDir, allMaterialsDirs, luaMaterialHints, allCdPrefixes);

        // 根据 VTX 网格数预估 BUILDING 阶段总量，在构建网格前设置进度追踪
        int estimatedMeshCount = vtx.meshTriangles.size();
        if (estimatedMeshCount <= 0) estimatedMeshCount = mdl.meshes.size();
        ModelLoadProgress.begin(ModelLoadProgress.Phase.BUILDING, estimatedMeshCount);
        SourceModelData result = buildSourceModelData(mdl, vvd, vtx, meshTextureMap,
            packageDir.getFileName().toString(), mdl.includeModels, includePath -> {
                Path includeDir = resolveIncludeModelPath(packageDir, includePath);
                if (includeDir != null && Files.exists(includeDir)) {
                    try {
                        SourceModelData subData = loadFromDirectory(includeDir, strategy);
                        if (!subData.meshes.isEmpty()) {
                            LOGGER.info("[ModelLoadManager] Merged {} meshes from sub-model: {}",
                                subData.meshes.size(), includePath);
                            return subData;
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[ModelLoadManager] Failed to load sub-model {}: {}",
                            includePath, e.getMessage());
                    }
                }
                return null;
            });

        // Merge sibling sub-model folders (npc/arms/shoes) into one assembled character.
        // Meshes only — sibling bones are independent rigs/props and must NOT be merged,
        // or skinning would bind meshes to the wrong bone.
        if (!siblingDirs.isEmpty()) {
            for (Path siblingDir : siblingDirs) {
                try {
                    SourceModelData subData = loadFromDirectory(siblingDir, strategy);
                    if (!subData.meshes.isEmpty()) {
                        result.meshes.addAll(subData.meshes);
                        LOGGER.info("[ModelLoadManager] Merged {} meshes from sibling: {}",
                            subData.meshes.size(), packageDir.relativize(siblingDir));
                    }
                } catch (Exception e) {
                    LOGGER.warn("[ModelLoadManager] Failed to merge sibling model {}: {}",
                        packageDir.relativize(siblingDir), e.getMessage());
                }
            }
            computeBounds(result);
        }

        if (result.meshes.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No meshes built from {}", packageDir);
        }

        // Register attachment items from parsed model data
        if (!result.meshes.isEmpty()) {
            try {
                String modelDirName = packageDir.getFileName().toString();
                AttachmentItemManager.registerAttachments(modelDirName, result);
                // Also register Forge items for any attachments found
                var attachments = AttachmentItemManager.getAttachments(modelDirName);
                for (var attInfo : attachments) {
                    NpcModelRegistry.registerAttachmentItem(attInfo.itemId, modelDirName, attInfo.name);
                }
            } catch (Exception e) {
                LOGGER.debug("[ModelLoadManager] Failed to register attachment items: {}", e.getMessage());
            }
        }

        return result;
    }

    private static @NotNull List<String> getStrings(MdlDataTypes.ParsedModel mdl, List<String> luaCdMaterialsHints) {
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
        return allCdPrefixes;
    }

    /**
     * Load a model from an SMD file (Studio Model Data, text-based Source Engine format).
     * SMD contains all geometry in a single file (unlike MDL which needs VVD+VTX companions).
     * Textures are resolved by scanning VMT/VTF files in materials directories.
     */
    private static SourceModelData loadFromSmd(Path packageDir, Path smdFile) throws IOException {
        LOGGER.info("[ModelLoadManager] Loading SMD model from: {}", smdFile);

        SmdParser.ParsedSmd smd = SmdParser.parse(smdFile);
        if (smd.meshes.isEmpty()) {
            throw new IOException("SMD file has no triangles: " + smdFile);
        }

        SourceModelData result = new SourceModelData();
        result.name = smdFile.getFileName().toString().replace(".smd", "");
        result.modelScale = 1.0f;

        // Build bone info from SMD nodes
        for (SmdParser.SmdBone bone : smd.bones) {
            result.bones.add(new SourceModelData.BoneInfo(bone.name,
                new float[]{0, 0, 0}, bone.parent));
        }

        // Scan Lua files for material hints
        List<String> luaMaterialHints = new ArrayList<>();
        List<String> luaCdMaterialsHints = new ArrayList<>();
        scanLuaForMaterialHints(packageDir, luaMaterialHints, luaCdMaterialsHints);

        // Find materials directories and scan VMT/VTF files
        List<Path> allMaterialsDirs = findAllMaterialsDirs(packageDir);

        Map<String, VtfParser.VtfImageData> vtfCache = new HashMap<>();
        Map<String, VmtParser.VmtMaterial> vmtCache = new HashMap<>();
        Map<String, BufferedImage> commonImageCache = new HashMap<>();

        scanMaterialsForSmd(allMaterialsDirs, vtfCache, vmtCache, commonImageCache);

        LOGGER.info("[ModelLoadManager] SMD texture scan: {} VMTs, {} VTFs, {} PNG/JPG",
            vmtCache.size(), vtfCache.size(), commonImageCache.size());

        // Build meshes from SMD triangles
        int meshIdx = 0;
        for (SmdParser.SmdMesh smdMesh : smd.meshes) {
            if (smdMesh.vertices.size() < 3) continue;

            List<Float> vertList = new ArrayList<>();
            List<Integer> idxList = new ArrayList<>();
            if (processSmdTriangles(smdMesh, vertList, idxList)) continue;

            float[] vertArray = new float[vertList.size()];
            for (int i = 0; i < vertList.size(); i++) vertArray[i] = vertList.get(i);
            int[] idxArray = new int[idxList.size()];
            for (int i = 0; i < idxList.size(); i++) idxArray[i] = idxList.get(i);

            // Resolve texture for this mesh based on material name
            ResourceLocation texture = null;
            ResourceLocation normalMap = null;
            boolean translucent = false;
            boolean alphaTest = false;
            boolean noCull = false;
            String vtfKey = null;
            String shaderType = null;

            String materialName = smdMesh.materialName;
            if (materialName != null && !materialName.isEmpty()) {
                String matNorm = materialName.replace('\\', '/').toLowerCase();
                if (matNorm.endsWith(".vtf") || matNorm.endsWith(".vmt")) {
                    matNorm = matNorm.substring(0, matNorm.length() - 4);
                }

                // Try to find matching VTF
                String matchedVtf = findVtfForBaseTexture(matNorm, vtfCache);
                if (matchedVtf == null) {
                    String simpleName = matNorm.contains("/")
                        ? matNorm.substring(matNorm.lastIndexOf('/') + 1) : matNorm;
                    for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
                        String key = e.getKey().toLowerCase();
                        String kSimple = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                        if (kSimple.equals(simpleName) || key.contains(simpleName)) {
                            matchedVtf = e.getKey();
                            break;
                        }
                    }
                }

                if (matchedVtf != null) {
                    VtfParser.VtfImageData vtf = vtfCache.get(matchedVtf);
                    if (vtf != null && vtf.image != null) {
                        texture = registerTexture(matchedVtf, vtf.image);
                        vtfKey = matchedVtf;
                        // Read VMT properties if available
                        VmtParser.VmtMaterial mat = vmtCache.get(matchedVtf);
                        if (mat == null) {
                            // Try to find VMT by matching path
                            for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
                                String fullBt = e.getValue().getFullBaseTexturePath();
                                if (fullBt != null && (fullBt.equals(matNorm) || fullBt.endsWith("/" + matNorm))) {
                                    mat = e.getValue();
                                    break;
                                }
                            }
                        }
                        if (mat != null) {
                            translucent = mat.isTransparent();
                            alphaTest = mat.isAlphaTest();
                            noCull = mat.isNoCull();
                            shaderType = mat.shader;
                            String bumpPath = mat.getBumpMap();
                            if (bumpPath != null && !bumpPath.isEmpty()) {
                                String bumpNorm = bumpPath.replace('\\', '/').toLowerCase();
                                if (bumpNorm.endsWith(".vtf")) bumpNorm = bumpNorm.substring(0, bumpNorm.length() - 4);
                                String nrmVtf = findVtfForBaseTexture(bumpNorm, vtfCache);
                                if (nrmVtf != null) {
                                    VtfParser.VtfImageData nrmImage = vtfCache.get(nrmVtf);
                                    if (nrmImage != null && nrmImage.image != null) {
                                        normalMap = registerTexture(nrmVtf, nrmImage.image);
                                    }
                                }
                            }
                        }
                    }
                }

                // Try Lua material hints as fallback
                if (texture == null && !luaMaterialHints.isEmpty()) {
                    for (String luaPath : luaMaterialHints) {
                        String luaNorm = luaPath.replace('\\', '/').toLowerCase();
                        if (luaNorm.endsWith(".vtf")) luaNorm = luaNorm.substring(0, luaNorm.length() - 4);
                        String luaMatch = findVtfForBaseTexture(luaNorm, vtfCache);
                        if (luaMatch != null) {
                            VtfParser.VtfImageData vtf = vtfCache.get(luaMatch);
                            if (vtf != null && vtf.image != null) {
                                texture = registerTexture(luaMatch, vtf.image);
                                vtfKey = luaMatch;
                                break;
                            }
                        }
                    }
                }

                // Last resort fallback: use first available VTF
                if (texture == null && !vtfCache.isEmpty()) {
                    String firstKey = vtfCache.keySet().iterator().next();
                    VtfParser.VtfImageData vtf = vtfCache.get(firstKey);
                    if (vtf != null && vtf.image != null) {
                        texture = registerTexture(firstKey, vtf.image);
                        vtfKey = firstKey;
                    }
                }
            }

            if (texture == null) {
                texture = ResourceLocation.parse("minecraft:textures/block/white_concrete.png");
            }

            result.meshes.add(new SourceModelData.MeshData.Builder()
                .vertices(vertArray).indices(idxArray)
                .texture(texture).normalMap(normalMap)
                .translucent(translucent).alphaTest(alphaTest).noCull(noCull)
                .bodyPartIndex(0).modelIndex(0).materialIndex(meshIdx)
                .vtfKey(vtfKey)
                .shaderType(shaderType)
                .build());

            meshIdx++;
        }

        LOGGER.info("[ModelLoadManager] Built {} meshes from SMD ({} triangles, {} vertices)",
            result.meshes.size(), result.totalTriangles(), result.totalVertices());

        // Compute bounding box and auto-scale
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
            LOGGER.info("[ModelLoadManager] SMD bounds: X=[{},{}] Y=[{},{}] Z=[{},{}] size={}x{}x{} autoScale={}",
                result.minX, result.maxX, result.minY, result.maxY, result.minZ, result.maxZ,
                sizeX, sizeY, sizeZ, result.modelScale);
        }

        return result;
    }

    private static void scanMaterialsForSmd(List<Path> materialsDirs,
            Map<String, VtfParser.VtfImageData> vtfCache,
            Map<String, VmtParser.VmtMaterial> vmtCache,
            Map<String, BufferedImage> commonImageCache) {
        for (Path materialsDir : materialsDirs) {
            if (materialsDir == null || !Files.exists(materialsDir)) continue;
            try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                for (Path f : files) {
                    String name = f.getFileName().toString().toLowerCase();
                    try {
                        String key = relativePath(materialsDir, f);
                        if (name.endsWith(".vmt")) {
                            VmtParser.VmtMaterial mat = VmtParser.parse(Files.readAllBytes(f));
                            if (!vmtCache.containsKey(key)) {
                                vmtCache.put(key, mat);
                            }
                        } else if (name.endsWith(".vtf")) {
                            try {
                                VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                                if (vtf.image != null) {
                                    if (!vtfCache.containsKey(key)) {
                                        vtfCache.put(key, vtf);
                                    }
                                    continue;
                                }
                            } catch (Exception e) {
                                LOGGER.debug("[ModelLoadManager] SMD VTF parse failed {}: {}", f.getFileName(), e.getMessage());
                            }
                            BufferedImage fallback = tryLoadVtfFallbackImage(f);
                            if (fallback != null) {
                                VtfParser.VtfImageData vtf = new VtfParser.VtfImageData();
                                vtf.width = fallback.getWidth();
                                vtf.height = fallback.getHeight();
                                vtf.image = fallback;
                                vtf.format = 0;
                                if (!vtfCache.containsKey(key)) {
                                    vtfCache.put(key, vtf);
                                }
                            }
                        } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                            if (!commonImageCache.containsKey(key)) {
                                BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
                                if (img != null) {
                                    commonImageCache.put(key, img);
                                    String vtfKey = key.replaceAll("\\.(png|jpg|jpeg)$", "");
                                    if (!vtfCache.containsKey(vtfKey)) {
                                        VtfParser.VtfImageData vtf = new VtfParser.VtfImageData();
                                        vtf.width = img.getWidth();
                                        vtf.height = img.getHeight();
                                        vtf.image = img;
                                        vtf.format = 0;
                                        vtfCache.put(vtfKey, vtf);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("[ModelLoadManager] SMD scan error {}: {}", f.getFileName(), e.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("[ModelLoadManager] Error scanning SMD materials dir {}: {}", materialsDir, e.getMessage());
            }
        }
        // Free image memory
        for (BufferedImage img : commonImageCache.values()) {
            img.flush();
        }
        commonImageCache.clear();
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

    private static List<SourceModelData.MeshData> buildMeshesForLod(
        MdlDataTypes.ParsedModel mdl,
        VvdParser.ParsedVvd vvd,
        VtxParser.ParsedVtx vtx,
        int lodLevel,
        Map<Integer, SourceModelData.MeshTextureInfo> meshTextureMap
    ) {
        List<SourceModelData.MeshData> meshes = new ArrayList<>();
        List<VvdParser.StudioVertexExt> vvdVerts = vvd.vertices;
        if (vvdVerts.isEmpty()) {
            LOGGER.warn("[ModelLoadManager] No VVD vertices available");
            return meshes;
        }

        List<List<VtxParser.VtxTriangle>> vtxTrianglesForLod = VtxParser.getTrianglesForLod(vtx, lodLevel);
        int vtxMeshCount = vtxTrianglesForLod.size();
        int mdlMeshCount = mdl.meshes.size();
        int mdlModelCount = mdl.models.size();
        LOGGER.info("[ModelLoadManager] buildMeshes: VVD vertices={}, VTX meshes={}, MDL meshes={}, MDL models={}",
            vvdVerts.size(), vtxMeshCount, mdlMeshCount, mdlModelCount);

        if (vtxMeshCount != mdlMeshCount) {
            LOGGER.warn("[ModelLoadManager] MESH COUNT MISMATCH: VTX={} vs MDL={} - triangles may be misaligned!",
                vtxMeshCount, mdlMeshCount);
        }

        // Walk bodyparts -> models -> meshes in LOCKSTEP with the VTX meshTriangles list,
        // which is ordered identically by (bodypart -> model -> LOD0 mesh), one entry per
        // mesh. We keep TWO cursors that advance exactly once per mesh consumed:
        //   - vtxMeshCursor  : index into vtx.meshTriangles (also used as the key for
        //                      meshTextureMap, which is keyed by VTX mesh index)
        //   - mdlMeshCursor  : index into mdl.meshes (the aligned MDL mesh for this mesh)
        // Because both lists share the same nested ordering, walking them in lockstep keeps
        // each VTX mesh aligned with the correct MDL mesh even when a bodypart has multiple
        // models or MDL model ordering differs from VTX ordering. A single flat counter that
        // indexed mdl.meshes by the VTX cursor desynced the two lists and pulled vertices
        // from the wrong VVD block -> garbage/scattered triangles.
        int vtxMeshCursor = 0;
        int mdlMeshCursor = 0;
        // VVD vertices are tightly packed in MDL model order when there are no fixup
        // tables (fixups.isEmpty()). In that case the correct per-model base is the
        // running sum of preceding models' numvertices, NOT model.vertexindex (which is
        // an offset inside the .mdl file, unrelated to the .vvd vertex layout). Using
        // model.vertexindex as a VVD index pulls every model after the first from the
        // wrong vertex block and mangles the whole mesh.
        // When fixup tables ARE present, fall back to the original model.vertexindex math
        // (those models need a separate, fixup-aware path that is out of scope here).
        boolean vvdTightlyPacked = vvd.fixups.isEmpty();
        int vvdAccumBase = 0;
        // Track whether each bodypart already has an active model. In Source Engine,
        // bodygroup models are mutually exclusive alternatives (e.g. "weapon_none",
        // "weapon_pistol", "weapon_rifle"). Only the first model per bodypart should
        // emit geometry; subsequent ones are bodygroup variants that would overlap.
        // The VTX / MDL mesh cursors still advance across all models to maintain
        // lockstep, but mesh output is skipped for non-primary bodygroup models.
        boolean[] bodygroupActiveModelSeen = new boolean[mdl.bodyParts.size()];
        for (int bpIdx = 0; bpIdx < mdl.bodyParts.size(); bpIdx++) {
            for (int modelIdx = 0; modelIdx < mdlModelCount; modelIdx++) {
                MdlDataTypes.Model model = mdl.models.get(modelIdx);
                if (model.bodypartIndex != bpIdx) continue;

                boolean shadowOnly = bodygroupActiveModelSeen[bpIdx];
                if (model.nummeshes > 0) {
                    bodygroupActiveModelSeen[bpIdx] = true;
                }
                if (shadowOnly) {
                    LOGGER.debug("[ModelLoadManager] Bodygroup variant model[{}] (bpIdx={}): advancing cursors without emitting geometry",
                        modelIdx, bpIdx);
                }

                int vvdModelBase;
                if (vvdTightlyPacked) {
                    vvdModelBase = vvdAccumBase;
                } else {
                    // Legacy path for fixup-bearing VVDs: derive base from the MDL offset.
                    int rawBase = model.vertexindex - vvd.header.vertexDataStart;
                    if (rawBase < 0) rawBase = 0;
                    vvdModelBase = rawBase / VVD_VERTEX_SIZE;
                }
                // Clamp into the actual VVD vertex range as a safety net.
                int vvdVertexCount = vvdVerts.size();
                if (vvdModelBase < 0) vvdModelBase = 0;
                if (vvdModelBase > vvdVertexCount) vvdModelBase = vvdVertexCount;

                for (int meshLocalIdx = 0; meshLocalIdx < model.nummeshes; meshLocalIdx++) {
                    int globalMeshIdx = vtxMeshCursor++;
                    int alignedMdlMeshIdx = mdlMeshCursor++;

                    if (shadowOnly) {
                        // Bodygroup variant: cursors already advanced to maintain lockstep,
                        // but skip mesh output so alternative variants don't overlap.
                        continue;
                    }

                    // Pull vertexoffset / numvertices / material from the ALIGNED MDL mesh,
                    // never from a flat globalMeshIdx into mdl.meshes.
                    int vertexOffset = (alignedMdlMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(alignedMdlMeshIdx).vertexoffset : 0;
                    int meshNumVertices = (alignedMdlMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(alignedMdlMeshIdx).numvertices : 0;

                    List<VtxParser.VtxTriangle> tris = (globalMeshIdx < vtxMeshCount) ?
                        vtxTrianglesForLod.get(globalMeshIdx) : new ArrayList<>();

                    if (tris.isEmpty()) continue;

                    // meshTextureMap is keyed by VTX mesh index, so use the VTX cursor.
                    SourceModelData.MeshTextureInfo texInfo = meshTextureMap.get(globalMeshIdx);

                    if (meshLocalIdx == 0) {
                        LOGGER.debug("[ModelLoadManager] Model[{}] vvdModelBase={}, numMeshes={}",
                            modelIdx, vvdModelBase, model.nummeshes);
                    }
                    LOGGER.debug("[ModelLoadManager] Mesh[vtx={} mdl={} model={}.{}] vvdModelBase={} vertOffset={} meshNumVerts={} tris={}",
                        globalMeshIdx, alignedMdlMeshIdx, modelIdx, meshLocalIdx, vvdModelBase, vertexOffset, meshNumVertices, tris.size());

                    // Validate VTX vertex IDs against MDL mesh vertex count
                    if (meshNumVertices > 0) {
                        int maxVtxVertId = 0;
                        for (VtxParser.VtxTriangle tri : tris) {
                            maxVtxVertId = Math.max(maxVtxVertId, Math.max(tri.v0, Math.max(tri.v1, tri.v2)));
                        }
                        if (maxVtxVertId >= meshNumVertices) {
                            LOGGER.warn("[ModelLoadManager] Mesh[vtx={} mdl={}] VTX origMeshVertID {} exceeds MDL mesh.numvertices {}",
                                globalMeshIdx, alignedMdlMeshIdx, maxVtxVertId, meshNumVertices);
                        }
                        // Validate using the same candidate resolution as below.
                        // Prefer the base+offset form (mesh-relative VTX ids); fall
                        // back to the raw absolute index only if that is out of range.
                        int maxVvdIdx = -1;
                        int adjusted = vvdModelBase + vertexOffset + maxVtxVertId;
                        if (adjusted >= 0 && adjusted < vvdVerts.size()) {
                            maxVvdIdx = adjusted;
                        } else if (maxVtxVertId < vvdVerts.size()) {
                            maxVvdIdx = maxVtxVertId;
                        }
                        if (maxVvdIdx >= vvdVerts.size()) {
                            LOGGER.warn("[ModelLoadManager] Mesh[vtx={} mdl={}] Max VVD index {} exceeds VVD vertex count {} (vvdModelBase={} vertOffset={} maxVtxId={})",
                                globalMeshIdx, alignedMdlMeshIdx, maxVvdIdx, vvdVerts.size(), vvdModelBase, vertexOffset, maxVtxVertId);
                        }
                    }

                List<Float> vertList = new ArrayList<>();
                List<Integer> idxList = new ArrayList<>();
                List<Float> bwList = new ArrayList<>();
                List<Integer> biList = new ArrayList<>();
                Map<Integer, Integer> vertCache = new HashMap<>();
                int oobCount = 0;
                int maxVvdIdx = -1;

                for (VtxParser.VtxTriangle tri : tris) {
                    int vvdIdx0 = resolveVvdIndex(tri.v0, vvdModelBase, vertexOffset, vvdVerts.size(), vvd.fixups);
                    int vvdIdx1 = resolveVvdIndex(tri.v1, vvdModelBase, vertexOffset, vvdVerts.size(), vvd.fixups);
                    int vvdIdx2 = resolveVvdIndex(tri.v2, vvdModelBase, vertexOffset, vvdVerts.size(), vvd.fixups);
                    maxVvdIdx = Math.max(maxVvdIdx, Math.max(vvdIdx0, Math.max(vvdIdx1, vvdIdx2)));

                    if (vvdIdx0 < 0 || vvdIdx0 >= vvdVerts.size() ||
                        vvdIdx1 < 0 || vvdIdx1 >= vvdVerts.size() ||
                        vvdIdx2 < 0 || vvdIdx2 >= vvdVerts.size()) {
                        oobCount++;
                        if (!vvd.fixups.isEmpty()) {
                            LOGGER.warn("[ModelLoadManager] Mesh[vtx={} mdl={}] fixup remap failed for triangle (v0={}->{} v1={}->{} v2={}->{}); skipping to avoid wrong geometry",
                                globalMeshIdx, alignedMdlMeshIdx, tri.v0, vvdIdx0, tri.v1, vvdIdx1, tri.v2, vvdIdx2);
                        }
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

                        int numBones = Math.min(sv.boneWeight.numbones, 3);
                        for (int b = 0; b < 4; b++) {
                            if (b < numBones) {
                                bwList.add(sv.boneWeight.weight[b]);
                                biList.add(sv.boneWeight.bone[b]);
                            } else {
                                bwList.add(b == 0 ? 1.0f : 0.0f);
                                biList.add(0);
                            }
                        }

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
                    float[] boneWtArray = new float[bwList.size()];
                    int[] boneIdxArray = new int[biList.size()];
                    for (int i = 0; i < bwList.size(); i++) {
                        boneWtArray[i] = bwList.get(i);
                        boneIdxArray[i] = biList.get(i);
                    }

                    boolean translucent = false;
                    boolean alphaTest = false;
                    boolean noCull = false;
                    boolean selfIllum = false;
                    boolean hasPhong = false;
                    boolean halfLambert = false;
                    float phongBoost = 0.0f;
                    float[] phongFresnelRanges = null;
                    ResourceLocation texture = null;
                    ResourceLocation normalMap = null;
                    ResourceLocation ssbumpMap = null;
                    ResourceLocation envMapMask = null;
                    ResourceLocation parallaxMap = null;
                    ResourceLocation detailMap = null;
                    ResourceLocation selfIllumMask = null;
                    ResourceLocation phongExponentTexture = null;
                    String vtfKey = null;
                    float[] colorTint = null;
                    float alpha = 1.0f;
                    String shaderType = null;
                    String surfaceProp = null;
                    int detailBlendMode = 0;

                    if (texInfo != null) {
                        texture = texInfo.texture;
                        normalMap = texInfo.normalMap;
                        ssbumpMap = texInfo.ssbumpMap;
                        envMapMask = texInfo.envMapMask;
                        parallaxMap = texInfo.parallaxMap;
                        detailMap = texInfo.detailMap;
                        selfIllumMask = texInfo.selfIllumMask;
                        translucent = texInfo.translucent;
                        alphaTest = texInfo.alphaTest;
                        noCull = texInfo.noCull;
                        selfIllum = texInfo.selfIllum;
                        hasPhong = texInfo.hasPhong;
                        halfLambert = texInfo.halfLambert;
                        phongBoost = texInfo.phongBoost;
                        phongFresnelRanges = texInfo.phongFresnelRanges;
                        phongExponentTexture = texInfo.phongExponentTexture;
                        vtfKey = texInfo.vtfKey;
                        colorTint = texInfo.colorTint;
                        alpha = texInfo.alpha;
                        surfaceProp = texInfo.surfaceProp;
                        detailBlendMode = texInfo.detailBlendMode;
                        shaderType = texInfo.shaderType;
                    }

                    int materialIdx = (alignedMdlMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(alignedMdlMeshIdx).material : -1;

                    meshes.add(new SourceModelData.MeshData.Builder()
                        .vertices(vertArray).indices(idxArray)
                        .boneWeights(boneWtArray).boneIndices(boneIdxArray)
                        .texture(texture).normalMap(normalMap)
                        .ssbumpMap(ssbumpMap).envMapMask(envMapMask)
                        .parallaxMap(parallaxMap).detailMap(detailMap).selfIllumMask(selfIllumMask)
                        .translucent(translucent).alphaTest(alphaTest).noCull(noCull)
                        .selfIllum(selfIllum).hasPhong(hasPhong).halfLambert(halfLambert)
                        .phongBoost(phongBoost).phongFresnelRanges(phongFresnelRanges)
                        .phongExponentTexture(phongExponentTexture)
                        .bodyPartIndex(model.bodypartIndex).modelIndex(modelIdx).materialIndex(materialIdx)
                        .vtfKey(vtfKey).colorTint(colorTint).alpha(alpha)
                        .surfaceProp(surfaceProp).detailBlendMode(detailBlendMode)
                        .shaderType(shaderType)
                        .build());
                }
                ModelLoadProgress.progress();
            }
            if (vvdTightlyPacked) {
                vvdAccumBase += model.numvertices;
            }
            // NOTE: for tightly-packed VVDs, vvdModelBase advances by model.numvertices
            // each model (see vvdAccumBase above). For fixup-bearing VVDs it is recomputed
            // per model from model.vertexindex.
            }
        }

        return meshes;
    }

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

    /**
     * Resolve a VTX origMeshVertID to a VVD vertex array index.
     * In standard Source VTX files the origMeshVertID is a per-mesh vertex offset:
     * it starts at 0 for each mesh and must be combined with the model's VVD base
     * plus the mesh's vertexoffset to reach the raw VVD vertex array. Only models
     * exported by some tools store absolute VVD indices in the strip; treat that as
     * a fallback (try base+offset first, then the absolute form). Returns -1 if no
     * combination resolves into range.
     */
    private static int resolveVvdIndex(int origMeshVertId, int vvdModelBase, int vertexOffset,
                                        int vvdVertexCount, List<VvdParser.VvdFixup> fixups) {
        if (origMeshVertId < 0) return -1;
        // Fixup-aware remap (standard Source VVD fixup): the VTX origMeshVertID is
        // a sequential index across the concatenation of all fixup vertex blocks. Walk
        // the fixups subtracting each block's vertex count until the id falls inside
        // one, then map it into the raw VVD vertex array via that fixup's sourceVertexID.
        if (fixups != null && !fixups.isEmpty()) {
            int id = origMeshVertId;
            for (VvdParser.VvdFixup f : fixups) {
                if (f.numVertexes <= 0) continue;
                if (id < f.numVertexes) {
                    int raw = f.sourceVertexID + id;
                    return (raw >= 0 && raw < vvdVertexCount) ? raw : -1;
                }
                id -= f.numVertexes;
            }
            return -1;
        }
        // No fixups: origMeshVertID is a mesh-relative offset, so the primary mapping
        // is vvdModelBase + vertexOffset + origMeshVertId. Only when that lands out of
        // range treat the id as an absolute VVD index (some decompiled models do this).
        int adjusted = vvdModelBase + vertexOffset + origMeshVertId;
        if (adjusted >= 0 && adjusted < vvdVertexCount) {
            return adjusted;
        }
        if (origMeshVertId >= 0 && origMeshVertId < vvdVertexCount) {
            return origMeshVertId;
        }
        return -1;
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
     * 5. VPK material caches (.vpk_mtl_cache) at ancestor levels
     * 6. VPK extraction caches (.vpk_cache) at ancestor levels
     */
    static List<Path> findAllMaterialsDirs(Path packageDir) {
        List<Path> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Direct child
        Path directChild = packageDir.resolve("materials");
        if (Files.exists(directChild) && Files.isDirectory(directChild)) {
            String key = directChild.toAbsolutePath().toString();
            seen.add(key);
            results.add(directChild);
        }

        // 2. Ancestor walk (up to 10 levels) - also checks .vpk_mtl_cache and .vpk_cache
        Path parent = packageDir.getParent();
        int depth = 0;
        while (parent != null && depth < 10) {
            // Check standard materials/ directory
            Path candidate = parent.resolve("materials");
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                String key = candidate.toAbsolutePath().toString();
                if (seen.add(key)) results.add(candidate);
            }
            // Check .vpk_mtl_cache at this level
            Path vpkCache = parent.resolve(".vpk_mtl_cache");
            if (Files.exists(vpkCache) && Files.isDirectory(vpkCache)) {
                try (Stream<Path> cacheDirs = Files.list(vpkCache)) {
                    cacheDirs.filter(Files::isDirectory)
                        .forEach(subDir -> {
                            Path matDir = subDir.resolve("materials");
                            if (Files.exists(matDir) && Files.isDirectory(matDir)) {
                                String key = matDir.toAbsolutePath().toString();
                                if (seen.add(key)) {
                                    synchronized (results) { results.add(matDir); }
                                }
                            }
                        });
                } catch (IOException ignored) {}
            }
            // Check .vpk_cache for extracted materials
            Path vpkExtract = parent.resolve(".vpk_cache");
            if (Files.exists(vpkExtract) && Files.isDirectory(vpkExtract)) {
                try (Stream<Path> cacheDirs = Files.list(vpkExtract)) {
                    cacheDirs.filter(Files::isDirectory)
                        .forEach(subDir -> {
                            Path matDir = subDir.resolve("materials");
                            if (Files.exists(matDir) && Files.isDirectory(matDir)) {
                                String key = matDir.toAbsolutePath().toString();
                                if (seen.add(key)) {
                                    synchronized (results) { results.add(matDir); }
                                }
                            }
                        });
                } catch (IOException ignored) {}
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

    private static boolean isValidPathString(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
            if (c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') return false;
        }
        return true;
    }

    private static Path resolveIncludeModelPath(Path packageDir, String includePath) {
        if (!isValidPathString(includePath)) {
            LOGGER.warn("[ModelLoadManager] Invalid include model path (contains non-printable or illegal characters), skipping: {}", includePath);
            return null;
        }
        String cleaned = includePath.replace('\\', '/');
        if (cleaned.endsWith(".mdl")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }

        // Strategy 1: Resolve relative to packageDir (covers models inside addon package)
        // e.g. packageDir=.../models/MyModel, includePath=models/arms/foo
        //   -> .../models/MyModel/models/arms/foo
        Path candidate = packageDir.resolve(cleaned);
        if (Files.exists(candidate) && Files.isDirectory(candidate)) {
            LOGGER.info("[ModelLoadManager] Resolved include model (package-relative): {} -> {}", includePath, candidate);
            return candidate;
        }

        // Strategy 2: Strip "models/" prefix and resolve under packageDir/models/
        String stripped = cleaned;
        if (stripped.startsWith("models/") || stripped.startsWith("models\\")) {
            stripped = stripped.substring("models/".length());
        }
        candidate = packageDir.resolve("models").resolve(stripped);
        if (Files.exists(candidate) && Files.isDirectory(candidate)) {
            LOGGER.info("[ModelLoadManager] Resolved include model (package-models-relative): {} -> {}", includePath, candidate);
            return candidate;
        }

        // Strategy 3: Resolve relative to global models root (sibling package directories)
        Path modelsRoot = packageDir.getParent();
        if (modelsRoot != null) {
            candidate = modelsRoot.resolve(stripped);
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                LOGGER.info("[ModelLoadManager] Resolved include model (models-root-relative): {} -> {}", includePath, candidate);
                return candidate;
            }
            // Try global models root with original path
            candidate = modelsRoot.resolve(cleaned);
            if (Files.exists(candidate) && Files.isDirectory(candidate)) {
                LOGGER.info("[ModelLoadManager] Resolved include model (models-root-original): {} -> {}", includePath, candidate);
                return candidate;
            }
        }

        // Strategy 4: Try as sibling of current loaded model directory
        String[] parts = stripped.split("/");
        for (int i = 0; i <= Math.min(parts.length, 3); i++) {
            Path prefix = modelsRoot;
            for (int j = 0; j < parts.length - i; j++) {
                prefix = prefix.resolve(parts[j]);
            }
            if (Files.exists(prefix) && Files.isDirectory(prefix) && hasAnyModelFile(prefix)) {
                LOGGER.info("[ModelLoadManager] Resolved include model (sibling-walk): {} -> {}", includePath, prefix);
                return prefix;
            }
        }

        LOGGER.warn("[ModelLoadManager] Could not resolve include model: {}", includePath);
        return null;
    }

    private static boolean hasAnyModelFile(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase();
                return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd") || name.endsWith(".bbmodel");
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 快速统计材质目录中可用于进度追踪的材质文件（VMT/VTF/PNG/JPG）数量。
     * 不解析文件内容，仅做文件名模式匹配。
     */
    private static int countTextureFiles(List<Path> materialsDirs) {
        if (materialsDirs == null || materialsDirs.isEmpty()) return 0;
        int count = 0;
        for (Path dir : materialsDirs) {
            if (dir == null || !Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir, 8)) {
                count += (int) walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith(".vmt") || n.endsWith(".vtf")
                            || n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                    })
                    .count();
            } catch (IOException ignored) {
            }
        }
        return count;
    }

    private static Map<Integer, SourceModelData.MeshTextureInfo> loadTextures(
            MdlDataTypes.ParsedModel mdl, Path primaryMaterialsDir,
            List<Path> allMaterialsDirs, List<String> luaMaterialHints,
            List<String> cdPrefixes) {
        Map<Integer, SourceModelData.MeshTextureInfo> meshTexMap = new HashMap<>();

        Map<String, VtfParser.VtfImageData> vtfCache = new HashMap<>();
        Map<String, VmtParser.VmtMaterial> vmtCache = new HashMap<>();
        // Fallback common format textures (PNG/JPG) when VTF is unavailable
        Map<String, BufferedImage> commonImageCache = new HashMap<>();

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
                    // 标记进度：只跟踪材质相关文件
                    boolean isTextureFile = name.endsWith(".vmt") || name.endsWith(".vtf")
                        || name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
                    try {
                        if (isTextureFile) {
                            ModelLoadProgress.progress(f.getFileName().toString());
                        }
                        if (name.endsWith(".vmt")) {
                            vmtCount++;
                            VmtParser.VmtMaterial mat = VmtParser.parse(Files.readAllBytes(f));
                            String key = relativePath(materialsDir, f);
                            if (!vmtCache.containsKey(key)) {
                                vmtCache.put(key, mat);
                            }
                        } else if (name.endsWith(".vtf")) {
                            vtfCount++;
                            String key = relativePath(materialsDir, f);
                            try {
                                VtfParser.VtfImageData vtf = VtfParser.parse(Files.readAllBytes(f));
                                if (vtf.image != null) {
                                    if (!vtfCache.containsKey(key)) {
                                        vtfCache.put(key, vtf);
                                    }
                                    continue;
                                }
                            } catch (Exception e) {
                                LOGGER.debug("[ModelLoadManager] VTF parse failed {}: {}", f.getFileName(), e.getMessage());
                            }
                            // VTF failed - try fallback: look for same-named PNG/JPG
                            vtfFailCount++;
                            BufferedImage fallback = tryLoadVtfFallbackImage(f);
                            if (fallback != null) {
                                VtfParser.VtfImageData vtf = new VtfParser.VtfImageData();
                                vtf.width = fallback.getWidth();
                                vtf.height = fallback.getHeight();
                                vtf.image = fallback;
                                vtf.format = 0;
                                if (!vtfCache.containsKey(key)) {
                                    vtfCache.put(key, vtf);
                                    LOGGER.info("[ModelLoadManager] PNG/JPG fallback for VTF: {} ({}x{})", f.getFileName(), fallback.getWidth(), fallback.getHeight());
                                }
                            }
                        } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                            String key = relativePath(materialsDir, f);
                            if (!commonImageCache.containsKey(key)) {
                                BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
                                if (img != null) {
                                    commonImageCache.put(key, img);
                                    // Also add to vtfCache as a fallback candidate
                                    String vtfKey = key.replaceAll("\\.(png|jpg|jpeg)$", "");
                                    if (!vtfCache.containsKey(vtfKey)) {
                                        VtfParser.VtfImageData vtf = new VtfParser.VtfImageData();
                                        vtf.width = img.getWidth();
                                        vtf.height = img.getHeight();
                                        vtf.image = img;
                                        vtf.format = 0;
                                        vtfCache.put(vtfKey, vtf);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (name.endsWith(".vmt")) vmtFailCount++;
                        else if (name.endsWith(".vtf")) vtfFailCount++;
                        LOGGER.debug("[ModelLoadManager] Failed to process texture file {}: {}", f.getFileName(), e.toString());
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

        LOGGER.info("[ModelLoadManager] Texture scan: {} VMTs, {} VTFs, {} PNG/JPG from {} directories",
            vmtCache.size(), vtfCache.size(), commonImageCache.size(), dirsToScan.size());

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

        // Build reverse map from VMT key to VTF key (via $basetexture + $cdmaterials)
        Map<String, String> vmtKeyToVtfKey = getStringStringMap(vmtCache, vtfCache);

        // Count renderable VTFs (excluding vgui) for progress reporting
        long renderableVtfCount = vtfCache.keySet().stream()
            .filter(k -> !k.contains("vgui"))  // Skip UI textures
            .count();
        LOGGER.info("[ModelLoadManager] Found {} renderable VTFs (excluding vgui)", renderableVtfCount);

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
                    allCdPrefixes,
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

        // Free image memory after textures are registered to Minecraft's texture manager
        for (VtfParser.VtfImageData vtf : vtfCache.values()) {
            vtf.image = null;
        }
        vtfCache.clear();
        for (BufferedImage img : commonImageCache.values()) {
            img.flush();
        }
        commonImageCache.clear();
        vmtCache.clear();

        return meshTexMap;
    }

    private static @NotNull Map<String, String> getStringStringMap(Map<String, VmtParser.VmtMaterial> vmtCache, Map<String, VtfParser.VtfImageData> vtfCache) {
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
        return vmtKeyToVtfKey;
    }

    private static String relativePath(Path base, Path full) {
        String rel = base.relativize(full).toString().replace('\\', '/');
        if (rel.endsWith(".vmt") || rel.endsWith(".vtf")) {
            rel = rel.substring(0, rel.length() - 4);
        }
        return rel;
    }

    private static SourceModelData.MeshTextureInfo resolveMeshTexture(
        MdlDataTypes.ParsedModel mdl, int meshIdx,
        Map<String, VmtParser.VmtMaterial> vmtCache,
        Map<String, VtfParser.VtfImageData> vtfCache,
        List<String> cdPrefixes,
        Map<String, String> vmtKeyToVtfKey,
        List<String> luaMaterialHints
    ) {
        if (meshIdx >= mdl.meshes.size()) return null;

        String texName = getString(mdl, meshIdx);

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
                            return buildFullMeshTextureInfo(mat, loc, vtfMatch, vtfCache, cdPrefixes);
                        }
                        return SourceModelData.MeshTextureInfo.simple(loc, null, false, false, false, vtfMatch, null);
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
                                return buildFullMeshTextureInfo(e.getValue(), loc, vtfKey, vtfCache, cdPrefixes);
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
                                    return buildFullMeshTextureInfo(mat, loc, vtfKey, vtfCache, cdPrefixes);
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
                                    return buildFullMeshTextureInfo(mat, loc, vtfKey, vtfCache, cdPrefixes);
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
                        return SourceModelData.MeshTextureInfo.simple(loc, null, false, false, false, e.getKey(), null);
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
                    return SourceModelData.MeshTextureInfo.simple(loc, null, false, false, false, e.getKey(), null);
                }
            }

            // NOTE: No fuzzy substring matching is performed here. Substring
            // containment (e.g. "hand" matching "hand_s", "Chiffon_Body" matching
            // "Chiffon_Body1") caused wrong textures to be assigned to meshes.
            // Exact path / filename matches above are sufficient and safe.
        }

        // Strategy CT (Critical): Direct VTF key match on normalized texture name
        if (!texName.isEmpty()) {
            String cleanTexName = texName;
            if (cleanTexName.endsWith(".vtf") || cleanTexName.endsWith(".vmt")) {
                cleanTexName = cleanTexName.substring(0, cleanTexName.length() - 4);
            }
            String normalized = cleanTexName.replace('\\', '/').toLowerCase();

            List<String> allCandidates = new ArrayList<>();
            allCandidates.add(normalized);
            for (String cdPrefix : cdPrefixes) {
                if (!cdPrefix.isEmpty() && !normalized.startsWith(cdPrefix)) {
                    allCandidates.add(cdPrefix + normalized);
                }
            }
            String simpleName = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;

            for (String candidate : allCandidates) {
                String matchedVtfKey = findVtfForBaseTexture(candidate, vtfCache);
                if (matchedVtfKey == null && !simpleName.isEmpty()) {
                    for (String vk : vtfCache.keySet()) {
                        String kLower = vk.toLowerCase();
                        String kSimple = kLower.contains("/")
                            ? kLower.substring(kLower.lastIndexOf('/') + 1) : kLower;
                        if (kSimple.equals(simpleName)) {
                            matchedVtfKey = vk;
                            break;
                        }
                    }
                }
                if (matchedVtfKey != null) {
                    VmtParser.VmtMaterial mat = findVmtForTexture(candidate, vmtCache);
                    VtfParser.VtfImageData vtf = vtfCache.get(matchedVtfKey);
                    if (vtf != null && vtf.image != null) {
                        ResourceLocation loc = registerTexture(matchedVtfKey, vtf.image);
                        if (mat != null) {
                            return buildFullMeshTextureInfo(mat, loc, matchedVtfKey, vtfCache, cdPrefixes);
                        }
                        return SourceModelData.MeshTextureInfo.simple(loc, null, false, false, false, matchedVtfKey, null);
                    }
                }
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
                            return buildFullMeshTextureInfo(mat, loc, entry.getValue(), vtfCache, cdPrefixes);
                        }
                        return SourceModelData.MeshTextureInfo.simple(loc, null, false, false, false, entry.getValue(), null);
                    }
                }
            }
        }

        // Strategy F2: Absolute last resort - white texture
        ResourceLocation whiteTex = ResourceLocation.parse("minecraft:textures/block/white_concrete.png");
        return SourceModelData.MeshTextureInfo.simple(whiteTex, null, false, false, false, null, null);
    }

    private static @NotNull String getString(MdlDataTypes.ParsedModel mdl, int meshIdx) {
        int materialIdx = mdl.meshes.get(meshIdx).material;
        int texIndex = materialIdx;

        if (!mdl.skinTable.isEmpty() && mdl.header.numskinref > 0) {
            int tableIdx = materialIdx >= 0 ? materialIdx % mdl.header.numskinref : 0;
            if (tableIdx < mdl.skinTable.size()) {
                texIndex = mdl.skinTable.get(tableIdx);
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
        return texName;
    }

    /**
     * Build a full MeshTextureInfo from a VmtMaterial, resolving all texture maps.
     */
    private static SourceModelData.MeshTextureInfo buildFullMeshTextureInfo(
            VmtParser.VmtMaterial mat, ResourceLocation loc, String vtfKey,
            Map<String, VtfParser.VtfImageData> vtfCache, List<String> cdPrefixes) {
        ResourceLocation normalMap = findNormalMap(mat, vtfCache, cdPrefixes);
        ResourceLocation ssbumpMap = findTextureMap(mat.getSsBump(), vtfCache, cdPrefixes);
        ResourceLocation envMapMask = findTextureMap(mat.getEnvMapMask(), vtfCache, cdPrefixes);
        ResourceLocation parallaxMap = findTextureMap(mat.getParallaxMap(), vtfCache, cdPrefixes);
        ResourceLocation detailMap = findTextureMap(mat.getDetail(), vtfCache, cdPrefixes);
        ResourceLocation selfIllumMask = findTextureMap(mat.getSelfIllumMask(), vtfCache, cdPrefixes);
        ResourceLocation phongExponentTex = findTextureMap(mat.getPhongExponentTexture(), vtfCache, cdPrefixes);
        float[] colorTint = mat.getColor2();
        if (colorTint == null) colorTint = mat.getColor();
        SourceModelData.MeshTextureInfo info = new SourceModelData.MeshTextureInfo(loc, normalMap,
            ssbumpMap, envMapMask, parallaxMap, detailMap, selfIllumMask,
            mat.isTransparent(), mat.isAlphaTest(), mat.isNoCull(),
            mat.isSelfIllum(), mat.hasPhong(), mat.isHalfLambert(),
            mat.getPhongBoost(), mat.getPhongFresnelRanges(), phongExponentTex,
            vtfKey, colorTint, mat.getAlpha(),
            mat.getSurfaceProp(), mat.getDetailBlendMode());
        info.shaderType = mat.shader;
        return info;
    }

    /**
     * Generic texture map resolver: given a texture path from VMT, find and register the matching VTF.
     */
    private static ResourceLocation findTextureMap(String texPath, Map<String, VtfParser.VtfImageData> vtfCache, List<String> cdPrefixes) {
        if (texPath == null || texPath.isEmpty()) return null;
        String texNorm = texPath.replace('\\', '/').toLowerCase();
        if (texNorm.endsWith(".vtf")) texNorm = texNorm.substring(0, texNorm.length() - 4);

        // Try direct match
        String vtfKey = findVtfForBaseTexture(texNorm, vtfCache);
        if (vtfKey != null) {
            VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
            if (vtf != null && vtf.image != null) {
                return registerTexture(vtfKey, vtf.image);
            }
        }

        // Try with cdPrefixes
        for (String cdPrefix : cdPrefixes) {
            if (!cdPrefix.isEmpty()) {
                String fullPath = cdPrefix + texNorm;
                vtfKey = findVtfForBaseTexture(fullPath, vtfCache);
                if (vtfKey != null) {
                    VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
                    if (vtf != null && vtf.image != null) {
                        return registerTexture(vtfKey, vtf.image);
                    }
                }
            }
        }

        // Fuzzy match by filename
        String simpleName = texNorm.contains("/") ? texNorm.substring(texNorm.lastIndexOf('/') + 1) : texNorm;
        for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
            String key = e.getKey().toLowerCase();
            if (key.contains(simpleName) || key.endsWith("/" + simpleName)) {
                return registerTexture(e.getKey(), e.getValue().image);
            }
        }

        return null;
    }

    /**
     * 查找法线贴图以供那些可以开光影的情况下使用或者是你最明白的 光 污 染*/
    private static ResourceLocation findNormalMap(VmtParser.VmtMaterial mat, Map<String, VtfParser.VtfImageData> vtfCache, List<String> cdPrefixes) {
        if (mat == null) return null;
        
        String bumpMap = mat.getBumpMap();
        if (bumpMap == null || bumpMap.isEmpty()) return null;
        
        String bumpNorm = bumpMap.replace('\\', '/').toLowerCase();
        if (bumpNorm.endsWith(".vtf")) bumpNorm = bumpNorm.substring(0, bumpNorm.length() - 4);
        
        // 尝试直接匹配
        String vtfKey = findVtfForBaseTexture(bumpNorm, vtfCache);
        if (vtfKey != null) {
            VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
            if (vtf != null && vtf.image != null) {
                return registerTexture(vtfKey, vtf.image);
            }
        }
        
        // 尝试使用cdPrefixes匹配
        for (String cdPrefix : cdPrefixes) {
            if (!cdPrefix.isEmpty()) {
                String fullBump = cdPrefix + bumpNorm;
                vtfKey = findVtfForBaseTexture(fullBump, vtfCache);
                if (vtfKey != null) {
                    VtfParser.VtfImageData vtf = vtfCache.get(vtfKey);
                    if (vtf != null && vtf.image != null) {
                        return registerTexture(vtfKey, vtf.image);
                    }
                }
            }
        }
        
        // 尝试模糊匹配
        String simpleName = bumpNorm.contains("/") ? bumpNorm.substring(bumpNorm.lastIndexOf('/') + 1) : bumpNorm;
        for (Map.Entry<String, VtfParser.VtfImageData> e : vtfCache.entrySet()) {
            String key = e.getKey().toLowerCase();
            if (key.contains(simpleName) || key.endsWith("/" + simpleName)) {
                return registerTexture(e.getKey(), e.getValue().image);
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

    /**
     * When a VTF file fails to parse, try to find a same-named PNG/JPG file as fallback.
     */
    private static BufferedImage tryLoadVtfFallbackImage(Path vtfPath) {
        String baseName = vtfPath.getFileName().toString();
        if (baseName.endsWith(".vtf")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        Path parent = vtfPath.getParent();
        if (parent == null) return null;

        // Try PNG first, then JPG, then JPEG
        String[] extensions = {".png", ".jpg", ".jpeg"};
        for (String ext : extensions) {
            Path candidate = parent.resolve(baseName + ext);
            if (Files.exists(candidate)) {
                try (InputStream is = Files.newInputStream(candidate)) {
                    BufferedImage img = javax.imageio.ImageIO.read(is);
                    if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                        return img;
                    }
                } catch (Exception e) {
                    LOGGER.debug("[ModelLoadManager] Failed to read fallback image: {} - {}", candidate, e.getMessage());
                }
            }
        }
        return null;
    }

    private static ResourceLocation registerTexture(String key, BufferedImage image) {
        // Build a key that stays distinct per actual source texture. The previous
        // implementation collapsed '/' and '.' to '_', so two textures whose paths
        // differed only by separators/extension collided and the first registered
        // won, giving the second mesh the wrong texture. We keep the original path
        // distinct and append a hash of the original key to guarantee uniqueness.
        String normalizedKey = key.toLowerCase(Locale.ROOT).replace('\\', '/').replace(' ', '_');
        String regKey = "gmod_" + normalizedKey + "_" + Integer.toHexString(key.hashCode());

        ResourceLocation existing = colorResolver.getRegistered(regKey);
        if (existing != null) return existing;

        ResourceLocation loc = ResourceLocation.parse("transferstation_whimsicalideas:textures/generated/" + regKey);

        try {
            NativeImage nativeImage = TextureColorResolver.bufferedImageToNativeImage(image);
            int color = extractCenterPixelColor(image);
            Minecraft mc = Minecraft.getInstance();
            if (mc.isSameThread()) {
                // 复用已有 DynamicTexture 实例（applyNativeImage 内部处理），
                // 避免重新注册关闭旧实例导致 flipFrame 时 NPE。
                colorResolver.applyNativeImage(loc, nativeImage);
                colorResolver.markComplete(regKey, loc, color, false, false, false, nativeImage);
            } else {
                mc.execute(() -> {
                    colorResolver.applyNativeImage(loc, nativeImage);
                    colorResolver.markComplete(regKey, loc, color, false, false, false, nativeImage);
                });
            }
            LOGGER.debug("[ModelLoadManager] Registered texture: {} ({}x{})", loc, image.getWidth(), image.getHeight());
            return loc;
        } catch (Exception e) {
            colorResolver.markFailed(regKey, e.getMessage());
            LOGGER.warn("[ModelLoadManager] Failed to register texture {}: {}", regKey, e.getMessage());
            return ResourceLocation.parse("minecraft:textures/block/white_concrete.png");
        }
    }

    /*
      Load a model's entity-selection icon from materials/vgui/entities/ inside
      the model package. Valve's convention maps a model path like
      "models/player/soldier.mdl" to "materials/vgui/entities/player_soldier.vtf"
      (slashes -> underscores, extension stripped). Several candidate names are
      tried; returns null if no icon is found (caller renders without one).
     */
    /**
     * Detects whether a package directory contains multiple model subfolders
     * (e.g. models/pm, models/npc, models/arms, models/shoes) that should be
     * merged into one assembled character. Used to skip the native single-trio
     * loader for such packages.
     */
    public static boolean isMultiSubmodelPackage(Path packageDir) {
        if (packageDir == null || !Files.isDirectory(packageDir)) return false;
        Set<String> SKIP = Set.of("lua", "materials", "scripts", "sound", "particles", "resource");
        int count = 0;
        try (Stream<Path> files = Files.walk(packageDir, 8)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path parent = f.getParent();
                if (parent == null) continue;
                boolean skip = false;
                for (int i = 0; i < parent.getNameCount(); i++) {
                    if (SKIP.contains(parent.getName(i).toString().toLowerCase())) { skip = true; break; }
                }
                if (skip) continue;
                String n = f.getFileName().toString().toLowerCase();
                if (n.endsWith(".mdl") && packageDir.relativize(parent).getNameCount() == 2) {
                    count++;
                }
            }
        } catch (IOException ignored) {}
        return count >= 2;
    }

    public static ResourceLocation loadEntityIcon(Path packageDir, String modelName) {
        try {
            // Prefer the "pm" subfolder's materials so the selection icon shows the
            // player model rather than an arbitrary sibling (e.g. shoes).
            Path pmDir = packageDir.resolve("pm");
            if (Files.isDirectory(pmDir)) {
                Path pmMaterials = findMaterialsDir(pmDir);
                if (pmMaterials != null) {
                    ResourceLocation pmIcon = tryLoadIconFromMaterials(pmMaterials, modelName);
                    if (pmIcon != null) return pmIcon;
                }
            }
            Path materialsDir = findMaterialsDir(packageDir);
            if (materialsDir == null) return null;
            return tryLoadIconFromMaterials(materialsDir, modelName);
        } catch (Exception e) {
            // Icon is optional; ignore any failure and let the caller render without it.
            LOGGER.debug("[ModelLoadManager] Failed to load entity icon for {}: {}", modelName, e.getMessage());
        }
        return null;
    }

    /**
     * Try to load the entity-selection icon from a package's materials directory.
     * Valve's convention maps a model path like "models/player/soldier.mdl" to
     * "materials/vgui/entities/player_soldier.vtf" (slashes -> underscores,
     * extension stripped). Several candidate names are tried; returns null if no
     * icon is found.
     */
    private static ResourceLocation tryLoadIconFromMaterials(Path materialsDir, String modelName) {
        try {
            Path vguiDir = materialsDir.resolve("vgui").resolve("entities");
            if (!Files.isDirectory(vguiDir)) return null;

            List<String> candidates = getStrings(modelName);

            for (String cand : candidates) {
                for (String ext : new String[]{".vtf", ".png"}) {
                    Path iconPath = vguiDir.resolve(cand + ext);
                    if (!Files.isRegularFile(iconPath)) continue;
                    BufferedImage img = null;
                    if (ext.equals(".vtf")) {
                        img = VtfParser.parseToBufferedImage(Files.readAllBytes(iconPath));
                    } else {
                        try {
                            img = javax.imageio.ImageIO.read(iconPath.toFile());
                        } catch (Exception ignored) {}
                    }
                    if (img != null) {
                        return registerTexture("vguiicon_" + cand, img);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[ModelLoadManager] Failed to load icon from {}: {}", materialsDir, e.getMessage());
        }
        return null;
    }

    private static @NotNull List<String> getStrings(String modelName) {
        String name = modelName;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mdl") || lower.endsWith(".smd")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        String slashToUnderscore = name.replace('/', '_').replace('\\', '_');
        String lastSegment = slashToUnderscore.contains("_")
                ? slashToUnderscore.substring(slashToUnderscore.lastIndexOf('_') + 1)
                : slashToUnderscore;

        List<String> candidates = new ArrayList<>();
        candidates.add(slashToUnderscore);
        candidates.add("models_" + slashToUnderscore);
        candidates.add(lastSegment);
        return candidates;
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
}
