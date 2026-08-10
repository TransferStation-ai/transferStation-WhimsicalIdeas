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
                                    MultiBufferSource bufferSource, int packedLight) {
        renderWithData(entity, poseStack, bufferSource, packedLight, resolveModelData(entity));
    }

    public static void renderModelLOD(LivingEntity entity, PoseStack poseStack,
                                       MultiBufferSource bufferSource, int packedLight,
                                       int lodLevel) {
        renderWithData(entity, poseStack, bufferSource, packedLight, resolveLodData(entity, lodLevel));
    }

    private static void renderWithData(LivingEntity entity, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight,
                                        SourceModelData data) {
        if (data == null || data.meshes.isEmpty()) return;

        poseStack.pushPose();

        // 整体朝向补偿：把 Source 模型前向对齐到 Minecraft 实体前向（-Z，看向玩家）
        // Sprite shader 模型改为面向相机（billboard）
        if (needsSpriteBillboard(data)) {
            applySpriteBillboard(poseStack);
        } else {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));
        }
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
            renderMeshWithEmissiveSupport(mesh, matrix, normalMatrix, bufferSource, packedLight);
        }

        poseStack.popPose();
    }

    private static final int fullbrightLight = 0xF000F0;

    private static boolean isSkippedShader(SourceModelData.MeshData mesh) {
        if (mesh.shaderType == null) return false;
        VmtParser.ShaderType st = VmtParser.ShaderType.fromName(mesh.shaderType);
        return st == VmtParser.ShaderType.SKYBOX || st == VmtParser.ShaderType.TOOL_TEXTURE;
    }

    private static boolean isSelfIllumShader(SourceModelData.MeshData mesh) {
        if (mesh.shaderType == null) return false;
        VmtParser.ShaderType st = VmtParser.ShaderType.fromName(mesh.shaderType);
        return st == VmtParser.ShaderType.UNLIT_GENERIC || st == VmtParser.ShaderType.EYE_REFRACT;
    }

    private static boolean needsSpriteBillboard(SourceModelData data) {
        for (SourceModelData.MeshData mesh : data.meshes) {
            if (mesh.shaderType != null
                    && VmtParser.ShaderType.fromName(mesh.shaderType) == VmtParser.ShaderType.SPRITE) {
                return true;
            }
        }
        return false;
    }

    private static void applySpriteBillboard(PoseStack poseStack) {
        net.minecraft.client.Camera camera =
            net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        poseStack.mulPose(camera.rotation().conjugate());
        // Source sprite 前向 +X → MC 前向 -Z：绕 Y 轴补偿 90°
        poseStack.mulPose(com.mojang.math.Axis.YP.rotation((float) Math.PI / 2));
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
                                                        int packedLight) {
        if (mesh.indices.length < 3) return;
        if (isSkippedShader(mesh)) return;

        ResourceLocation texture = mesh.texture;
        if (texture != null) {
            ModelLoadManager.getColorResolver().ensureTextureRegistered(texture);
        }
        boolean selfIllum = mesh.selfIllum || isSelfIllumShader(mesh);
        boolean alphaTest = mesh.alphaTest
                || (mesh.shaderType != null
                    && VmtParser.ShaderType.fromName(mesh.shaderType) == VmtParser.ShaderType.EYE_REFRACT);
        RenderType renderType = selectRenderType(texture, mesh.translucent, alphaTest, selfIllum, mesh.noCull);
        int light = selfIllum ? fullbrightLight : packedLight;
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
            // Every MC entity RenderType assembles vertices as QUADS (4 per primitive, split
            // into two triangles 0-1-2 / 0-2-3). The Source mesh is triangles (3 per primitive),
            // so we must emit a 4th vertex identical to the first to keep the quad degenerate.
            // Without this, the triangle stream is mis-grouped and the surface shreds to dots/lines.
            emitVertex(consumer, matrix, normalMatrix, ax, ay, az, anx, any, anz, au, av, light, cr, cg, cb, ca);
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

    /**
     * Combine per-bone world matrices with their inverse-bind matrices:
     * {@code eff[i] = boneMatrices[i] * invBind[i]} — the Source-engine skin matrix.
     * Returns {@code boneMatrices} unchanged when no invBind data is available.
     * invBind entries are the inverse of the renderer's own bind world, already in
     * MC space (see {@code ModelLoadManager.computeInvBindMatrices}).
     */
    public static float[][] combineInvBind(float[][] boneMatrices, List<float[]> invBind) {
        if (boneMatrices == null || invBind == null || invBind.size() != boneMatrices.length) {
            return boneMatrices;
        }
        float[][] combined = new float[boneMatrices.length][16];
        for (int i = 0; i < boneMatrices.length; i++) {
            org.joml.Matrix4f mat = new org.joml.Matrix4f().set(boneMatrices[i]);
            mat.mul(new org.joml.Matrix4f().set(invBind.get(i)));
            mat.get(combined[i]);
        }
        return combined;
    }

    public static void renderWithSkinning(Entity entity, PoseStack poseStack,
                                            MultiBufferSource bufferSource, int packedLight,
                                            float[][] boneMatrices) {
        SourceModelData data = resolveModelData(entity instanceof LivingEntity le ? le : null);
        if (data == null || data.meshes.isEmpty()) return;

        poseStack.pushPose();

        // 整体朝向补偿：与 renderWithData 保持一致（见上方说明）
        // Sprite shader 模型改为面向相机（billboard）
        if (needsSpriteBillboard(data)) {
            applySpriteBillboard(poseStack);
        } else {
            poseStack.mulPose(com.mojang.math.Axis.YP.rotation(MODEL_FACING_YAW));
        }

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

        float[][] effectiveMatrices = combineInvBind(boneMatrices, data.invBindMatrices);
        for (SourceModelData.MeshData mesh : data.meshes) {
            renderMeshSkinned(mesh, matrix, normalMatrix, bufferSource, packedLight, effectiveMatrices);
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

    /**
     * Blend a single skinned vertex position/normal from its bone weights.
     * Pure computation, shared by {@link #renderMeshSkinned} and the model debug
     * screen (and unit tests). Returns {@code false} when the vertex has no usable
     * weights, in which case the caller should fall back to the raw vertex.
     *
     * @param vertices   interleaved vertex data (pos3, nrm3, uv2 per vertex)
     * @param offset     vertex float offset (vertexIndex * 8)
     * @param weights    per-vertex bone weights (4 per vertex)
     * @param boneIdx    per-vertex bone indices (4 per vertex)
     * @param vertIdx    vertex index (for the weight arrays)
     * @param boneMatrices world-space bone matrices
     * @param outPos     blended position (w=1)
     * @param outNormal  blended normal (w=0)
     * @return true if blending applied
     */
    public static boolean skinVertex(float[] vertices, int offset,
                                     float[] weights, int[] boneIdx, int vertIdx,
                                     float[][] boneMatrices,
                                     Vector4f outPos, Vector4f outNormal) {
        return skinVertex(vertices, offset, weights, boneIdx, vertIdx, boneMatrices,
            new Matrix4f(), new Vector4f(), new Vector4f(), outPos, outNormal);
    }

    /**
     * Scratch-based variant of {@link #skinVertex} for allocation-free hot paths.
     * Callers may reuse the same {@code boneMat}/{@code tempPos}/{@code tempNormal}
     * instances across calls.
     */
    public static boolean skinVertex(float[] vertices, int offset,
                                     float[] weights, int[] boneIdx, int vertIdx,
                                     float[][] boneMatrices,
                                     Matrix4f boneMat, Vector4f tempPos, Vector4f tempNormal,
                                     Vector4f outPos, Vector4f outNormal) {
        if (weights == null || boneIdx == null) return false;
        float vx = vertices[offset];
        float vy = vertices[offset + 1];
        float vz = vertices[offset + 2];
        float nx = vertices[offset + 3];
        float ny = vertices[offset + 4];
        float nz = vertices[offset + 5];

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
            boneMat.set(boneMatrices[bi]);
            tempPos.set(vx, vy, vz, 1.0f);
            boneMat.transform(tempPos);
            sx += tempPos.x() * weight;
            sy += tempPos.y() * weight;
            sz += tempPos.z() * weight;

            tempNormal.set(nx, ny, nz, 0.0f);
            boneMat.transform(tempNormal);
            snx += tempNormal.x() * weight;
            sny += tempNormal.y() * weight;
            snz += tempNormal.z() * weight;
        }

        if (totalWeight > 0.001f) {
            outPos.set(sx / totalWeight, sy / totalWeight, sz / totalWeight, 1.0f);
            outNormal.set(snx / totalWeight, sny / totalWeight, snz / totalWeight, 0.0f);
            return true;
        }
        return false;
    }

    /** Skinned single-mesh renderer, shared by the entity path and {@code ModelViewport}. */
    public static void renderMeshSkinned(SourceModelData.MeshData mesh,
                                            Matrix4f modelMatrix,
                                            Matrix3f normalMatrix,
                                            MultiBufferSource bufferSource, int packedLight,
                                            float[][] boneMatrices) {
        if (mesh.indices.length < 3) return;
        if (isSkippedShader(mesh)) return;

        ResourceLocation texture = mesh.texture;
        if (texture != null) {
            ModelLoadManager.getColorResolver().ensureTextureRegistered(texture);
        }
        boolean selfIllum = mesh.selfIllum || isSelfIllumShader(mesh);
        boolean alphaTest = mesh.alphaTest
                || (mesh.shaderType != null
                    && VmtParser.ShaderType.fromName(mesh.shaderType) == VmtParser.ShaderType.EYE_REFRACT);
        RenderType renderType = selectRenderType(texture, mesh.translucent, alphaTest, selfIllum, mesh.noCull);
        int light = selfIllum ? fullbrightLight : packedLight;
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

            // Validate all three indices up front like the non-skinned path does. A per-vertex
            // continue below would drop individual vertices and mis-group the QUADS stream,
            // recreating stray point-to-point connections.
            int i0v = indices[i];
            int i1v = indices[i + 1];
            int i2v = indices[i + 2];
            int i0o = i0v * 8;
            int i1o = i1v * 8;
            int i2o = i2v * 8;
            if (i0o < 0 || i1o < 0 || i2o < 0) continue;
            if (i0o + 7 >= vertices.length || i1o + 7 >= vertices.length || i2o + 7 >= vertices.length) continue;

            for (int vi = 0; vi < 4; vi++) {
                // MC entity RenderTypes are QUADS-assembled (4 vertices per primitive, split
                // into triangles 0-1-2 / 0-2-3). The Source mesh is triangles (3 per primitive),
                // so submit the first vertex again as the 4th to keep the quad degenerate. Without
                // this the vertex stream is mis-grouped and the surface shreds to dots/lines.
                int vertIdx = vi == 3 ? i0v : indices[i + vi];
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

                if (hasWeights && skinVertex(vertices, offset, weights, boneIdx, vertIdx,
                        boneMatrices, buf.boneMat, buf.tempPos, buf.tempNormal,
                        buf.skinnedPos, buf.skinnedNormal)) {
                    fx = buf.skinnedPos.x();
                    fy = buf.skinnedPos.y();
                    fz = buf.skinnedPos.z();
                    fnx = buf.skinnedNormal.x();
                    fny = buf.skinnedNormal.y();
                    fnz = buf.skinnedNormal.z();
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
