package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MdlModelRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // LOD distance thresholds with hysteresis support
    private static float lod1Distance = 20.0f;
    private static float lod2Distance = 40.0f;
    private static float lod3Distance = 60.0f;
    private static float lodHysteresis = 2.0f;

    // Occlusion culling grid (32x32x32 world cells)
    private static final int OCCLUSION_GRID_SIZE = 32;
    private static final float OCCLUSION_CELL_SIZE = 16.0f;
    private static final Map<Long, Boolean> occlusionCache = new ConcurrentHashMap<>();
    private static long lastOcclusionCacheClear = 0;
    private static final long OCCLUSION_CACHE_CLEAR_INTERVAL_MS = 5000;

    // Render statistics
    private static final LongAdder totalDrawCalls = new LongAdder();
    private static final LongAdder totalTrianglesRendered = new LongAdder();
    private static final LongAdder totalEntitiesRendered = new LongAdder();
    private static final LongAdder totalEntitiesCulledFrustum = new LongAdder();
    private static final LongAdder totalEntitiesCulledOcclusion = new LongAdder();
    private static final LongAdder totalLod0 = new LongAdder();
    private static final LongAdder totalLod1 = new LongAdder();
    private static final LongAdder totalLod2 = new LongAdder();
    private static final LongAdder totalLod3 = new LongAdder();
    private static volatile long frameStartNano = 0;
    private static volatile long lastFrameTimeMs = 0;

    // Per-frame snapshot for external readers
    private static volatile RenderStats lastFrameStats = null;

    // Frustum culling AABB expansion factor (safety margin in model-space units)
    private static float frustumExpansion = 1.0f;

    // Batch rendering: group entities by model name for potential instanced rendering
    private static final Map<String, List<LivingEntity>> batchGroups = new ConcurrentHashMap<>();
    private static boolean batchEnabled = true;

    public static void setLodDistances(float near, float mid, float far) {
        lod1Distance = near;
        lod2Distance = mid;
        lod3Distance = far;
    }

    public static void setLodHysteresis(float hysteresis) {
        lodHysteresis = Math.max(0.0f, hysteresis);
    }

    public static void setBatchRendering(boolean enabled) {
        batchEnabled = enabled;
    }

    public static void setFrustumExpansion(float expansion) {
        frustumExpansion = Math.max(0.0f, expansion);
    }

    public static float getFrustumExpansion() {
        return frustumExpansion;
    }

    /**
     * Immutable snapshot of per-frame render statistics.
     */
    public record RenderStats(
        long drawCalls,
        long trianglesRendered,
        long entitiesRendered,
        long entitiesCulledFrustum,
        long entitiesCulledOcclusion,
        long lod0Count,
        long lod1Count,
        long lod2Count,
        long lod3Count,
        long frameTimeMs
    ) {
        public float entitiesPerSecond() {
            return frameTimeMs > 0 ? (entitiesRendered * 1000.0f / frameTimeMs) : 0;
        }

        public float trianglesPerFrame() {
            return entitiesRendered > 0 ? (float) trianglesRendered / entitiesRendered : 0;
        }

        @Override
        public String toString() {
            return String.format(
                "RenderStats{draws=%d, tris=%d, entities=%d, frustumCulled=%d, occCulled=%d, " +
                "lod0=%d, lod1=%d, lod2=%d, lod3=%d, frameMs=%d}",
                drawCalls, trianglesRendered, entitiesRendered,
                entitiesCulledFrustum, entitiesCulledOcclusion,
                lod0Count, lod1Count, lod2Count, lod3Count, frameTimeMs);
        }
    }

    /**
     * Called at the start of each render frame to reset per-frame counters.
     */
    public static void beginFrame() {
        frameStartNano = System.nanoTime();
        totalDrawCalls.reset();
        totalTrianglesRendered.reset();
        totalEntitiesRendered.reset();
        totalEntitiesCulledFrustum.reset();
        totalEntitiesCulledOcclusion.reset();
        totalLod0.reset();
        totalLod1.reset();
        totalLod2.reset();
        totalLod3.reset();
    }

    /**
     * Called at the end of each render frame to snapshot the stats.
     */
    public static void endFrame() {
        long now = System.nanoTime();
        long frameMs = (now - frameStartNano) / 1_000_000;
        lastFrameTimeMs = frameMs;
        lastFrameStats = new RenderStats(
            totalDrawCalls.sum(),
            totalTrianglesRendered.sum(),
            totalEntitiesRendered.sum(),
            totalEntitiesCulledFrustum.sum(),
            totalEntitiesCulledOcclusion.sum(),
            totalLod0.sum(),
            totalLod1.sum(),
            totalLod2.sum(),
            totalLod3.sum(),
            frameMs
        );

        // Periodically clear occlusion cache to avoid stale data
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastOcclusionCacheClear > OCCLUSION_CACHE_CLEAR_INTERVAL_MS) {
            occlusionCache.clear();
            lastOcclusionCacheClear = nowMs;
        }
    }

    /**
     * Get the render stats from the last completed frame. May be null on first frame.
     */
    public static RenderStats getLastFrameStats() {
        return lastFrameStats;
    }

    /**
     * Reset all cumulative statistics (call at session start or when you want fresh totals).
     */
    public static void resetStats() {
        totalDrawCalls.reset();
        totalTrianglesRendered.reset();
        totalEntitiesRendered.reset();
        totalEntitiesCulledFrustum.reset();
        totalEntitiesCulledOcclusion.reset();
        totalLod0.reset();
        totalLod1.reset();
        totalLod2.reset();
        totalLod3.reset();
        lastFrameStats = null;
    }

    // NOTE: We deliberately do NOT auto-free native handles inside removeEldestEntry.
    // Freeing from whatever thread triggers a put could race with the render thread,
    // which may still be using that handle (use-after-free). Native handles are freed
    // explicitly on the main thread in unloadCurrent()/unloadAll() only.
    private static final Map<String, Long> nativeHandleCache = Collections.synchronizedMap(new LinkedHashMap<>());

    private static volatile String currentModelName = null;
    private static Path modelsDir = null;

    // Use WeakHashMap to prevent entity memory leak
    private static final Map<LivingEntity, String> entityModelMap = new WeakHashMap<>();
    private static final ReentrantReadWriteLock entityMapLock = new ReentrantReadWriteLock();

    // Per-entity LOD state for hysteresis
    private static final Map<LivingEntity, Integer> entityLodState = new WeakHashMap<>();

    private static final Random RANDOM = new Random();

    // Async loading executor
    private static final ExecutorService LOADING_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
        r -> {
            Thread t = new Thread(r, "ModelLoader");
            t.setDaemon(true);
            return t;
        }
    );

    // Loading futures for in-progress loads
    private static final Map<String, CompletableFuture<Void>> pendingLoads = new ConcurrentHashMap<>();

    // Models where native load was attempted but failed (handle == 0). Prevents
    // re-scheduling a CompletableFuture every frame for models that cannot be
    // loaded natively (e.g. multi-submodel packages). Cleared on unloadAll().
    private static final Set<String> nativeLoadFailed = ConcurrentHashMap.newKeySet();

    // Per-model Java data cache (key = modelName)
    private static final Map<String, SourceModelData> javaModelDataCache = new ConcurrentHashMap<>();

    // Per-entity animation state
    private static final Map<LivingEntity, float[]> entityBoneMatrices = new WeakHashMap<>();

    // Fallback animation state
    private static float fallbackAnimTime = 0.0f;

    public static void setModelsDir(Path dir) {
        modelsDir = dir;
    }

    public static Path getModelsDir() {
        return modelsDir;
    }

    public static void setCacheDir(Path dir) {
        ModelLoadManager.setCacheDir(dir);
    }

    public static void setCurrentModel(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            unloadCurrent();
            currentModelName = null;
            return;
        }
        if (!modelName.equals(currentModelName)) {
            unloadCurrent();
            currentModelName = modelName;
        }
    }

    private static void unloadCurrent() {
        if (currentModelName == null) return;
        Long handle = nativeHandleCache.remove(currentModelName);
        if (handle != null && GmodNativeBridge.isAvailable()) {
            GmodNativeBridge.nativeFreeModel(handle);
        }
        nativeLoadFailed.remove(currentModelName);
        javaModelDataCache.remove(currentModelName);
        if (modelsDir != null) {
            Path packageDir = modelsDir.resolve(currentModelName);
            String cacheKey = packageDir.toAbsolutePath().toString();
            ModelLoadManager.unloadModel(cacheKey);
        }
        JavaModelRenderer.setModelData(null);
        JavaModelRenderer.clearAllEntityModels();
    }

    public static void unloadAll() {
        for (Long handle : nativeHandleCache.values()) {
            if (handle != null && GmodNativeBridge.isAvailable()) {
                GmodNativeBridge.nativeFreeModel(handle);
            }
        }
        nativeHandleCache.clear();
        nativeLoadFailed.clear();
        javaModelDataCache.clear();
        ModelLoadManager.unloadAllModels();
        JavaModelRenderer.setModelData(null);
        JavaModelRenderer.clearAllEntityModels();
        currentModelName = null;
        entityMapLock.writeLock().lock();
        try {
            entityModelMap.clear();
        } finally {
            entityMapLock.writeLock().unlock();
        }
    }

    public static void shutdown() {
        unloadAll();
        LOADING_EXECUTOR.shutdownNow();
        pendingLoads.clear();
    }

    public static String getCurrentModel() {
        return currentModelName;
    }

    public static SourceModelData getJavaModelData(String modelName) {
        if (modelName == null) return null;
        return javaModelDataCache.get(modelName);
    }

    public static void setJavaModelData(String modelName, SourceModelData data) {
        if (modelName != null && data != null) {
            javaModelDataCache.put(modelName, data);
        }
    }

    public static String getEntityModel(LivingEntity entity) {
        entityMapLock.readLock().lock();
        try {
            return entityModelMap.get(entity);
        } finally {
            entityMapLock.readLock().unlock();
        }
    }

    public static void setEntityModel(LivingEntity entity, String modelName) {
        entityMapLock.writeLock().lock();
        try {
            entityModelMap.put(entity, modelName);
        } finally {
            entityMapLock.writeLock().unlock();
        }
    }

    public static void clearEntityModel(LivingEntity entity) {
        entityMapLock.writeLock().lock();
        try {
            entityModelMap.remove(entity);
        } finally {
            entityMapLock.writeLock().unlock();
        }
    }

    public static String getRandomModel() {
        if (modelsDir == null || !java.nio.file.Files.exists(modelsDir)) {
            return currentModelName;
        }
        try (var dirs = java.nio.file.Files.list(modelsDir)) {
            java.util.List<String> models = dirs
                .filter(java.nio.file.Files::isDirectory)
                .filter(dir -> !dir.equals(modelsDir))
                .filter(MdlModelRenderer::hasAnyModelFile)
                .map(dir -> modelsDir.relativize(dir).toString().replace('\\', '/'))
                .toList();
            if (models.isEmpty()) {
                return currentModelName;
            }
            return models.get(RANDOM.nextInt(models.size()));
        } catch (IOException e) {
            return currentModelName;
        }
    }

    private static boolean hasAnyModelFile(Path dir) {
        try (var files = java.nio.file.Files.list(dir)) {
            return files.anyMatch(f -> {
                String name = f.getFileName().toString().toLowerCase();
                return name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd") || name.endsWith(".bbmodel");
            });
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isModelLoaded() {
        if (currentModelName == null) return false;
        if (GmodNativeBridge.isAvailable()) {
            return nativeHandleCache.containsKey(currentModelName) || JavaModelRenderer.hasModel();
        }
        return JavaModelRenderer.hasModel();
    }

    private static final Set<String> modelLoadInProgress = ConcurrentHashMap.newKeySet();

    public static void loadModel(Path modelsDir, String modelName) throws IOException {
        if (modelName == null || modelName.isEmpty()) return;

        String loadKey = modelsDir + ":" + modelName;
        if (!modelLoadInProgress.add(loadKey)) {
            LOGGER.debug("[MdlModelRenderer] Model '{}' is already being loaded by another thread", modelName);
            return;
        }

        try {
            Path packageDir = modelsDir.resolve(modelName);
            if (!java.nio.file.Files.exists(packageDir)) {
                throw new IOException("Model package directory not found: " + modelName);
            }

            try {
                transferstation.transferstation_whimsicalideas.client.ClientNotifications.showModelLoadStarted(modelName);
            } catch (Exception ignored) {}

            ModelParserStrategy currentParserStrategy = ModelParserProvider.getStrategy();
            LOGGER.info("[MdlModelRenderer] Loading model '{}' using parser: {}", modelName, currentParserStrategy.getPlatformName());

            if (currentParserStrategy instanceof WindowsNativeModelParserStrategy
                && !nativeHandleCache.containsKey(modelName)
                && !nativeLoadFailed.contains(modelName)) {
                try {
                    long handle = GmodNativeBridge.nativeLoadModel(
                        modelsDir.toAbsolutePath().toString(),
                        modelName
                    );
                    if (handle != 0) {
                        nativeHandleCache.put(modelName, handle);
                        LOGGER.info("[MdlModelRenderer] Loaded model '{}' via native renderer (handle={})", modelName, handle);
                    } else {
                        nativeLoadFailed.add(modelName);
                        LOGGER.info("[MdlModelRenderer] Native load returned 0 for '{}'; will use Java renderer", modelName);
                    }
                } catch (Exception e) {
                    nativeLoadFailed.add(modelName);
                    LOGGER.debug("[MdlModelRenderer] Native load failed for {}, falling back to Java: {}", modelName, e.getMessage());
                }
            }

            if (!JavaModelRenderer.hasModel()) {
                LOGGER.info("[MdlModelRenderer] Loading model '{}' data via {}", modelName, currentParserStrategy.getPlatformName());
                // Route through ModelLoadManager so the loading progress bar (ModelLoadProgress)
                // is driven correctly. It also reuses the disk cache and reports SCANNING/PARSING/
                // TEXTURING/BUILDING phases that ModelLoadProgressOverlay reads.
                SourceModelData data = ModelLoadManager.loadModel(packageDir);
                if (data != null && !data.meshes.isEmpty()) {
                    JavaModelRenderer.setModelData(data);
                    javaModelDataCache.put(modelName, data);
                    LOGGER.info("[MdlModelRenderer] Model data loaded: {} meshes, {} triangles (parser: {})",
                        data.meshes.size(), data.totalTriangles(), currentParserStrategy.getPlatformName());

                    try {
                        try (var files = java.nio.file.Files.list(packageDir)) {
                            java.nio.file.Path phyFile = files
                                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".phy"))
                                .findFirst().orElse(null);
                            if (phyFile != null && java.nio.file.Files.exists(phyFile)) {
                                var phyResult = PhyParser.parse(java.nio.file.Files.readAllBytes(phyFile));
                                if (phyResult.valid && !phyResult.solids.isEmpty()) {
                                    String simId = transferstation.transferstation_whimsicalideas.client.physics.PhysicsSimulationManager.registerSimulation(
                                        transferstation.transferstation_whimsicalideas.client.physics.SoftBodySimulation.createClothSimulation(
                                            new Vector3f(0, 1.5f, 0), 8, 8, 0.05f));
                                    data.physicsSimId = simId;
                                    LOGGER.info("[MdlModelRenderer] Created soft-body simulation '{}' for model '{}'", simId, modelName);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("[MdlModelRenderer] Could not create soft-body for '{}': {}", modelName, e.getMessage());
                    }

                    try {
                        transferstation.transferstation_whimsicalideas.client.ClientNotifications.showModelLoadComplete(
                            modelName, data.meshes.size(), data.totalTriangles());
                    } catch (Exception ignored) {}
                } else {
                    LOGGER.warn("[MdlModelRenderer] Model load produced no meshes for {} via {}", modelName, currentParserStrategy.getPlatformName());
                    try {
                        transferstation.transferstation_whimsicalideas.client.ClientNotifications.showModelLoadError(
                            modelName, "No meshes produced");
                    } catch (Exception ignored) {}
                }
            } else {
                // Model already loaded globally, but cache it per-model too
                SourceModelData existingData = JavaModelRenderer.getModelData();
                if (existingData != null && !existingData.meshes.isEmpty()) {
                    javaModelDataCache.put(modelName, existingData);
                }
            }
        } finally {
            modelLoadInProgress.remove(loadKey);
        }
    }

    /**
     * Async model loading
     */
    public static CompletableFuture<Void> loadModelAsync(Path modelsDir, String modelName) {
        String loadKey = modelsDir + ":" + modelName;
        CompletableFuture<Void> existing = pendingLoads.get(loadKey);
        if (existing != null) return existing;

        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                loadModel(modelsDir, modelName);
            } catch (Exception e) {
                LOGGER.error("[MdlModelRenderer] Async load failed for {}: {}", modelName, e.getMessage());
            }
        }, LOADING_EXECUTOR).whenComplete((v, t) -> pendingLoads.remove(loadKey));

        pendingLoads.put(loadKey, future);
        return future;
    }

    public static int getLodLevel(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return 0;
        double dist = entity.distanceToSqr(mc.player);
        double d = Math.sqrt(dist);

        // Check previous LOD state for hysteresis (avoid rapid LOD switching)
        Integer prevLod = entityLodState.get(entity);
        if (prevLod != null) {
            float hysteresis = lodHysteresis;
            // Apply hysteresis: require extra distance to switch UP, less distance to switch DOWN
            double enterDist0 = lod1Distance;
            double enterDist1 = lod2Distance;
            double enterDist2 = lod3Distance;
            double exitDist0 = lod1Distance - hysteresis;
            double exitDist1 = lod2Distance - hysteresis;
            double exitDist2 = lod3Distance - hysteresis;

            int candidate = computeRawLod(d, enterDist0, enterDist1, enterDist2);
            // Hysteresis: only switch if the new LOD is clearly better
            if (candidate < prevLod) {
                // Switching to a higher detail level: require entering the closer zone
                double threshold = switch (prevLod) {
                    case 1 -> exitDist0;
                    case 2 -> exitDist1;
                    case 3 -> exitDist2;
                    default -> 0;
                };
                if (d > threshold) return prevLod;
            } else if (candidate > prevLod) {
                // Switching to a lower detail level: require going beyond the farther zone
                double threshold = switch (prevLod) {
                    case 0 -> enterDist0;
                    case 1 -> enterDist1;
                    case 2 -> enterDist2;
                    default -> Double.MAX_VALUE;
                };
                if (d < threshold) return prevLod;
            }
            entityLodState.put(entity, candidate);
            return candidate;
        }

        int lod = computeRawLod(d, lod1Distance, lod2Distance, lod3Distance);
        entityLodState.put(entity, lod);
        return lod;
    }

    private static int computeRawLod(double distance, double d1, double d2, double d3) {
        if (distance > d3) return 3;
        if (distance > d2) return 2;
        if (distance > d1) return 1;
        return 0;
    }

    private static boolean isEntityVisible(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;
        try {
            var frustum = mc.levelRenderer.getFrustum();
            AABB aabb = entity.getBoundingBox();
            if (aabb.getSize() == 0) {
                aabb = new AABB(entity.blockPosition()).inflate(0.5);
            }
            // Expand the AABB by the frustum expansion factor to avoid popping at screen edges
            if (frustumExpansion > 0) {
                Vec3 center = aabb.getCenter();
                double halfX = (aabb.maxX - aabb.minX) * 0.5 + frustumExpansion;
                double halfY = (aabb.maxY - aabb.minY) * 0.5 + frustumExpansion;
                double halfZ = (aabb.maxZ - aabb.minZ) * 0.5 + frustumExpansion;
                aabb = new AABB(
                    center.x - halfX, center.y - halfY, center.z - halfZ,
                    center.x + halfX, center.y + halfY, center.z + halfZ
                );
            }
            return frustum.isVisible(aabb);
        } catch (Exception e) {
            return true;
        }
    }

    public static void render(LivingEntity entity, PoseStack poseStack, MultiBufferSource bufferSource,
                                int packedLight, float partialTicks) {
        if (entity instanceof net.minecraft.world.entity.player.Player) {
            org.slf4j.LoggerFactory.getLogger("RenderDiag").info(
                "[RenderDiag] MdlModelRenderer.render: currentModel='{}', javaHasModel={}, nativeAvailable={}, nativeCached={}",
                currentModelName,
                transferstation.transferstation_whimsicalideas.client.model.JavaModelRenderer.hasModel(),
                GmodNativeBridge.isAvailable(),
                nativeHandleCache.containsKey(currentModelName));
        }

        // Frustum culling
        if (!isEntityVisible(entity)) {
            totalEntitiesCulledFrustum.increment();
            return;
        }

        String modelName;
        entityMapLock.readLock().lock();
        try {
            modelName = entityModelMap.get(entity);
        } finally {
            entityMapLock.readLock().unlock();
        }
        if (modelName == null) {
            modelName = currentModelName;
        }
        if (modelName == null) {
            totalEntitiesRendered.increment();
            renderFallback(entity, poseStack, bufferSource, packedLight, partialTicks);
            return;
        }

        // Try native renderer first
        if (GmodNativeBridge.isAvailable()) {
            Long handle = nativeHandleCache.get(modelName);
            if (handle == null && !nativeLoadFailed.contains(modelName)) {
                loadModelIfNotPending(modelName);
                handle = nativeHandleCache.get(modelName);
            }
            if (handle != null) {
                int lod = getLodLevel(entity);
                incrementLodCounter(lod);
                totalEntitiesRendered.increment();
                renderNative(handle, entity, poseStack, packedLight, partialTicks);
                return;
            }
        }

        // Try per-model Java renderer data
        SourceModelData modelData = javaModelDataCache.get(modelName);
        if (modelData == null) {
            // Fallback to global data if per-model not cached yet
            modelData = JavaModelRenderer.getModelData();
        }
        if (modelData != null && !modelData.meshes.isEmpty()) {
            // Ensure the per-model cache is populated for next frame
            if (!javaModelDataCache.containsKey(modelName)) {
                javaModelDataCache.put(modelName, modelData);
            }
            // Set per-entity model data so JavaModelRenderer.resolveModelData() works correctly
            JavaModelRenderer.setModelData(entity, modelData);
            
            totalEntitiesRendered.increment();

            if (!modelData.bones.isEmpty()) {
                try {
                    float[][] boneMatrices = AnimationProcessor.getBoneTransforms(entity, modelData, partialTicks);
                    if (boneMatrices != null) {
                        // Skinned animation rendering: per-vertex bone blending + animation transform
                        JavaModelRenderer.renderWithSkinning(entity, poseStack, bufferSource, packedLight, boneMatrices);
                        incrementLodCounter(0);
                        return;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MdlModelRenderer] Animation failed for '{}', falling back to static: {}", 
                        entity.getName().getString(), e.getMessage());
                }
            }
            // No animation data - fall back to static mesh rendering
            int lod = getLodLevel(entity);
            incrementLodCounter(lod);
            if (lod > 0) {
                JavaModelRenderer.renderModelLOD(entity, poseStack, bufferSource, packedLight, lod);
            } else {
                JavaModelRenderer.renderModel(entity, poseStack, bufferSource, packedLight);
            }
            return;
        }

        // Model name known but data doesn't exist (e.g. CleanupHandler cleared data after reconnect), trigger async reload
        if (modelsDir != null) {
            loadModelIfNotPending(modelName);
        }

        // While loading, render a minimal placeholder instead of the cube fallback
        // This avoids the "invisible model" issue where the cube shows briefly
        totalEntitiesRendered.increment();
        renderLoadingPlaceholder(entity, poseStack, bufferSource, packedLight);
    }

    private static void incrementLodCounter(int lod) {
        switch (lod) {
            case 0 -> totalLod0.increment();
            case 1 -> totalLod1.increment();
            case 2 -> totalLod2.increment();
            case 3 -> totalLod3.increment();
        }
    }

    private static void renderLoadingPlaceholder(LivingEntity entity, PoseStack poseStack,
                                                  MultiBufferSource bufferSource, int packedLight) {
        // Render a translucent wireframe-style box to indicate the model is loading
        // instead of making the entity completely invisible
        poseStack.pushPose();
        float scale = entity.getBbHeight() / 1.8f;
        poseStack.scale(scale, scale, scale);

        float hw = 0.3f; // half-width
        float hh = 0.9f; // half-height
        float hd = 0.15f; // half-depth

        RenderType renderType = RenderType.entitySolid(
            ResourceLocation.parse("minecraft:textures/block/white_concrete.png"));
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        float r = 0.8f, g = 0.8f, b = 1.0f, a = 0.3f;

        // 8 corners of a bounding box
        float[][] corners = {
            {-hw, -hh, -hd}, { hw, -hh, -hd}, { hw,  hh, -hd}, {-hw,  hh, -hd},
            {-hw, -hh,  hd}, { hw, -hh,  hd}, { hw,  hh,  hd}, {-hw,  hh,  hd}
        };
        // 12 edges
        int[][] edges = {
            {0,1},{1,2},{2,3},{3,0},
            {4,5},{5,6},{6,7},{7,4},
            {0,4},{1,5},{2,6},{3,7}
        };
        // Render each edge as a thin quad (two triangles)
        for (int[] edge : edges) {
            float[] p0 = corners[edge[0]];
            float[] p1 = corners[edge[1]];
            float thickness = 0.01f;
            float dx = p1[0] - p0[0], dy = p1[1] - p0[1], dz = p1[2] - p0[2];
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.001f) continue;
            float nx = -dy / len, ny = dx / len, nz = 0;
            if (Math.abs(nz) > 0.9f) { nx = 0; ny = 0; nz = dz > 0 ? 1 : -1; }

            float ax = p0[0] + nx * thickness, ay = p0[1] + ny * thickness, az = p0[2] + nz * thickness;
            float bx = p0[0] - nx * thickness, by = p0[1] - ny * thickness, bz = p0[2] - nz * thickness;
            float cx = p1[0] + nx * thickness, cy = p1[1] + ny * thickness, cz = p1[2] + nz * thickness;
            float dx2 = p1[0] - nx * thickness, dy2 = p1[1] - ny * thickness, dz2 = p1[2] - nz * thickness;

            consumer.vertex(matrix, ax, ay, az).color(r, g, b, a).uv(0, 0).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normalMatrix, 0, 1, 0).endVertex();
            consumer.vertex(matrix, bx, by, bz).color(r, g, b, a).uv(1, 0).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normalMatrix, 0, 1, 0).endVertex();
            consumer.vertex(matrix, dx2, dy2, dz2).color(r, g, b, a).uv(1, 1).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normalMatrix, 0, 1, 0).endVertex();
            consumer.vertex(matrix, cx, cy, cz).color(r, g, b, a).uv(0, 1).overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normalMatrix, 0, 1, 0).endVertex();
        }

        totalDrawCalls.increment();
        poseStack.popPose();
    }

    private static void loadModelIfNotPending(String modelName) {
        if (modelsDir == null) return;
        if (nativeLoadFailed.contains(modelName)) return;
        String loadKey = modelsDir + ":" + modelName;

        pendingLoads.computeIfAbsent(loadKey, k -> {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    loadModel(modelsDir, modelName);
                } catch (Exception e) {
                    LOGGER.error("[MdlModelRenderer] Failed to load model: {}", modelName, e);
                }
            }, LOADING_EXECUTOR);
            future.whenComplete((v, t) -> pendingLoads.remove(k));
            return future;
        });
    }

    private static void renderNative(long handle, LivingEntity entity, PoseStack poseStack,
                                       int packedLight, float partialTicks) {
        poseStack.pushPose();

        float scale = entity.getBbHeight() / 1.8f;
        poseStack.scale(scale, scale, scale);

        float mdlScale = (1.0f / 40.0f);
        try {
            mdlScale *= GmodNativeBridge.nativeGetModelScale(handle);
        } catch (Exception ignored) {}
        poseStack.scale(mdlScale, mdlScale, mdlScale);

        float minZ = GmodNativeBridge.nativeGetMinZ(handle);
        if (minZ < -0.001f) {
            poseStack.translate(0.0, -minZ + 4.0f, 0.0);
        }

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        try {
            net.minecraft.client.Camera cam =
                net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
            GmodNativeBridge.nativeSetCameraPosition(
                (float) cam.getPosition().x, (float) cam.getPosition().y, (float) cam.getPosition().z);

            // In MC's entity pipeline the entity poseStack (and thus `matrix` above)
            // ALREADY contains the camera transform — RenderSystem's ModelView is the
            // identity during the entity pass (the poseStack folds the camera view into
            // the entity matrix). So the native renderer must combine ONLY the projection
            // matrix with the model matrix (gl_renderer.cpp renderMesh:
            //   MVP = viewProjection * modelMatrix). Applying a separate camera view here
            // double-transforms the model and throws it off-screen (the "invisible" bug).
            Matrix4f viewProjection = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
            float[] vpArray = new float[16];
            viewProjection.get(vpArray);
            GmodNativeBridge.nativeSetViewProjection(vpArray);
        } catch (Throwable ignored) {}
        float[] matArray = new float[16];
        matrix.get(matArray);

        try {
            int lod = getLodLevel(entity);
            try {
                GmodNativeBridge.nativeRenderModelLOD(handle, matArray, packedLight, partialTicks, lod);
            } catch (UnsatisfiedLinkError e) {
                GmodNativeBridge.nativeRenderModel(handle, matArray, packedLight, partialTicks);
            }
            totalDrawCalls.increment();
        } catch (Exception e) {
            LOGGER.error("[MdlModelRenderer] Native render error", e);
        }

        poseStack.popPose();
    }

    private static void renderFallback(LivingEntity entity, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight, float partialTicks) {
        // Track fallback rendering in statistics
        totalDrawCalls.increment();
        transferstation.transferstation_whimsicalideas.client.GmodModelRenderer.renderGmodModel(
                entity, poseStack, bufferSource, packedLight, partialTicks);
    }
}
