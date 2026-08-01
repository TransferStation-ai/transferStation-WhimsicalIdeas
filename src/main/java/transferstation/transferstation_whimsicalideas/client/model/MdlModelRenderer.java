package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
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
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MdlModelRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static float lod1Distance = 20.0f;
    private static float lod2Distance = 40.0f;
    private static float lod3Distance = 60.0f;

    public static void setLodDistances(float near, float mid, float far) {
        lod1Distance = near;
        lod2Distance = mid;
        lod3Distance = far;
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

    // Per-entity animation state
    private static final Map<LivingEntity, float[]> entityBoneMatrices = new WeakHashMap<>();

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
                && !ModelLoadManager.isMultiSubmodelPackage(packageDir)) {
                try {
                    long handle = GmodNativeBridge.nativeLoadModel(
                        modelsDir.toAbsolutePath().toString(),
                        modelName
                    );
                    if (handle != 0) {
                        nativeHandleCache.put(modelName, handle);
                        LOGGER.info("[MdlModelRenderer] Loaded model '{}' via native renderer (handle={})", modelName, handle);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[MdlModelRenderer] Native load failed for {}, falling back: {}", modelName, e.getMessage());
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
        if (d > lod3Distance) return 3;
        if (d > lod2Distance) return 2;
        if (d > lod1Distance) return 1;
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
        if (!isEntityVisible(entity)) return;

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
            renderFallback(entity, poseStack, bufferSource, packedLight, partialTicks);
            return;
        }

        if (GmodNativeBridge.isAvailable()) {
            Long handle = nativeHandleCache.get(modelName);
            if (handle == null) {
                loadModelIfNotPending(modelName);
                handle = nativeHandleCache.get(modelName);
            }
            if (handle != null) {
                renderNative(handle, entity, poseStack, packedLight, partialTicks);
                return;
            }
        }

        if (JavaModelRenderer.hasModel()) {
            SourceModelData modelData = JavaModelRenderer.getModelData();
            if (modelData != null && !modelData.bones.isEmpty()) {
                try {
                    float[][] boneMatrices = AnimationProcessor.getBoneTransforms(entity, modelData, partialTicks);
                    if (boneMatrices != null) {
                        // 蒙皮动画渲染：每顶点骨骼混合 + 动画变换
                        JavaModelRenderer.renderWithSkinning(entity, poseStack, bufferSource, packedLight, boneMatrices);
                        return;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[MdlModelRenderer] Animation failed for '{}', falling back to static: {}", 
                        entity.getName().getString(), e.getMessage());
                }
            }
            // 无动画数据时降级为静态网格渲染
            int lod = getLodLevel(entity);
            if (lod > 0) {
                JavaModelRenderer.renderModelLOD(entity, poseStack, bufferSource, packedLight, lod);
            } else {
                JavaModelRenderer.renderModel(entity, poseStack, bufferSource, packedLight);
            }
            return;
        }

        // 模型名已知但数据不存在时（如 CleanupHandler 清除了数据后重连），触发异步重新加载
        if (modelsDir != null) {
            loadModelIfNotPending(modelName);
        }

        renderFallback(entity, poseStack, bufferSource, packedLight, partialTicks);
    }

    private static void loadModelIfNotPending(String modelName) {
        if (modelsDir == null) return;
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
        } catch (Exception ignored) {}
        float[] matArray = new float[16];
        matrix.get(matArray);

        try {
            int lod = getLodLevel(entity);
            try {
                GmodNativeBridge.nativeRenderModelLOD(handle, matArray, packedLight, partialTicks, lod);
            } catch (UnsatisfiedLinkError e) {
                GmodNativeBridge.nativeRenderModel(handle, matArray, packedLight, partialTicks);
            }
        } catch (Exception e) {
            LOGGER.error("[MdlModelRenderer] Native render error", e);
        }

        poseStack.popPose();
    }

    private static void renderFallback(LivingEntity entity, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight, float partialTicks) {
        transferstation.transferstation_whimsicalideas.client.GmodModelRenderer.renderGmodModel(
                entity, poseStack, bufferSource, packedLight, partialTicks);
    }
}
