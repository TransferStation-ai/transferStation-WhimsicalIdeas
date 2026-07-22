package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;

import java.util.List;

public interface ParticleRenderer {
    void render(PoseStack poseStack, MultiBufferSource bufferSource,
                ParticleEmitter emitter, List<Particle> particles,
                float partialTicks, int packedLight);
}
