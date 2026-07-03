package transferstation.transferstation_whimsicalideas.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;

public class GmodModelRenderer {

    private static final float BODY_WIDTH = 0.35f;
    private static final float BODY_HEIGHT = 0.6f;
    private static final float BODY_DEPTH = 0.2f;
    private static final float HEAD_SIZE = 0.4f;
    private static final float LIMB_WIDTH = 0.15f;
    private static final float LIMB_LENGTH = 0.55f;

    private static final int COLOR_SKIN = ColorUtils.MC_SKIN;
    private static final int COLOR_SHIRT = ColorUtils.MC_SHIRT;
    private static final int COLOR_PANTS = ColorUtils.MC_PANTS;
    private static final int COLOR_SHOES = ColorUtils.MC_SHOES;
    private static final int COLOR_EYE_WHITE = ColorUtils.MC_EYE_WHITE;
    private static final int COLOR_PUPIL = ColorUtils.MC_PUPIL;
    private static final int COLOR_MOUTH = ColorUtils.MC_MOUTH;
    private static final int COLOR_EYEBROW = ColorUtils.MC_EYEBROW;

    private static final float[] COLOR_SKIN_RGB = ColorUtils.argbToFloatRgb(COLOR_SKIN);
    private static final float[] COLOR_SHIRT_RGB = ColorUtils.argbToFloatRgb(COLOR_SHIRT);
    private static final float[] COLOR_PANTS_RGB = ColorUtils.argbToFloatRgb(COLOR_PANTS);
    private static final float[] COLOR_SHOES_RGB = ColorUtils.argbToFloatRgb(COLOR_SHOES);
    private static final float[] COLOR_EYE_WHITE_RGB = ColorUtils.argbToFloatRgb(COLOR_EYE_WHITE);
    private static final float[] COLOR_PUPIL_RGB = ColorUtils.argbToFloatRgb(COLOR_PUPIL);
    private static final float[] COLOR_MOUTH_RGB = ColorUtils.argbToFloatRgb(COLOR_MOUTH);
    private static final float[] COLOR_EYEBROW_RGB = ColorUtils.argbToFloatRgb(COLOR_EYEBROW);

    private static final ResourceLocation FACE_TEXTURE =
            ResourceLocation.parse("transferstation_whimsicalideas:textures/model/fallback_face.png");

    public static void renderGmodModel(LivingEntity entity, PoseStack poseStack, MultiBufferSource bufferSource,
                                        int packedLight, float partialTicks) {
        poseStack.pushPose();

        float scale = entity.getBbHeight() / 1.8f;
        poseStack.scale(scale, scale, scale);

        float bob = 0f;
        if (!entity.onGround() && entity.getDeltaMovement().y > 0) {
            bob = (float) Math.sin(entity.tickCount * 0.5f) * 0.02f;
        }

        poseStack.translate(0.0, bob, 0.0);

        float limbSwing = entity.walkAnimation.position(partialTicks);
        float limbSwingAmount = entity.walkAnimation.speed(partialTicks);

        float leftArmAngle = (float) Math.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount;
        float rightArmAngle = (float) Math.cos(limbSwing * 0.6662f + Math.PI) * 1.4f * limbSwingAmount;
        float leftLegAngle = (float) Math.cos(limbSwing * 0.6662f + Math.PI) * 1.4f * limbSwingAmount;
        float rightLegAngle = (float) Math.cos(limbSwing * 0.6662f) * 1.4f * limbSwingAmount;

        VertexConsumer solidConsumer = bufferSource.getBuffer(RenderType.entitySolid(
                ResourceLocation.parse("minecraft:textures/block/white_concrete.png")));

        renderBody(poseStack, solidConsumer, packedLight);

        poseStack.pushPose();
        poseStack.translate(0.0, BODY_HEIGHT + 0.05f, 0.0);
        renderHead(poseStack, solidConsumer, packedLight);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(BODY_WIDTH / 2 + LIMB_WIDTH / 2, BODY_HEIGHT - 0.1f, 0.0);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(rightArmAngle));
        renderLimb(poseStack, solidConsumer, packedLight, LIMB_LENGTH, COLOR_SKIN_RGB[0], COLOR_SKIN_RGB[1], COLOR_SKIN_RGB[2]);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(-BODY_WIDTH / 2 - LIMB_WIDTH / 2, BODY_HEIGHT - 0.1f, 0.0);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(leftArmAngle));
        renderLimb(poseStack, solidConsumer, packedLight, LIMB_LENGTH, COLOR_SKIN_RGB[0], COLOR_SKIN_RGB[1], COLOR_SKIN_RGB[2]);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(LIMB_WIDTH / 2, -BODY_HEIGHT / 2 + 0.1f, 0.0);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(rightLegAngle));
        renderLimb(poseStack, solidConsumer, packedLight, LIMB_LENGTH, COLOR_PANTS_RGB[0], COLOR_PANTS_RGB[1], COLOR_PANTS_RGB[2]);
        renderShoe(poseStack, solidConsumer, packedLight);
        poseStack.popPose();

        poseStack.pushPose();
        poseStack.translate(-LIMB_WIDTH / 2, -BODY_HEIGHT / 2 + 0.1f, 0.0);
        poseStack.mulPose(com.mojang.math.Axis.ZP.rotation(leftLegAngle));
        renderLimb(poseStack, solidConsumer, packedLight, LIMB_LENGTH, COLOR_PANTS_RGB[0], COLOR_PANTS_RGB[1], COLOR_PANTS_RGB[2]);
        renderShoe(poseStack, solidConsumer, packedLight);
        poseStack.popPose();

        poseStack.popPose();
    }

    private static void renderBody(PoseStack poseStack, VertexConsumer consumer, int packedLight) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        float hw = BODY_WIDTH / 2;
        float hh = BODY_HEIGHT / 2;
        float hd = BODY_DEPTH / 2;

        addBox(matrix, consumer, -hw, -hh, -hd, hw, hh, hd, COLOR_SHIRT_RGB[0], COLOR_SHIRT_RGB[1], COLOR_SHIRT_RGB[2], 1f, packedLight);
    }

    private static void renderHead(PoseStack poseStack, VertexConsumer consumer, int packedLight) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        float hs = HEAD_SIZE / 2;

        addBox(matrix, consumer, -hs, -hs, -hs, hs, hs, hs, COLOR_SKIN_RGB[0], COLOR_SKIN_RGB[1], COLOR_SKIN_RGB[2], 1f, packedLight);

        renderEyes(matrix, consumer, packedLight);
        renderMouth(matrix, consumer, packedLight);
    }

    private static void renderEyes(Matrix4f matrix, VertexConsumer consumer, int packedLight) {
        float hs = HEAD_SIZE / 2;
        float eyeY = hs * 0.2f;
        float eyeZ = hs + 0.005f;
        float eyeSpacing = hs * 0.35f;
        float eyeWidth = hs * 0.15f;
        float eyeHeight = hs * 0.12f;
        float pupilWidth = eyeWidth * 0.5f;
        float pupilHeight = eyeHeight * 0.6f;

        addFrontQuad(matrix, consumer,
                -eyeSpacing - eyeWidth, eyeY, eyeZ,
                -eyeSpacing + eyeWidth, eyeY + eyeHeight, eyeZ,
                COLOR_EYE_WHITE_RGB[0], COLOR_EYE_WHITE_RGB[1], COLOR_EYE_WHITE_RGB[2], 1f, packedLight);

        addFrontQuad(matrix, consumer,
                eyeSpacing - eyeWidth, eyeY, eyeZ,
                eyeSpacing + eyeWidth, eyeY + eyeHeight, eyeZ,
                COLOR_EYE_WHITE_RGB[0], COLOR_EYE_WHITE_RGB[1], COLOR_EYE_WHITE_RGB[2], 1f, packedLight);

        float pupilZ = eyeZ + 0.001f;
        addFrontQuad(matrix, consumer,
                -eyeSpacing - pupilWidth, eyeY + eyeHeight * 0.15f, pupilZ,
                -eyeSpacing + pupilWidth, eyeY + pupilHeight + eyeHeight * 0.15f, pupilZ,
                COLOR_PUPIL_RGB[0], COLOR_PUPIL_RGB[1], COLOR_PUPIL_RGB[2], 1f, packedLight);

        addFrontQuad(matrix, consumer,
                eyeSpacing - pupilWidth, eyeY + eyeHeight * 0.15f, pupilZ,
                eyeSpacing + pupilWidth, eyeY + pupilHeight + eyeHeight * 0.15f, pupilZ,
                COLOR_PUPIL_RGB[0], COLOR_PUPIL_RGB[1], COLOR_PUPIL_RGB[2], 1f, packedLight);

        float browY = eyeY + eyeHeight + 0.01f;
        float browH = 0.012f;

        addFrontQuad(matrix, consumer,
                -eyeSpacing - eyeWidth - 0.005f, browY, eyeZ,
                -eyeSpacing + eyeWidth + 0.005f, browY + browH, eyeZ,
                COLOR_EYEBROW_RGB[0], COLOR_EYEBROW_RGB[1], COLOR_EYEBROW_RGB[2], 1f, packedLight);

        addFrontQuad(matrix, consumer,
                eyeSpacing - eyeWidth - 0.005f, browY, eyeZ,
                eyeSpacing + eyeWidth + 0.005f, browY + browH, eyeZ,
                COLOR_EYEBROW_RGB[0], COLOR_EYEBROW_RGB[1], COLOR_EYEBROW_RGB[2], 1f, packedLight);
    }

    private static void renderMouth(Matrix4f matrix, VertexConsumer consumer, int packedLight) {
        float hs = HEAD_SIZE / 2;
        float mouthY = -hs * 0.25f;
        float mouthZ = hs + 0.005f;
        float mouthWidth = hs * 0.25f;
        float mouthHeight = hs * 0.06f;

        addFrontQuad(matrix, consumer,
                -mouthWidth, mouthY, mouthZ,
                mouthWidth, mouthY - mouthHeight, mouthZ,
                COLOR_MOUTH_RGB[0], COLOR_MOUTH_RGB[1], COLOR_MOUTH_RGB[2], 1f, packedLight);
    }

    private static void addFrontQuad(Matrix4f matrix, VertexConsumer consumer,
                                      float x1, float y1, float z,
                                      float x2, float y2, float z2,
                                      float r, float g, float b, float a, int packedLight) {
        consumer.vertex(matrix, x1, y1, z).color(r, g, b, a).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(0, 0, 1).endVertex();
        consumer.vertex(matrix, x1, y2, z).color(r, g, b, a).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(0, 0, 1).endVertex();
        consumer.vertex(matrix, x2, y2, z).color(r, g, b, a).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(0, 0, 1).endVertex();
        consumer.vertex(matrix, x2, y1, z).color(r, g, b, a).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(0, 0, 1).endVertex();
    }

    private static void renderShoe(PoseStack poseStack, VertexConsumer consumer, int packedLight) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        float hw = LIMB_WIDTH / 2 + 0.02f;
        float hh = LIMB_WIDTH / 2;
        float hd = LIMB_WIDTH / 2 + 0.02f;

        addBox(matrix, consumer, -hw, -LIMB_LENGTH / 2 - hh, -hd, hw, -LIMB_LENGTH / 2 + hh, hd, COLOR_SHOES_RGB[0], COLOR_SHOES_RGB[1], COLOR_SHOES_RGB[2], 1f, packedLight);
    }

    private static void renderLimb(PoseStack poseStack, VertexConsumer consumer, int packedLight,
                                    float length, float r, float g, float b) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        float hw = LIMB_WIDTH / 2;
        float hd = LIMB_WIDTH / 2;

        addBox(matrix, consumer, -hw, -length / 2, -hd, hw, length / 2, hd, r, g, b, 1f, packedLight);
    }

    private static void addBox(Matrix4f matrix, VertexConsumer consumer,
                                float x1, float y1, float z1, float x2, float y2, float z2,
                                float r, float g, float b, float a, int packedLight) {
        addQuadWithNormal(matrix, consumer, x1, y1, z2, x2, y2, z2, x2, y1, z2, x1, y1, z2, r, g, b, a, packedLight, 0, 0, 1);
        addQuadWithNormal(matrix, consumer, x2, y1, z1, x1, y2, z1, x1, y1, z1, x2, y1, z1, r, g, b, a, packedLight, 0, 0, -1);

        float rt = r * 0.85f, gt = g * 0.85f, bt = b * 0.85f;
        addQuadWithNormal(matrix, consumer, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, rt, gt, bt, a, packedLight, 0, 1, 0);

        addQuadWithNormal(matrix, consumer, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a, packedLight, 0, -1, 0);

        rt = r * 0.8f; gt = g * 0.8f; bt = b * 0.8f;
        addQuadWithNormal(matrix, consumer, x1, y2, z2, x2, y1, z2, x2, y2, z2, x1, y1, z2, rt, gt, bt, a, packedLight, 1, 0, 0);
        rt = r * 0.9f; gt = g * 0.9f; bt = b * 0.9f;
        addQuadWithNormal(matrix, consumer, x1, y1, z1, x1, y2, z1, x2, y1, z1, x2, y2, z1, rt, gt, bt, a, packedLight, -1, 0, 0);
    }

    private static void addQuadWithNormal(Matrix4f matrix, VertexConsumer consumer,
                                           float x1, float y1, float z1, float x2, float y2, float z2,
                                           float x3, float y3, float z3, float x4, float y4, float z4,
                                           float r, float g, float b, float a, int packedLight,
                                           float nx, float ny, float nz) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, a).uv(0, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(nx, ny, nz).endVertex();
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, a).uv(0, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(nx, ny, nz).endVertex();
        consumer.vertex(matrix, x3, y3, z3).color(r, g, b, a).uv(1, 1).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(nx, ny, nz).endVertex();
        consumer.vertex(matrix, x4, y4, z4).color(r, g, b, a).uv(1, 0).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(nx, ny, nz).endVertex();
    }
}