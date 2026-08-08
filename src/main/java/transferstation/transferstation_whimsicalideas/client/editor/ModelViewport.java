package transferstation.transferstation_whimsicalideas.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.model.JavaModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.util.Map;

/**
 * Reusable in-game 3D viewport for the model/animation editors.
 * <p>
 * Renders a {@link SourceModelData} inside a screen rectangle with an orbit
 * camera (drag to rotate, wheel to zoom). Bone gizmos can be drawn on top.
 * The mesh drawing logic mirrors {@code JavaModelRenderer} so the editor shows
 * the same geometry the game renders.
 */
public class ModelViewport {

    // Orbit camera state
    private float yaw = 0.35f;
    private float pitch = 0.25f;
    private float distance = 4.0f;
    private float targetX = 0f, targetY = 1.0f, targetZ = 0f;

    // Viewport rectangle (screen space)
    private int x, y, width, height;

    private SourceModelData model;

    // Optional per-bone override transforms applied on top of the model's bind pose.
    // Keyed by bone index. null entry = use model default.
    private final Map<Integer, BoneOverride> boneOverrides = new java.util.HashMap<>();

    // Optional world-space bone matrices (one 4x4 per bone index) for vertex skinning.
    // When set (non-null), meshes with vertex weights are rendered skinned; otherwise
    // the mesh is rendered in bind pose exactly like the entity path.
    private float[][] boneMatrices;

    /**
     * @param pos local position offset (added to bind pos)
     * @param rot euler rotation in radians (applied on top of bind)
     */
    public record BoneOverride(float[] pos, float[] rot) {
    }

    public void setRect(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setModel(SourceModelData model) {
        this.model = model;
        this.boneOverrides.clear();
        if (model != null) {
            float cx = (model.minX + model.maxX) / 2.0f;
            float cy = (model.minY + model.maxY) / 2.0f;
            float cz = (model.minZ + model.maxZ) / 2.0f;
            this.targetX = cx;
            this.targetY = cy;
            this.targetZ = cz;
            float span = Math.max(model.maxX - model.minX,
                    Math.max(model.maxY - model.minY, model.maxZ - model.minZ));
            this.distance = Math.max(1.5f, span * 1.4f);
        }
    }

    public SourceModelData getModel() {
        return model;
    }

    public void setBoneOverride(int boneIndex, BoneOverride override) {
        if (override == null) boneOverrides.remove(boneIndex);
        else boneOverrides.put(boneIndex, override);
    }

    public BoneOverride getBoneOverride(int boneIndex) {
        return boneOverrides.get(boneIndex);
    }

    public Map<Integer, BoneOverride> getBoneOverrides() {
        return boneOverrides;
    }

    public void clearBoneOverrides() {
        boneOverrides.clear();
    }

    /**
     * Provide world-space bone matrices (one 4x4 per bone index, column-major) used for
     * vertex skinning. When non-null, meshes with vertex weights render skinned.
     * Pass {@code null} to fall back to bind-pose rendering.
     */
    public void setBoneMatrices(float[][] boneMatrices) {
        this.boneMatrices = boneMatrices;
    }

    public void clearBoneMatrices() {
        this.boneMatrices = null;
    }

    public boolean hasBoneMatrices() {
        return boneMatrices != null;
    }

    public void orbit(float dYaw, float dPitch) {
        yaw += dYaw;
        pitch += dPitch;
        float limit = (float) (Math.PI / 2 - 0.05);
        if (pitch > limit) pitch = limit;
        if (pitch < -limit) pitch = -limit;
    }

    public void zoom(float amount) {
        distance *= (1.0f + amount);
        if (distance < 0.5f) distance = 0.5f;
        if (distance > 200.0f) distance = 200.0f;
    }

    public void pan(float dx, float dy) {
        float cosYaw = (float) Math.cos(yaw);
        float sinYaw = (float) Math.sin(yaw);
        float cosPitch = (float) Math.cos(pitch);
        float sinPitch = (float) Math.sin(pitch);
        Vector3f right = new Vector3f(cosYaw, 0, -sinYaw);
        Vector3f up = new Vector3f(-sinYaw * sinPitch, cosPitch, -cosYaw * sinPitch);
        float scale = distance * 0.0015f;
        targetX -= right.x * dx * scale - up.x * dy * scale;
        targetY -= right.y * dx * scale - up.y * dy * scale;
        targetZ -= right.z * dx * scale - up.z * dy * scale;
    }

    /** Render the model into the configured rectangle. Call inside a Screen.render. */
    public void render(PoseStack guiPose) {
        if (model == null || model.meshes.isEmpty()) return;

        com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        int scX = (int) (x * scale);
        int scY = (int) (window.getHeight() - (y + height) * scale);
        int scW = (int) (width * scale);
        int scH = (int) (height * scale);
        RenderSystem.enableScissor(scX, scY, scW, scH);

        try {
        com.mojang.blaze3d.vertex.Tesselator t = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bb = t.getBuilder();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        bb.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        bb.vertex(x, y, 0).color(0.12f, 0.12f, 0.14f, 1f).endVertex();
        bb.vertex(x, y + height, 0).color(0.12f, 0.12f, 0.14f, 1f).endVertex();
        bb.vertex(x + width, y + height, 0).color(0.12f, 0.12f, 0.14f, 1f).endVertex();
        bb.vertex(x + width, y, 0).color(0.12f, 0.12f, 0.14f, 1f).endVertex();
        t.end();

        PoseStack ps = new PoseStack();
        ps.translate(x + width / 2.0, y + height / 2.0, 0);
        float persp = 3.0f / distance;
        ps.scale(persp, persp, persp);
        ps.mulPose(new Quaternionf().rotationY(yaw));
        ps.mulPose(new Quaternionf().rotationX(pitch));
        ps.translate(-targetX, -targetY, -targetZ);

        float mdlScale = model.modelScale;
        ps.scale(mdlScale, mdlScale, mdlScale);
        float centerX = (model.minX + model.maxX) / 2.0f;
        float centerZ = (model.minZ + model.maxZ) / 2.0f;
        ps.translate(-centerX, -model.minY, -centerZ);

        MultiBufferSource.BufferSource bufferSource =
                Minecraft.getInstance().renderBuffers().bufferSource();
        int light = LightTexture.pack(15, 15);

        PoseStack.Pose pose = ps.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        float[][] effectiveMatrices = JavaModelRenderer.combineInvBind(boneMatrices, model.invBindMatrices);
        for (SourceModelData.MeshData mesh : model.meshes) {
            if (effectiveMatrices != null && hasVertexWeights(mesh)) {
                JavaModelRenderer.renderMeshSkinned(mesh, matrix, normalMatrix, bufferSource, light, effectiveMatrices);
            } else {
                renderMesh(mesh, matrix, normalMatrix, bufferSource, light);
            }
        }

        bufferSource.endBatch();
        } finally {
            // Restore a sane default shader and disable scissor so the GL state set
            // above never leaks into subsequent rendering passes, even if renderMesh throws.
            RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
            RenderSystem.disableScissor();
        }
    }

    private void renderMesh(SourceModelData.MeshData mesh, Matrix4f matrix, Matrix3f normalMatrix,
                            MultiBufferSource bufferSource, int packedLight) {
        if (mesh.indices.length < 3) return;
        ResourceLocation texture = mesh.texture;
        if (texture != null) {
            ModelLoadManager.getColorResolver().ensureTextureRegistered(texture);
        }
        RenderType renderType = selectRenderType(texture, mesh.translucent, mesh.alphaTest, mesh.selfIllum);
        int light = mesh.selfIllum ? 0xF000F0 : packedLight;
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        float[] vertices = mesh.vertices;
        int[] indices = mesh.indices;

        float cr = 1f, cg = 1f, cb = 1f, ca = 1f;
        if (mesh.colorTint != null && mesh.colorTint.length >= 3) {
            cr = mesh.colorTint[0]; cg = mesh.colorTint[1]; cb = mesh.colorTint[2];
            ca = mesh.colorTint.length >= 4 ? mesh.colorTint[3] : 1f;
        }

        for (int i = 0; i + 2 < indices.length; i += 3) {
            int i0 = indices[i] * 8;
            int i1 = indices[i + 1] * 8;
            int i2 = indices[i + 2] * 8;
            if (i0 < 0 || i1 < 0 || i2 < 0) continue;
            if (i0 + 7 >= vertices.length || i1 + 7 >= vertices.length || i2 + 7 >= vertices.length) continue;

            emit(consumer, matrix, normalMatrix, vertices, i0, light, cr, cg, cb, ca);
            emit(consumer, matrix, normalMatrix, vertices, i1, light, cr, cg, cb, ca);
            emit(consumer, matrix, normalMatrix, vertices, i2, light, cr, cg, cb, ca);
        }
    }

    private void emit(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                      float[] v, int o, int light, float r, float g, float b, float a) {
        consumer.vertex(matrix, v[o], v[o + 1], v[o + 2])
                .color(r, g, b, a)
                .uv(v[o + 6], v[o + 7])
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normalMatrix, v[o + 3], v[o + 4], v[o + 5])
                .endVertex();
    }

    /** Whether a mesh carries per-vertex bone weights usable for skinning. */
    private static boolean hasVertexWeights(SourceModelData.MeshData mesh) {
        float[] weights = mesh.boneWeights;
        int[] boneIdx = mesh.boneIndices;
        return weights != null && boneIdx != null
                && weights.length >= 4 && boneIdx.length >= 4
                && weights.length >= (mesh.indices.length / 3) * 4
                && boneIdx.length >= (mesh.indices.length / 3) * 4;
    }

    private static RenderType selectRenderType(ResourceLocation texture, boolean translucent,
                                               boolean alphaTest, boolean selfIllum) {
        ResourceLocation tex = texture != null
                ? texture
                : ResourceLocation.parse("minecraft:textures/block/white_concrete.png");
        if (selfIllum) return RenderType.entityCutout(tex);
        if (translucent) return RenderType.entityTranslucent(tex);
        if (alphaTest) return RenderType.entityCutout(tex);
        return RenderType.entitySolid(tex);
    }

    /** Compute the world-space (model-local) position of a bone for gizmo placement. */
    public Vector3f boneWorldPos(int boneIndex) {
        if (model == null || boneIndex < 0 || boneIndex >= model.bones.size()) return null;
        java.util.Stack<Integer> chain = new java.util.Stack<>();
        int cur = boneIndex;
        while (cur >= 0 && cur < model.bones.size()) {
            chain.push(cur);
            cur = model.bones.get(cur).parent();
        }
        Matrix4f m = new Matrix4f().identity();
        while (!chain.isEmpty()) {
            int bi = chain.pop();
            SourceModelData.BoneInfo bone = model.bones.get(bi);
            float px = bone.pos()[0], py = bone.pos()[1], pz = bone.pos()[2];
            float rx = 0, ry = 0, rz = 0;
            BoneOverride ov = boneOverrides.get(bi);
            if (ov != null) {
                px += ov.pos[0]; py += ov.pos[1]; pz += ov.pos[2];
                rx = ov.rot[0]; ry = ov.rot[1]; rz = ov.rot[2];
            }
            Matrix4f local = new Matrix4f().translation(px, py, pz);
            if (bone.quat() != null) {
                Quaternionf q = new Quaternionf(bone.quat()[0], bone.quat()[1], bone.quat()[2], bone.quat()[3]);
                local.rotate(q);
            } else if (bone.rot() != null) {
                local.rotateXYZ(bone.rot()[0], bone.rot()[1], bone.rot()[2]);
            }
            if (rx != 0 || ry != 0 || rz != 0) {
                local.rotateXYZ(rx, ry, rz);
            }
            m.mul(local);
        }
        Vector3f out = new Vector3f(0, 0, 0);
        m.transformPosition(out);
        return out;
    }

    /** Project a model-local point to screen coordinates (for gizmo hit testing). */
    public int[] projectToScreen(Vector3f localPoint) {
        if (model == null) return null;
        PoseStack ps = new PoseStack();
        ps.translate(x + width / 2.0, y + height / 2.0, 0);
        float persp = 3.0f / distance;
        ps.scale(persp, persp, persp);
        ps.mulPose(new Quaternionf().rotationY(yaw));
        ps.mulPose(new Quaternionf().rotationX(pitch));
        ps.translate(-targetX, -targetY, -targetZ);
        float mdlScale = model.modelScale;
        ps.scale(mdlScale, mdlScale, mdlScale);
        float centerX = (model.minX + model.maxX) / 2.0f;
        float centerZ = (model.minZ + model.maxZ) / 2.0f;
        ps.translate(-centerX, -model.minY, -centerZ);

        Vector3f p = new Vector3f(localPoint);
        ps.last().pose().transformPosition(p);
        return new int[]{x + width / 2 + (int) p.x, y + height / 2 + (int) p.y};
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
