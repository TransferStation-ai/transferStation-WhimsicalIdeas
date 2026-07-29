package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class JavaModelRenderer {

    private static volatile SourceModelData currentModelData;
    private static volatile SourceModelData lodModelData1;
    private static volatile SourceModelData lodModelData2;
    private static volatile SourceModelData lodModelData3;

    private static final Map<Entity, SourceModelData> entityModelData = new WeakHashMap<>();
    private static final Map<Entity, SourceModelData[]> entityLodData = new WeakHashMap<>();

    /**
     * 模型整体朝向补偿（绕 Y 轴，弧度）。
     * Source 模型前向为 +X，经顶点 swap 后映射到 MC +X（东），
     * 而 MC 实体约定前向为 -Z（南/看向玩家）。此旋转把模型整体转向，
     * 使其正面朝向玩家。默认 180°（PI）是最常见修复；若实测仍偏 90°，
     * 改为 Math.PI/2 或 -Math.PI/2 即可。
     * 可通过 Config 或运行时修改：
     * <pre>
     *   JavaModelRenderer.MODEL_FACING_YAW = (float) Math.PI / 2;
     * </pre>
     */
    public static float MODEL_FACING_YAW = (float) Math.PI;

    private static boolean FACING_DEBUG_LOG = true;
    private static boolean facingDebugLogged = false;

    /**
     * 打印当前朝向补偿值，帮助调试模型朝向问题。
     */
    public static void logFacingCompensation() {
        if (!facingDebugLogged) {
            facingDebugLogged = true;
            org.slf4j.LoggerFactory.getLogger(JavaModelRenderer.class)
                .info("[JavaModelRenderer] Current MODEL_FACING_YAW = {} rad ({} deg)",
                    MODEL_FACING_YAW, Math.toDegrees(MODEL_FACING_YAW));
        }
    }

    public static void setModelData(SourceModelData data) {
        org.slf4j.LoggerFactory.getLogger("RenderDiag").info(
            "[RenderDiag] JavaModelRenderer.setModelData: data={}, meshes={}",
            data != null ? data.name : "null",
            data != null ? data.meshes.size() : -1);
        currentModelData = data;
        precomputeLod(data);
    }

    public static void setModelData(Entity entity, SourceModelData data) {
        if (data != null && !data.meshes.isEmpty()) {
            entityModelData.put(entity, data);
            SourceModelData[] lods = new SourceModelData[3];
            lods[0] = data.getMeshesForLod(1);
            lods[1] = data.getMeshesForLod(2);
            lods[2] = data.getMeshesForLod(3);
            entityLodData.put(entity, lods);
        } else {
            entityModelData.remove(entity);
            entityLodData.remove(entity);
        }
    }

    public static SourceModelData getModelData() {
        return currentModelData;
    }

    public static SourceModelData getModelData(Entity entity) {
        return entityModelData.get(entity);
    }

    public static boolean hasModel() {
        return currentModelData != null && !currentModelData.meshes.isEmpty();
    }

    public static boolean hasModel(Entity entity) {
        return entityModelData.containsKey(entity);
    }

    public static void clearEntityModel(Entity entity) {
        entityModelData.remove(entity);
        entityLodData.remove(entity);
    }

    public static void clearAllEntityModels() {
        entityModelData.clear();
        entityLodData.clear();
    }

    private static void precomputeLod(SourceModelData data) {
        if (data != null) {
            lodModelData1 = data.getMeshesForLod(1);
            lodModelData2 = data.getMeshesForLod(2);
            lodModelData3 = data.getMeshesForLod(3);
        } else {
            lodModelData1 = null;
            lodModelData2 = null;
            lodModelData3 = null;
        }
    }

    private static SourceModelData resolveModelData(LivingEntity entity) {
        SourceModelData data = entityModelData.get(entity);
        return data != null ? data : currentModelData;
    }

    private static SourceModelData resolveLodData(LivingEntity entity, int lodLevel) {
        SourceModelData data = entityModelData.get(entity);
        if (data != null) {
            SourceModelData[] lods = entityLodData.get(entity);
            if (lods != null && lodLevel >= 1 && lodLevel <= 3) {
                SourceModelData lod = lods[lodLevel - 1];
                if (lod != null) return lod;
            }
            return data;
        }
        return switch (lodLevel) {
            case 1 -> lodModelData1;
            case 2 -> lodModelData2;
            case 3 -> lodModelData3;
            default -> currentModelData;
        };
    }

    public static void renderModel(LivingEntity entity, PoseStack poseStack,
                                    MultiBufferSource bufferSource, int packedLight,
                                    float partialTicks) {
        renderWithData(entity, poseStack, bufferSource, packedLight, partialTicks, resolveModelData(entity));
    }

    public static void renderModelLOD(LivingEntity entity, PoseStack poseStack,
                                       MultiBufferSource bufferSource, int packedLight,
                                       float partialTicks, int lodLevel) {
        renderWithData(entity, poseStack, bufferSource, packedLight, partialTicks, resolveLodData(entity, lodLevel));
    }

    private static void renderWithData(LivingEntity entity, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight,
                                        float partialTicks, SourceModelData data) {
        if (data == null || data.meshes.isEmpty()) return;

        poseStack.pushPose();

        // 整体朝向补偿：把 Source 模型前向对齐到 Minecraft 实体前向（-Z，看向玩家）
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));
        logFacingCompensation();

        float scale = entity.getBbHeight() / 1.8f;
        poseStack.scale(scale, scale, scale);

        float mdlScale = data.modelScale;
        poseStack.scale(mdlScale, mdlScale, mdlScale);

        float centerX = (data.minX + data.maxX) / 2.0f;
        float centerZ = (data.minZ + data.maxZ) / 2.0f;
        poseStack.translate(-centerX, -data.minY, -centerZ);

        float bob = 0f;
        if (!entity.onGround() && entity.getDeltaMovement().y > 0) {
            bob = (float) Math.sin(entity.tickCount * 0.5f) * 0.02f;
        }
        poseStack.translate(0.0, bob, 0.0);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        for (SourceModelData.MeshData mesh : data.meshes) {
            renderMeshWithEmissiveSupport(mesh, matrix, normalMatrix, bufferSource, packedLight, partialTicks);
        }

        poseStack.popPose();
    }

    private static int fullbrightLight = 0xF000F0;

    public static void setFullbrightLight(int light) {
        fullbrightLight = light;
    }

    private static RenderType selectRenderType(ResourceLocation texture, boolean translucent,
                                                 boolean alphaTest, boolean selfIllum, boolean noCull) {
        ResourceLocation tex = texture != null
                ? texture
                : ResourceLocation.parse("minecraft:textures/block/white_concrete.png");
        if (selfIllum) {
            return noCull ? RenderType.entityCutoutNoCull(tex) : RenderType.entityCutout(tex);
        }
        if (translucent) {
            // entityTranslucentNoCull is not available in this Forge version; fall back to
            // the cutout-no-cull variant which still disables back-face culling for double-sided
            // translucent materials (hair, cloth).
            return noCull ? RenderType.entityCutoutNoCull(tex) : RenderType.entityTranslucent(tex);
        }
        if (alphaTest) {
            return noCull ? RenderType.entityCutoutNoCull(tex) : RenderType.entityCutout(tex);
        }
        if (noCull) {
            return RenderType.entityCutoutNoCull(tex);
        }
        return RenderType.entitySolid(tex);
    }

    private static void renderMeshWithEmissiveSupport(SourceModelData.MeshData mesh, Matrix4f matrix,
                                                        Matrix3f normalMatrix, MultiBufferSource bufferSource,
                                                        int packedLight, float partialTicks) {
        if (mesh.indices.length < 3) return;

        ResourceLocation texture = mesh.texture;
        if (texture != null) {
            ModelLoadManager.getColorResolver().ensureTextureRegistered(texture);
        }
        RenderType renderType = selectRenderType(texture, mesh.translucent, mesh.alphaTest, mesh.selfIllum, mesh.noCull);
        int light = mesh.selfIllum ? fullbrightLight : packedLight;
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        float[] vertices = mesh.vertices;
        int[] indices = mesh.indices;

        float cr = 1f, cg = 1f, cb = 1f, ca = 1f;
        if (mesh.colorTint != null && mesh.colorTint.length >= 3) {
            cr = mesh.colorTint[0];
            cg = mesh.colorTint[1];
            cb = mesh.colorTint[2];
            ca = mesh.colorTint.length >= 4 ? mesh.colorTint[3] : 1f;
        }

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

            emitVertex(consumer, matrix, normalMatrix, ax, ay, az, anx, any, anz, au, av, light, cr, cg, cb, ca);
            emitVertex(consumer, matrix, normalMatrix, bx, by, bz, bnx, bny, bnz, bu, bv, light, cr, cg, cb, ca);
            emitVertex(consumer, matrix, normalMatrix, cx, cy, cz, cnx, cny, cnz, cu, cv, light, cr, cg, cb, ca);
        }
    }

    private static void emitVertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                                    float x, float y, float z,
                                    float nx, float ny, float nz,
                                    float u, float v, int packedLight,
                                    float r, float g, float b, float a) {
        consumer.vertex(matrix, x, y, z)
            .color(r, g, b, a)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(normalMatrix, nx, ny, nz)
            .endVertex();
    }

    // ====== VERTEX SKINNING SUPPORT ======

    public static void renderWithSkinning(Entity entity, PoseStack poseStack,
                                            MultiBufferSource bufferSource, int packedLight,
                                            float partialTicks, float[][] boneMatrices) {
        SourceModelData data = resolveModelData(entity instanceof LivingEntity le ? le : null);
        if (data == null || data.meshes.isEmpty()) return;

        poseStack.pushPose();

        // 整体朝向补偿：与 renderWithData 保持一致（见上方说明）
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));

        float scale = entity instanceof LivingEntity le ? le.getBbHeight() / 1.8f : 1.0f;
        poseStack.scale(scale, scale, scale);

        float mdlScale = data.modelScale;
        poseStack.scale(mdlScale, mdlScale, mdlScale);

        float centerX = (data.minX + data.maxX) / 2.0f;
        float centerZ = (data.minZ + data.maxZ) / 2.0f;
        poseStack.translate(-centerX, -data.minY, -centerZ);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        for (SourceModelData.MeshData mesh : data.meshes) {
            renderMeshSkinned(mesh, matrix, normalMatrix, bufferSource, packedLight, partialTicks, boneMatrices);
        }

        poseStack.popPose();
    }

    private static final class ReusableBuffers {
        final Vector4f skinnedPos = new Vector4f();
        final Vector4f skinnedNormal = new Vector4f();
        final Vector4f tempPos = new Vector4f();
        final Vector4f tempNormal = new Vector4f();
        final org.joml.Vector3f normal3 = new org.joml.Vector3f();
        final Matrix4f boneMat = new Matrix4f();
    }

    private static final ThreadLocal<ReusableBuffers> REUSABLE_BUFFERS =
        ThreadLocal.withInitial(ReusableBuffers::new);

    private static void renderMeshSkinned(SourceModelData.MeshData mesh,
                                            Matrix4f modelMatrix,
                                            Matrix3f normalMatrix,
                                            MultiBufferSource bufferSource, int packedLight,
                                            float partialTicks, float[][] boneMatrices) {
        if (mesh.indices.length < 3) return;

        ResourceLocation texture = mesh.texture;
        if (texture != null) {
            ModelLoadManager.getColorResolver().ensureTextureRegistered(texture);
        }
        RenderType renderType = selectRenderType(texture, mesh.translucent, mesh.alphaTest, mesh.selfIllum, mesh.noCull);
        int light = mesh.selfIllum ? fullbrightLight : packedLight;
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        float[] vertices = mesh.vertices;
        int[] indices = mesh.indices;
        float[] weights = mesh.boneWeights;
        int[] boneIdx = mesh.boneIndices;
        // Guard against short bone arrays: both must hold 4 entries per vertex, and the
        // last vertex we may index (indices.length-1) must fit. Otherwise boneIdx[wOffset+w]
        // can throw ArrayIndexOutOfBounds during render.
        boolean hasWeights = (weights != null && boneIdx != null
                && weights.length >= 4 && boneIdx.length >= 4
                && weights.length >= (indices.length / 3) * 4
                && boneIdx.length >= (indices.length / 3) * 4);

        float cr = 1f, cg = 1f, cb = 1f, ca = 1f;
        if (mesh.colorTint != null && mesh.colorTint.length >= 3) {
            cr = mesh.colorTint[0];
            cg = mesh.colorTint[1];
            cb = mesh.colorTint[2];
            ca = mesh.colorTint.length >= 4 ? mesh.colorTint[3] : 1f;
        }

        ReusableBuffers buf = REUSABLE_BUFFERS.get();

        for (int i = 0; i < indices.length; i += 3) {
            if (i + 2 >= indices.length) break;

            for (int vi = 0; vi < 3; vi++) {
                int vertIdx = indices[i + vi];
                int offset = vertIdx * 8;

                if (offset < 0 || offset + 7 >= vertices.length) continue;

                float vx = vertices[offset];
                float vy = vertices[offset + 1];
                float vz = vertices[offset + 2];
                float nx = vertices[offset + 3];
                float ny = vertices[offset + 4];
                float nz = vertices[offset + 5];
                float u = vertices[offset + 6];
                float v = vertices[offset + 7];

                float fx = vx, fy = vy, fz = vz;
                float fnx = nx, fny = ny, fnz = nz;

                if (hasWeights) {
                    int wOffset = vertIdx * 4;
                    float sx = 0, sy = 0, sz = 0;
                    float snx = 0, sny = 0, snz = 0;
                    float totalWeight = 0f;

                    for (int w = 0; w < 4; w++) {
                        float weight = weights[wOffset + w];
                        if (weight < 0.001f) continue;
                        int bi = boneIdx[wOffset + w];
                        if (bi < 0 || bi >= boneMatrices.length) continue;

                        totalWeight += weight;

                        buf.boneMat.set(boneMatrices[bi]);
                        buf.tempPos.set(vx, vy, vz, 1.0f);
                        buf.boneMat.transform(buf.tempPos);
                        sx += buf.tempPos.x() * weight;
                        sy += buf.tempPos.y() * weight;
                        sz += buf.tempPos.z() * weight;

                        buf.tempNormal.set(nx, ny, nz, 0.0f);
                        buf.boneMat.transform(buf.tempNormal);
                        snx += buf.tempNormal.x() * weight;
                        sny += buf.tempNormal.y() * weight;
                        snz += buf.tempNormal.z() * weight;
                    }

                    if (totalWeight > 0.001f) {
                        fx = sx / totalWeight;
                        fy = sy / totalWeight;
                        fz = sz / totalWeight;
                        fnx = snx / totalWeight;
                        fny = sny / totalWeight;
                        fnz = snz / totalWeight;
                    }
                }

                buf.skinnedPos.set(fx, fy, fz, 1.0f);
                modelMatrix.transform(buf.skinnedPos);

                buf.normal3.set(fnx, fny, fnz);
                normalMatrix.transform(buf.normal3);
                buf.skinnedNormal.set(buf.normal3.x, buf.normal3.y, buf.normal3.z, 0.0f);

                consumer.vertex(buf.skinnedPos.x(), buf.skinnedPos.y(), buf.skinnedPos.z())
                    .color(cr, cg, cb, ca)
                    .uv(u, v)
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(light)
                    .normal(buf.skinnedNormal.x(), buf.skinnedNormal.y(), buf.skinnedNormal.z())
                    .endVertex();
            }
        }
    }
}
