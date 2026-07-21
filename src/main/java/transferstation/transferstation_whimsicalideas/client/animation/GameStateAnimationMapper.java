package transferstation.transferstation_whimsicalideas.client.animation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.UseAnim;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;

public class GameStateAnimationMapper {

    public static String getAnimationForEntity(LivingEntity entity) {
        if (entity == null) return "idle";

        if (!entity.isAlive() || entity.getHealth() <= 0) return "die";

        // Check for explicit NPC animation override (set via mobInteract or chat)
        if (entity instanceof NpcEntity npc) {
            String explicitAnim = npc.getCurrentAnimation();
            if (explicitAnim != null && !explicitAnim.isEmpty() && !"idle".equals(explicitAnim)) {
                return explicitAnim;
            }
        }

        if (entity.isPassenger()) {
            var vehicle = entity.getVehicle();
            if (vehicle instanceof AbstractHorse) return "onHorse";
            if (vehicle instanceof Boat) return "ride";
            return "ride";
        }

        if (entity.isSleeping()) return "sleep";

        if (entity.isVisuallySwimming() || entity.isInWater()) return "swim";

        if (entity.isShiftKeyDown() && entity.onGround()) return "sneak";

        if (entity.isFallFlying()) return "elytraFly";

        if (!entity.onGround() && !entity.isInWater() && !entity.onClimbable()
            && entity.getDeltaMovement().y < -0.1) {
            return "falling";
        }

        if (entity.onClimbable() && !entity.onGround()) {
            double vy = entity.getDeltaMovement().y;
            if (vy > 0.01) return "onClimbableUp";
            if (vy < -0.01) return "onClimbableDown";
            return "onClimbable";
        }

        if (entity.isCrouching() && entity.isInWater()) return "crawl";

        if (entity.swinging) {
            return "swingRight";
        }

        if (entity instanceof LocalPlayer player) {
            var useItem = player.getUseItem();
            if (!useItem.isEmpty()) {
                UseAnim anim = useItem.getUseAnimation();
                if (anim == UseAnim.EAT || anim == UseAnim.DRINK) return "itemActive_eat";
                if (anim == UseAnim.BLOCK) return "itemActive_block";
                if (anim == UseAnim.BOW) return "itemActive_bow";
            }
        }

        double dx = entity.getX() - entity.xo;
        double dz = entity.getZ() - entity.zo;
        double speed = Math.sqrt(dx * dx + dz * dz);

        if (entity.isSprinting() && speed > 0.01) return "sprint";
        if (speed > 0.005) return "walk";

        return "idle";
    }
}
