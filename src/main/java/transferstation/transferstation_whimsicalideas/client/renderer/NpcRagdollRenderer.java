package transferstation.transferstation_whimsicalideas.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import transferstation.transferstation_whimsicalideas.client.model.*;

import java.nio.file.Path;

public class NpcRagdollRenderer extends EntityRenderer<NpcRagdoll> {

    public NpcRagdollRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(NpcRagdoll entity, float entityYaw, float partialTicks,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int packedLight) {
        String modelName = entity.getModelName();
        if (modelName == null || modelName.isEmpty()) return;

        Path modelsDir = MdlModelRenderer.getModelsDir();
        if (modelsDir == null) return;

        String cacheKey = modelsDir.resolve(modelName).toAbsolutePath().toString();
        SourceModelData modelData = ModelLoadManager.getCached(cacheKey);
        if (modelData == null) {
            ModelLoadManager.loadModelAsync(modelsDir.resolve(modelName));
            return;
        }

        if (modelData.meshes.isEmpty() || modelData.bones.isEmpty()) return;

        int boneCount = modelData.bones.size();
        float[][] boneMatrices = new float[boneCount][16];

        boolean physicsAvailable = PhysicsBridge.isAvailable();
        for (int i = 0; i < boneCount; i++) {
            Matrix4f boneMat = new Matrix4f();
            boneMat.identity();

            if (physicsAvailable) {
                float[] pos = entity.getBonePosition(i);
                float[] rot = entity.getBoneRotation(i);
                // Fall back to bind pose if the physics bridge returned zeroed data.
                // A zero quaternion (rot[3] == 0) is invalid and would produce a NaN
                // axis, so treat it as "use bind pose" as well.
                boolean zeroed = (pos == null || (pos[0] == 0 && pos[1] == 0 && pos[2] == 0))
                        && (rot == null || (rot[0] == 0 && rot[1] == 0 && rot[2] == 0 && rot[3] == 0));
                boolean invalidQuat = (rot != null && rot.length >= 4 && rot[3] == 0.0f);
                if (zeroed || invalidQuat) continue;
                if (pos != null) {
                    boneMat.translate(pos[0], pos[1], pos[2]);
                    if (rot != null && rot.length >= 4) {
                        float angle = (float) (2.0 * Math.acos(Math.max(-1.0f, Math.min(1.0f, rot[3]))));
                        float s = (float) Math.sqrt(1.0 - rot[3] * rot[3]);
                        if (s > 0.001f) {
                            float invS = 1.0f / s;
                            boneMat.rotate(angle, rot[0] * invS, rot[1] * invS, rot[2] * invS);
                        }
                    }
                }
            }
            // When physics is unavailable or data is zeroed, leave the matrix as identity
            // (bind pose) so the model renders in its default pose instead of collapsing
            // to the origin.

            boneMat.get(boneMatrices[i]);
        }

        poseStack.pushPose();

        // The caller already positions the poseStack at the entity; renderWithSkinning
        // applies its own model centering, so do NOT pre-translate by world coords here
        // (that double-translated the ragdoll away from the entity).

        JavaModelRenderer.renderWithSkinning(entity, poseStack, bufferSource, packedLight, boneMatrices);

        poseStack.popPose();

        if (this.shouldShowName(entity)) {
            this.renderNameTag(entity, entity.getDisplayName(), poseStack, bufferSource, packedLight);
        }
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull NpcRagdoll entity) {
        return ResourceLocation.parse("minecraft:textures/entity/steve.png");
    }
}
