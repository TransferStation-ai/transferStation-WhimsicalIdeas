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
 * Renders ribbon/strip trails following particle paths.
 * Uses each particle's previousPosition to form a quad strip.
 * Similar to SpriteParticleRenderer but connects sequential particle
 * positions into ribbon geometry facing the camera.
 */
public class TrailParticleRenderer implements ParticleRenderer {
    private final ResourceLocation texture;

    public TrailParticleRenderer(ResourceLocation texture) {
        this.texture = texture;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        if (particles.size() < 2) return;

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

        // Determine trail length from definition
        int maxTrail = emitter.getDefinition().renderer != null
            ? emitter.getDefinition().renderer.trailSegments : 8;
        maxTrail = Math.min(maxTrail, particles.size());

        // Connect adjacent particles into ribbon quads facing camera
        for (int i = 0; i < maxTrail - 1; i++) {
            Particle a = particles.get(i);
            Particle b = particles.get(i + 1);
            if (!a.alive || !b.alive) continue;

            float alpha = (a.getAlpha() + b.getAlpha()) * 0.5f;
            if (alpha < 0.01f) continue;

            // Use midpoint of current and previous for ribbon direction
            Vector3f dir = new Vector3f(b.position).sub(a.position);
            float len = dir.length();
            if (len < 0.01f) continue;
            dir.normalize();

            // Camera-facing ribbon: compute right vector perpendicular to both dir and view
            Vector3f toCamera = new Vector3f(cameraPos).sub(
                (a.position.x + b.position.x) * 0.5f,
                (a.position.y + b.position.y) * 0.5f,
                (a.position.z + b.position.z) * 0.5f
            ).normalize();

            Vector3f right = new Vector3f(dir).cross(toCamera);
            float rLen = right.length();
            if (rLen < 0.01f) {
                // dir parallel to view direction, use camera up as fallback
                right.set(camera.getUpVector());
            } else {
                right.div(rLen);
            }

            float halfWidth = (a.size + b.size) * 0.25f;

            // Compute quad vertices in camera-relative space
            float ax = a.position.x - cameraPos.x;
            float ay = a.position.y - cameraPos.y;
            float az = a.position.z - cameraPos.z;
            float bx = b.position.x - cameraPos.x;
            float by = b.position.y - cameraPos.y;
            float bz = b.position.z - cameraPos.z;

            float rx = right.x * halfWidth;
            float ry = right.y * halfWidth;
            float rz = right.z * halfWidth;

            float u0 = (float) i / maxTrail;
            float u1 = (float) (i + 1) / maxTrail;

            // Two triangles forming a quad:
            // a(-right) -> b(-right) -> b(+right) -> a(+right)
            builder.vertex(matrix, ax - rx, ay - ry, az - rz).uv(u0, 1f).color(a.color.x, a.color.y, a.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, bx - rx, by - ry, bz - rz).uv(u1, 1f).color(b.color.x, b.color.y, b.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, bx + rx, by + ry, bz + rz).uv(u1, 0f).color(b.color.x, b.color.y, b.color.z, alpha).uv2(light).endVertex();
            builder.vertex(matrix, ax + rx, ay + ry, az + rz).uv(u0, 0f).color(a.color.x, a.color.y, a.color.z, alpha).uv2(light).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }
}
