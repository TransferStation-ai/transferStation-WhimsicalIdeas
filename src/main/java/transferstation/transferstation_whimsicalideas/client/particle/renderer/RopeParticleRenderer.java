package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;

import java.util.List;

/**
 * Renders particles as connected line segments with thickness.
 * Like BeamParticleRenderer but with a fixed number of segments and sag physics.
 * For MVP: renders as connected lines with configurable segments.
 */
public class RopeParticleRenderer implements ParticleRenderer {
    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        if (particles.size() < 2) return;

        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f cameraPos = camera.getPosition().toVector3f();

        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        int ropeSegments = emitter.getDefinition().renderer != null
            ? emitter.getDefinition().renderer.ropeSegments : 16;
        float ropeWidth = emitter.getDefinition().renderer != null
            ? emitter.getDefinition().renderer.ropeWidth : 1f;

        // For MVP: subdivide each particle pair into rope segments with simple sag
        for (int i = 0; i < particles.size() - 1; i++) {
            Particle a = particles.get(i);
            Particle b = particles.get(i + 1);
            if (!a.alive || !b.alive) continue;

            float alpha = (a.getAlpha() + b.getAlpha()) * 0.5f;

            // Subdivide the segment between a and b into ropeSegments sub-segments
            Vector3f dir = new Vector3f(b.position).sub(a.position);
            float segLen = 1.0f / ropeSegments;
            float sagFactor = ropeWidth * 0.5f; // sag amplitude scales with rope width

            for (int s = 0; s < ropeSegments; s++) {
                float t0 = s * segLen;
                float t1 = (s + 1) * segLen;

                // Interpolate position along the line
                float p0x = a.position.x + dir.x * t0;
                float p0y = a.position.y + dir.y * t0;
                float p0z = a.position.z + dir.z * t0;
                float p1x = a.position.x + dir.x * t1;
                float p1y = a.position.y + dir.y * t1;
                float p1z = a.position.z + dir.z * t1;

                // Simple sag: apply downward (Y) offset using sine arc
                float sag0 = (float) Math.sin(t0 * Math.PI) * sagFactor;
                float sag1 = (float) Math.sin(t1 * Math.PI) * sagFactor;
                p0y -= sag0;
                p1y -= sag1;

                builder.vertex(matrix,
                    p0x - cameraPos.x,
                    p0y - cameraPos.y,
                    p0z - cameraPos.z)
                    .color(a.color.x, a.color.y, a.color.z, alpha).endVertex();
                builder.vertex(matrix,
                    p1x - cameraPos.x,
                    p1y - cameraPos.y,
                    p1z - cameraPos.z)
                    .color(b.color.x, b.color.y, b.color.z, alpha).endVertex();
            }
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }
}
