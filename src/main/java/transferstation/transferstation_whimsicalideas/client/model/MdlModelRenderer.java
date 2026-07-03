package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class MdlModelRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_CACHED_MODELS = 16;
    private static final float LOD_1_DISTANCE = 20.0f;
    private static final float LOD_2_DISTANCE = 40.0f;
    private static final float LOD_3_DISTANCE = 60.0f;

    private static final Map<String, Long> nativeHandleCache = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            if (size() > MAX_CACHED_MODELS) {
                if (eldest.getValue() != null && GmodNativeBridge.isAvailable()) {
                    GmodNativeBridge.nativeFreeModel(eldest.getValue());
                }
                return true;
            }
            return false;
        }
    };
    private static String currentModelName = null;
    private static Path modelsDir = null;
    private static Path cacheDir = null;

    public static void setModelsDir(Path dir) {
        modelsDir = dir;
    }

    public static Path getModelsDir() {
        return modelsDir;
    }

    public static void setCacheDir(Path dir) {
        cacheDir = dir;
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
        JavaModelRenderer.setModelData(null);
    }

    public static String getCurrentModel() {
        return currentModelName;
    }

    public static boolean isModelLoaded() {
        if (currentModelName == null) return false;
        if (GmodNativeBridge.isAvailable()) {
            return nativeHandleCache.containsKey(currentModelName) || JavaModelRenderer.hasModel();
        }
        return JavaModelRenderer.hasModel();
    }

    public static void loadModel(Path modelsDir, String modelName) throws IOException {
        if (modelName == null || modelName.isEmpty()) return;

        Path packageDir = modelsDir.resolve(modelName);
        if (!java.nio.file.Files.exists(packageDir)) {
            throw new IOException("Model package directory not found: " + modelName);
        }

        if (GmodNativeBridge.tryLoadNative()) {
            long handle = GmodNativeBridge.nativeLoadModel(
                modelsDir.toAbsolutePath().toString(),
                modelName
            );

            if (handle != 0) {
                nativeHandleCache.put(modelName, handle);
                LOGGER.info("[MdlModelRenderer] Loaded model '{}' via native renderer (handle={})", modelName, handle);
                return;
            }
        }

        LOGGER.info("[MdlModelRenderer] Loading model '{}' via Java renderer", modelName);
        SourceModelData data = ModelLoadManager.loadModel(packageDir);
        if (data != null && !data.meshes.isEmpty()) {
            JavaModelRenderer.setModelData(data);
            LOGGER.info("[MdlModelRenderer] Java model loaded: {} meshes, {} triangles",
                data.meshes.size(), data.totalTriangles());
        } else {
            LOGGER.warn("[MdlModelRenderer] Java model load produced no meshes for {}", modelName);
        }
    }

    public static int getLodLevel(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return 0;
        double dist = entity.distanceToSqr(mc.player);
        double d = Math.sqrt(dist);
        if (d > LOD_3_DISTANCE) return 3;
        if (d > LOD_2_DISTANCE) return 2;
        if (d > LOD_1_DISTANCE) return 1;
        return 0;
    }

    private static boolean isEntityVisible(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.levelRenderer == null) return true;
        try {
            var frustum = mc.levelRenderer.getFrustum();
            if (frustum == null) return true;
            AABB aabb = entity.getBoundingBox();
            if (aabb == null || aabb.getSize() == 0) {
                aabb = new AABB(entity.blockPosition()).inflate(0.5);
            }
            return frustum.isVisible(aabb);
        } catch (Exception e) {
            return true;
        }
    }

    public static void render(LivingEntity entity, PoseStack poseStack, MultiBufferSource bufferSource,
                               int packedLight, float partialTicks) {
        if (!isEntityVisible(entity)) return;

        if (currentModelName == null) {
            renderFallback(entity, poseStack, bufferSource, packedLight, partialTicks);
            return;
        }

        if (GmodNativeBridge.isAvailable()) {
            Long handle = nativeHandleCache.get(currentModelName);
            if (handle != null) {
                renderNative(handle, entity, poseStack, packedLight, partialTicks);
                return;
            }
        }

        if (JavaModelRenderer.hasModel()) {
            JavaModelRenderer.renderModel(entity, poseStack, bufferSource, packedLight, partialTicks);
            return;
        }

        renderFallback(entity, poseStack, bufferSource, packedLight, partialTicks);
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
