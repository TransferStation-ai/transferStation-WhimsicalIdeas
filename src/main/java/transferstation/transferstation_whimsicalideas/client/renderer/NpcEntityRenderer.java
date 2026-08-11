package transferstation.transferstation_whimsicalideas.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import transferstation.transferstation_whimsicalideas.client.GmodModelRenderer;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;
import transferstation.transferstation_whimsicalideas.client.model.*;
import transferstation.transferstation_whimsicalideas.common.BodyHitboxSystem;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NpcEntityRenderer extends EntityRenderer<NpcEntity> {

    private static final Map<String, CompletableFuture<SourceModelData>> loadingModels = new ConcurrentHashMap<>();

    private static final double MAX_RENDER_DISTANCE = 64.0;

    public NpcEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
    }

    @Override
    public boolean shouldRender(NpcEntity entity, net.minecraft.client.renderer.culling.Frustum frustum,
                                double camX, double camY, double camZ) {
        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) {
            return false;
        }
        return super.shouldRender(entity, frustum, camX, camY, camZ);
    }

    @Override
    public void render(NpcEntity entity, float entityYaw, float partialTicks,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight) {
        String modelName = entity.getModelName();
        if (modelName == null || modelName.isEmpty()) return;

        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir == null) {
            poseStack.pushPose();
            GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
            poseStack.popPose();
            super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
            return;
        }

        Path packageDir = modelsDir.resolve(modelName);
        if (!packageDir.toFile().exists()) {
            poseStack.pushPose();
            GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
            poseStack.popPose();
            super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
            return;
        }

        // Check if we already have per-entity model data
        SourceModelData modelData = JavaModelRenderer.getModelData(entity);

        // Load the model if not yet loaded for this entity
        if (modelData == null || modelData.meshes.isEmpty()) {
            // First try the per-model cache from MdlModelRenderer
            modelData = MdlModelRenderer.getJavaModelData(modelName);
            
            if (modelData != null && !modelData.meshes.isEmpty()) {
                JavaModelRenderer.setModelData(entity, modelData);
                // Register hitboxes for hardcore damage
                BodyHitboxSystem.registerHitboxes(entity, modelData);
            } else {
                // Try ModelLoadManager cache
                modelData = ModelLoadManager.getCached(packageDir.toAbsolutePath().toString());
                if (modelData != null && !modelData.meshes.isEmpty()) {
                    JavaModelRenderer.setModelData(entity, modelData);
                    MdlModelRenderer.setJavaModelData(modelName, modelData);
                    BodyHitboxSystem.registerHitboxes(entity, modelData);
                } else {
                    // Try cached future first
                    String loadKey = packageDir.toAbsolutePath().toString();
                    CompletableFuture<SourceModelData> future = loadingModels.get(loadKey);

                    if (future == null) {
                        future = ModelLoadManager.loadModelAsync(packageDir);
                        loadingModels.put(loadKey, future);
                        future.thenAccept(data -> {
                            loadingModels.remove(loadKey);
                            if (data != null && !data.meshes.isEmpty()) {
                                net.minecraft.client.Minecraft.getInstance().tell(() -> {
                                    JavaModelRenderer.setModelData(entity, data);
                                    MdlModelRenderer.setJavaModelData(modelName, data);
                                    BodyHitboxSystem.registerHitboxes(entity, data);
                                });
                            }
                        });
                    }

                    // While loading, render a minimal placeholder instead of the cube fallback
                    poseStack.pushPose();
                    float scale = entity.getBbHeight() / 1.8f;
                    poseStack.scale(scale, scale, scale);
                    poseStack.popPose();
                    super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
                    return;
                }
            }
        }

        if (modelData.meshes.isEmpty()) {
            poseStack.pushPose();
            GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
            poseStack.popPose();
            super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
            return;
        }

        if (!modelData.bones.isEmpty()) {
            int boneCount = modelData.bones.size();
            this.shadowRadius = Mth.lerp(Math.min(boneCount / 20.0f, 1.0f), 0.3f, 0.8f);
        } else {
            this.shadowRadius = 0.5f;
        }

        poseStack.pushPose();

        float[][] boneMatrices;

        if (!modelData.bones.isEmpty()) {
            boneMatrices = AnimationProcessor.getBoneTransforms(entity, modelData, partialTicks);

            if (boneMatrices == null) {
                int boneCount = modelData.bones.size();
                boneMatrices = new float[boneCount][16];
                for (int i = 0; i < boneCount; i++) {
                    org.joml.Matrix4f identity = new org.joml.Matrix4f();
                    identity.identity();
                    identity.get(boneMatrices[i]);
                }
            }

            String entityId = entity.getStringUUID();
            NpcBoneController.registerBones(entityId, modelData.bones);
            for (int i = 0; i < modelData.bones.size(); i++) {
                SourceModelData.BoneInfo bone = modelData.bones.get(i);
                Matrix4f override = NpcBoneController.getBoneTransform(entityId, bone.name());
                Matrix4f identity = new Matrix4f();
                identity.identity();
                if (override != null && !override.equals(identity, 0.001f)) {
                    Matrix4f existing = new Matrix4f();
                    existing.set(boneMatrices[i]);
                    existing.mul(override);
                    existing.get(boneMatrices[i]);
                }
            }

            JavaModelRenderer.renderWithSkinning(entity, poseStack, bufferSource, packedLight, boneMatrices);
        } else {
            // No bones - render as static mesh using per-entity model data
            JavaModelRenderer.renderModel(entity, poseStack, bufferSource, packedLight);
        }

        poseStack.popPose();

        if (this.shouldShowName(entity)) {
            this.renderNameTag(entity, entity.getDisplayName(), poseStack, bufferSource, packedLight);
        }

        renderStatusIndicator(entity, poseStack, bufferSource);
        renderHealthBar(entity, poseStack, bufferSource);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull NpcEntity entity) {
        return ResourceLocation.parse("minecraft:textures/entity/steve.png");
    }

    private void renderStatusIndicator(NpcEntity entity, PoseStack poseStack, MultiBufferSource bufferSource) {
        NpcData npcData = entity.getNpcData();
        if (npcData == null) return;

        String mood = npcData.getCurrentMood();
        int color = getMoodColor(mood);

        poseStack.pushPose();
        poseStack.translate(0, entity.getBbHeight() + 0.5, 0);

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        double dx = entity.getX() - cameraPos.x;
        double dy = (entity.getY() + entity.getBbHeight() + 0.5) - cameraPos.y;
        double dz = entity.getZ() - cameraPos.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));

        float size = 0.15f;
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entitySolid(
                ResourceLocation.parse("minecraft:textures/misc/white.png")));

        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        int light = 0xF000F0;

        Matrix4f matrix = poseStack.last().pose();
        vertexConsumer.vertex(matrix, -size, -size, 0).color(r, g, b, a).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, -size, size, 0).color(r, g, b, a).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, size, size, 0).color(r, g, b, a).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, size, -size, 0).color(r, g, b, a).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();

        poseStack.popPose();
    }

    private int getMoodColor(String mood) {
        if (mood == null) return 0xFF808080;
        return switch (mood) {
            case "happy" -> 0xFF00FF00;
            case "angry" -> 0xFFFF0000;
            case "scared" -> 0xFFFFFF00;
            case "hostile" -> 0xFFFF4400;
            case "neutral" -> 0xFF808080;
            default -> 0xFF808080;
        };
    }

    private void renderHealthBar(NpcEntity entity, PoseStack poseStack, MultiBufferSource bufferSource) {
        if (entity.getHealth() >= entity.getMaxHealth()) return;

        float healthPercent = entity.getHealth() / entity.getMaxHealth();
        float barWidth = 0.5f;
        float barHeight = 0.05f;
        float yOffset = entity.getBbHeight() + 0.3f;

        poseStack.pushPose();
        poseStack.translate(0, yOffset, 0);

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        double dx = entity.getX() - cameraPos.x;
        double dy = (entity.getY() + yOffset) - cameraPos.y;
        double dz = entity.getZ() - cameraPos.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));

        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entitySolid(
                ResourceLocation.parse("minecraft:textures/misc/white.png")));

        int light = 0xF000F0;
        Matrix4f matrix = poseStack.last().pose();

        float bgLeft = -barWidth / 2;
        float bgRight = barWidth / 2;
        float bgTop = -barHeight / 2;
        float bgBottom = barHeight / 2;

        vertexConsumer.vertex(matrix, bgLeft, bgBottom, 0).color(0.25f, 0.25f, 0.25f, 0.78f).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, bgLeft, bgTop, 0).color(0.25f, 0.25f, 0.25f, 0.78f).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, bgRight, bgTop, 0).color(0.25f, 0.25f, 0.25f, 0.78f).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, bgRight, bgBottom, 0).color(0.25f, 0.25f, 0.25f, 0.78f).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();

        float healthLeft = bgLeft;
        float healthRight = bgLeft + (bgRight - bgLeft) * healthPercent;

        float healthR = 1.0f - healthPercent;
        float healthG = healthPercent;

        vertexConsumer.vertex(matrix, healthLeft, bgBottom, 0.001f).color(healthR, healthG, 0, 0.78f).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, healthLeft, bgTop, 0.001f).color(healthR, healthG, 0, 0.78f).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, healthRight, bgTop, 0.001f).color(healthR, healthG, 0, 0.78f).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();
        vertexConsumer.vertex(matrix, healthRight, bgBottom, 0.001f).color(healthR, healthG, 0, 0.78f).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(0, 1, 0).endVertex();

        poseStack.popPose();
    }
}
