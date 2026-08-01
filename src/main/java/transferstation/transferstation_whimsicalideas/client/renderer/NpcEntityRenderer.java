package transferstation.transferstation_whimsicalideas.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
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

    public NpcEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
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
            modelData = ModelLoadManager.getCached(packageDir.toAbsolutePath().toString());
            if (modelData != null) {
                JavaModelRenderer.setModelData(entity, modelData);
                // 注册骨骼碰撞箱，用于硬核伤害判定
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
                                BodyHitboxSystem.registerHitboxes(entity, data);
                            });
                        }
                    });
                }

                poseStack.pushPose();
                GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
                poseStack.popPose();
                super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
                return;
            }
        }

        if (modelData.meshes.isEmpty()) {
            poseStack.pushPose();
            GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
            poseStack.popPose();
            super.render(entity, entityYaw, partialTicks, poseStack, bufferSource, packedLight);
            return;
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
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull NpcEntity entity) {
        return ResourceLocation.parse("minecraft:textures/entity/steve.png");
    }
}
