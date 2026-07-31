package transferstation.transferstation_whimsicalideas.event;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.common.BodyHitboxSystem;
import transferstation.transferstation_whimsicalideas.common.InjurySystem;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class InjuryEventHandler {

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        var entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // Apply body-part damage multiplier
        var source = event.getSource();
        if (source.getDirectEntity() != null) {
            InjurySystem.BodyPart hitPart = BodyHitboxSystem.determineHitBodyPart(entity, source.getDirectEntity().position());
            float multiplier = hitPart.damageMultiplier;
            event.setAmount(event.getAmount() * multiplier);
        }
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        var entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        if (event.getSource().getDirectEntity() instanceof AbstractArrow) {
            float dmg = event.getAmount();
            InjurySystem.BodyPart hitPart = InjurySystem.BodyPart.CHEST;
            if (event.getSource().getDirectEntity() != null) {
                hitPart = BodyHitboxSystem.determineHitBodyPart(entity, event.getSource().getDirectEntity().position());
            }

            int severity = Math.min(3, Math.max(1, (int)(dmg / 2.0f)));
            int duration = severity * 1200;
            InjurySystem.addInjury(entity, InjurySystem.InjuryType.LACERATION, hitPart, severity, duration);
        }
    }

    @SubscribeEvent
    public static void onPlayerRightClick(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) return;

        var stack = player.getItemInHand(event.getHand());

        // Shears → remove embedded arrow
        if (stack.is(Items.SHEARS) && InjurySystem.hasEmbeddedArrow(player)) {
            if (InjurySystem.removeArrow(player)) {
                player.drop(new net.minecraft.world.item.ItemStack(Items.ARROW), false);
                player.hurt(player.damageSources().generic(), 1.0f);
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
                return;
            }
        }

        // Paper → stop bleeding (bandage)
        if (stack.is(Items.PAPER) && InjurySystem.isBleeding(player) && !InjurySystem.hasEmbeddedArrow(player)) {
            for (var injury : InjurySystem.getInjuries(player)) {
                if (injury.isBleeding && !injury.isStopped && !injury.hasArrow) {
                    injury.isStopped = true;
                }
            }
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    // Drive bleeding/expiry for players. NPCs are ticked from NpcEntity.aiStep,
    // but players have no such hook, so without this their injuries never process.
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Player player : event.getServer().getPlayerList().getPlayers()) {
            if (!player.level().isClientSide()) {
                InjurySystem.tick(player);
            }
        }
    }
}
