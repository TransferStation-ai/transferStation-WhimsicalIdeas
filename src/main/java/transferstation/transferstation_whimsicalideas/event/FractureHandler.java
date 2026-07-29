package transferstation.transferstation_whimsicalideas.event;

import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.common.InjurySystem;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class FractureHandler {

    @SubscribeEvent
    public static void onLivingFall(LivingFallEvent event) {
        var entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        float distance = event.getDistance();
        InjurySystem.trackFallDistance(entity, distance);

        if (distance >= 3.0f) {
            int severity = Math.min(5, Math.max(1, (int)(distance / 2.0f)));
            int durationTicks = severity * 1200; // 20s per severity

            InjurySystem.BodyPart fracturePart = InjurySystem.BodyPart.LEFT_LEG;
            if (distance >= 5.0f) fracturePart = InjurySystem.BodyPart.RIGHT_LEG;
            if (distance >= 7.0f) fracturePart = InjurySystem.BodyPart.STOMACH;

            InjurySystem.addInjury(entity, InjurySystem.InjuryType.FRACTURE, fracturePart, severity, durationTicks);

            if (distance >= 4.0f) {
                // Fall-induced laceration: a wound, but not an embedded arrow that
                // shears could pull out.
                InjurySystem.addInjury(entity, InjurySystem.InjuryType.LACERATION, fracturePart, severity, durationTicks / 2, false);
            }
        }
    }
}
