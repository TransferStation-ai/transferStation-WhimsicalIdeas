package transferstation.transferstation_whimsicalideas.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import transferstation.transferstation_whimsicalideas.DebugConfig;
import transferstation.transferstation_whimsicalideas.client.GmodModelConfig;
import transferstation.transferstation_whimsicalideas.client.GmodModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.MdlModelRenderer;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, M extends EntityModel<T>> {

    @Shadow protected abstract boolean shouldShowName(T entity);

    @Shadow
    public abstract void render(T p_115308_, float p_115309_, float p_115310_, PoseStack p_115311_, MultiBufferSource p_115312_, int p_115313_);

    @Shadow
    @Nullable
    protected abstract RenderType getRenderType(T p_115322_, boolean p_115323_, boolean p_115324_, boolean p_115325_);

    private static final Logger RENDER_DIAG_LOGGER = LogUtils.getLogger();
    private static final Map<LivingEntity, String> ENTITY_MODEL_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(T entity, float entityYaw, float partialTicks,
                          PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                          CallbackInfo ci) {
        // Skip NpcEntity — handled by dedicated NpcEntityRenderer
        if (entity instanceof NpcEntity) return;

        if (entity instanceof Player) {
            if (!GmodModelConfig.isPlayerModelEnabled()) return;
        } else {
            if (!GmodModelConfig.isMobModelEnabled()) return;
        }

        // Only take over rendering when we actually have a model to draw. Otherwise let
        // vanilla rendering proceed so armor/elytra/other-mod layers are preserved.
        if (entity instanceof Player && DebugConfig.isDebugLogging()) {
            boolean loaded = MdlModelRenderer.isModelLoaded();
            boolean hasModel = transferstation.transferstation_whimsicalideas.client.model.JavaModelRenderer.hasModel();
            String cur = MdlModelRenderer.getCurrentModel();
            RENDER_DIAG_LOGGER.debug(
                "[RenderDiag] player render: enabled={}, isModelLoaded={}, javaHasModel={}, currentModel='{}'",
                GmodModelConfig.isPlayerModelEnabled(), loaded, hasModel, cur);
        }
        if (!MdlModelRenderer.isModelLoaded()) return;

        ci.cancel();

        // Handle random model per entity (non-players only)
        if (GmodModelConfig.isRandomModelEnabled() && !(entity instanceof Player)) {
            String cachedModel = ENTITY_MODEL_CACHE.computeIfAbsent(entity, e -> MdlModelRenderer.getRandomModel());
            if (cachedModel != null && !cachedModel.isEmpty()) {
                MdlModelRenderer.setEntityModel(entity, cachedModel);
            }
        }

        poseStack.pushPose();
        if (MdlModelRenderer.isModelLoaded()) {
            MdlModelRenderer.render(entity, poseStack, bufferSource, packedLight, partialTicks);
        } else {
            GmodModelRenderer.renderGmodModel(entity, poseStack, bufferSource, packedLight, partialTicks);
        }
        poseStack.popPose();
    }
}