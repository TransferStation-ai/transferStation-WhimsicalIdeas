package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;

public class JavaModelRenderer {

    private static SourceModelData currentModelData;

    public static void setModelData(SourceModelData data) {
        currentModelData = data;
    }

    public static SourceModelData getModelData() {
        return currentModelData;
    }

    public static boolean hasModel() {
        return currentModelData != null && !currentModelData.meshes.isEmpty();
    }

    public static void renderModel(LivingEntity entity, PoseStack poseStack,
                                    MultiBufferSource bufferSource, int packedLight,
                                    float partialTicks) {
        if (!hasModel()) return;

        poseStack.pushPose();

        float scale = entity.getBbHeight() / 1.8f;
        poseStack.scale(scale, scale, scale);

        float mdlScale = 1.0f / 40.0f;
        if (currentModelData.modelScale != 0) {
            mdlScale *= currentModelData.modelScale;
        }
        poseStack.scale(mdlScale, mdlScale, mdlScale);

        float minZ = currentModelData.minZ;
        if (minZ < -0.001f) {
            poseStack.translate(0.0, -minZ + 4.0f, 0.0);
        }

        float bob = 0f;
        if (!entity.onGround() && entity.getDeltaMovement().y > 0) {
            bob = (float) Math.sin(entity.tickCount * 0.5f) * 0.02f;
        }
        poseStack.translate(0.0, bob, 0.0);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        for (SourceModelData.MeshData mesh : currentModelData.meshes) {
            renderMesh(mesh, matrix, bufferSource, packedLight);
        }

        poseStack.popPose();
    }

    private static void renderMesh(SourceModelData.MeshData mesh, Matrix4f matrix,
                                    MultiBufferSource bufferSource, int packedLight) {
        if (mesh.indices.length < 3) return;

        ResourceLocation texture = mesh.texture;
        RenderType renderType;
        if (texture != null) {
            renderType = mesh.translucent ?
                RenderType.entityTranslucent(texture) :
                RenderType.entityCutout(texture);
        } else {
            renderType = RenderType.entitySolid(
                ResourceLocation.parse("minecraft:textures/block/white_concrete.png"));
        }

        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        float[] vertices = mesh.vertices;
        int[] indices = mesh.indices;

        for (int i = 0; i < indices.length; i += 3) {
            if (i + 2 >= indices.length) break;

            int i0 = indices[i] * 8;
            int i1 = indices[i + 1] * 8;
            int i2 = indices[i + 2] * 8;

            if (i0 < 0 || i1 < 0 || i2 < 0) continue;
            if (i0 + 7 >= vertices.length || i1 + 7 >= vertices.length || i2 + 7 >= vertices.length) continue;

            float ax = vertices[i0], ay = vertices[i0 + 1], az = vertices[i0 + 2];
            float bx = vertices[i1], by = vertices[i1 + 1], bz = vertices[i1 + 2];
            float cx = vertices[i2], cy = vertices[i2 + 1], cz = vertices[i2 + 2];

            float anx = vertices[i0 + 3], any = vertices[i0 + 4], anz = vertices[i0 + 5];
            float bnx = vertices[i1 + 3], bny = vertices[i1 + 4], bnz = vertices[i1 + 5];
            float cnx = vertices[i2 + 3], cny = vertices[i2 + 4], cnz = vertices[i2 + 5];

            float au = vertices[i0 + 6], av = vertices[i0 + 7];
            float bu = vertices[i1 + 6], bv = vertices[i1 + 7];
            float cu = vertices[i2 + 6], cv = vertices[i2 + 7];

            emitVertex(consumer, matrix, ax, ay, az, anx, any, anz, au, av, packedLight);
            emitVertex(consumer, matrix, bx, by, bz, bnx, bny, bnz, bu, bv, packedLight);
            emitVertex(consumer, matrix, cx, cy, cz, cnx, cny, cnz, cu, cv, packedLight);
        }
    }

    private static void emitVertex(VertexConsumer consumer, Matrix4f matrix,
                                    float x, float y, float z,
                                    float nx, float ny, float nz,
                                    float u, float v, int packedLight) {
        consumer.vertex(matrix, x, y, z)
            .color(1f, 1f, 1f, 1f)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(nx, ny, nz)
            .endVertex();
    }
}
