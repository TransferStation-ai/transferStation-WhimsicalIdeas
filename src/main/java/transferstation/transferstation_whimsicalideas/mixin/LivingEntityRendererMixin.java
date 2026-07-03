package transferstation.transferstation_whimsicalideas.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import transferstation.transferstation_whimsicalideas.client.GmodModelConfig;
import transferstation.transferstation_whimsicalideas.client.model.MdlModelRenderer;
import transferstation.transferstation_whimsicalideas.client.GmodModelRenderer;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>>
        extends EntityRenderer<T> implements RenderLayerParent<T, M> {

    protected LivingEntityRendererMixin(EntityRendererProvider.Context context) {
        super(context);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(T entity, float entityYaw, float partialTicks,
                          PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                          CallbackInfo ci) {
        if (entity instanceof Player) {
            if (!GmodModelConfig.isPlayerModelEnabled()) return;
        } else {
            if (!GmodModelConfig.isMobModelEnabled()) return;
        }

        ci.cancel();

        poseStack.pushPose();
        if (MdlModelRenderer.isModelLoaded()) {
            MdlModelRenderer.render(entity, poseStack, bufferSource, packedLight, partialTicks);
        } else {
            GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
        }
        poseStack.popPose();

        if (this.shouldShowName(entity)) {
            this.renderNameTag(entity, entity.getDisplayName(), poseStack, bufferSource, packedLight);
        }
    }
}