package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;

import java.util.List;

/**
 * Projects a texture onto surfaces using OverlayTexture-like approach.
 * For MVP: renders as flat sprite on the nearest surface below the particle.
 * Decals face upward (+Y) by default, simulating a projected decal on the ground.
 */
public class DecalParticleRenderer implements ParticleRenderer {
    private ResourceLocation texture;

    public DecalParticleRenderer(ResourceLocation texture) {
        this.texture = texture;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        if (particles.isEmpty()) return;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f cameraPos = camera.getPosition().toVector3f();

        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);

        Matrix4f matrix = poseStack.last().pose();
        int light = 0xF000F0;

        float decalSize = emitter.getDefinition().renderer != null
            ? emitter.getDefinition().renderer.decalSize : 16f;

        for (Particle p : particles) {
            float progress = p.getProgress();
            if (progress >= 1f) continue;

            float alpha = p.getAlpha();
            if (alpha < 0.01f) continue;

            // Render as an upward-facing quad (Y-up decal) at particle position
            // This simulates a decal projected onto the ground plane
            float hx = decalSize * 0.5f;
            float rx = p.position.x - cameraPos.x;
            float ry = p.position.y - cameraPos.y;
            float rz = p.position.z - cameraPos.z;

            // Upward-facing quad, offset slightly above surface to avoid z-fighting
            float yOffset = 0.1f;
            float dy = ry + yOffset;

            // Build quad in the XZ plane (square decal: hx == hz)
            float hz = hx;
            builder.vertex(matrix, rx - hx, dy, rz + hz).uv(1f, 1f).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, rx + hx, dy, rz + hz).uv(0f, 1f).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, rx + hx, dy, rz - hz).uv(0f, 0f).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, rx - hx, dy, rz - hz).uv(1f, 0f).color(p.color.x, p.color.y, p.color.z, alpha).uv2(light).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }
}
