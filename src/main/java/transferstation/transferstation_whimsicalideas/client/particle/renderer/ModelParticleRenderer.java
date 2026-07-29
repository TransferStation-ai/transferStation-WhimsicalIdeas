package transferstation.transferstation_whimsicalideas.client.particle.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.util.List;

/**
 * Renders particles as instanced .mdl models.
 * For MVP: renders each particle as a scaled + rotated model at particle position.
 * Uses existing ModelLoadManager to get the model data.
 */
public class ModelParticleRenderer implements ParticleRenderer {
    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource,
                       ParticleEmitter emitter, List<Particle> particles,
                       float partialTicks, int packedLight) {
        // TODO: For each particle -> load model -> render with pose from particle position/rotation
        // Reuses existing rendering pipeline from GmodModelRenderer / MdlModelRenderer
    }
}
