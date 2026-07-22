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
 * Uses Minecraft's DynamicLight system (or custom lightmaps).
 * For MVP: doesn't add actual dynamic lighting (complex); renders as bright sprite instead.
 * Full dynamic light can be integrated with existing light systems later.
 */
public class LightParticleRenderer implements ParticleRenderer {
    private ResourceLocation texture;

    public LightParticleRenderer(ResourceLocation texture) {
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

        for (Particle p : particles) {
            float progress = p.getProgress();
            if (progress >= 1f) continue;

            float alpha = p.getAlpha();
            if (alpha < 0.01f) continue;

            float rx = p.position.x - cameraPos.x;
            float ry = p.position.y - cameraPos.y;
            float rz = p.position.z - cameraPos.z;
            float halfSize = p.size * 0.5f;

            // Billboard quad facing camera (same as SpriteParticleRenderer)
            Vector3f up = camera.getUpVector();
            Vector3f right = camera.getLeftVector();

            Vector3f v0 = new Vector3f(rx - right.x * halfSize + up.x * halfSize,
                                       ry - right.y * halfSize + up.y * halfSize,
                                       rz - right.z * halfSize + up.z * halfSize);
            Vector3f v1 = new Vector3f(rx + right.x * halfSize + up.x * halfSize,
                                       ry + right.y * halfSize + up.y * halfSize,
                                       rz + right.z * halfSize + up.z * halfSize);
            Vector3f v2 = new Vector3f(rx + right.x * halfSize - up.x * halfSize,
                                       ry + right.y * halfSize - up.y * halfSize,
                                       rz + right.z * halfSize - up.z * halfSize);
            Vector3f v3 = new Vector3f(rx - right.x * halfSize - up.x * halfSize,
                                       ry - right.y * halfSize - up.y * halfSize,
                                       rz - right.z * halfSize - up.z * halfSize);

            // Light particles render brighter: boost color toward white
            float r = Math.min(1f, p.color.x * 1.5f);
            float g = Math.min(1f, p.color.y * 1.5f);
            float b = Math.min(1f, p.color.z * 1.5f);

            float u0 = 0f, v0t = 0f, u1 = 1f, v1t = 1f;

            builder.vertex(matrix, v0.x, v0.y, v0.z).uv(u1, v1t).color(r, g, b, alpha).uv2(light).endVertex();
            builder.vertex(matrix, v1.x, v1.y, v1.z).uv(u0, v1t).color(r, g, b, alpha).uv2(light).endVertex();
            builder.vertex(matrix, v2.x, v2.y, v2.z).uv(u0, v0t).color(r, g, b, alpha).uv2(light).endVertex();
            builder.vertex(matrix, v3.x, v3.y, v3.z).uv(u1, v0t).color(r, g, b, alpha).uv2(light).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }
}
