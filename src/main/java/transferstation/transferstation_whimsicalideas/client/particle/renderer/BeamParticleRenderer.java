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

import java.util.*;

/**
 * Renders beam/line particles as connected segments between particle pairs
 * or between particle and emitter origin.
 */
public class BeamParticleRenderer implements ParticleRenderer {
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
        float beamWidth = emitter.getDefinition().renderer != null ?
            emitter.getDefinition().renderer.beamWidth : 2f;

        // Connect particles sequentially to form beam
        for (int i = 0; i < particles.size() - 1; i++) {
            Particle a = particles.get(i);
            Particle b = particles.get(i + 1);
            if (!a.alive || !b.alive) continue;

            float alpha = (a.getAlpha() + b.getAlpha()) * 0.5f;
            builder.vertex(matrix,
                a.position.x - cameraPos.x,
                a.position.y - cameraPos.y,
                a.position.z - cameraPos.z)
                .color(a.color.x, a.color.y, a.color.z, alpha).endVertex();
            builder.vertex(matrix,
                b.position.x - cameraPos.x,
                b.position.y - cameraPos.y,
                b.position.z - cameraPos.z)
                .color(b.color.x, b.color.y, b.color.z, alpha).endVertex();
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }
}
