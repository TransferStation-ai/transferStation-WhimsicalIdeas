package transferstation.transferstation_whimsicalideas.client.particle.integration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.particle.ParticleManager;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, value = Dist.CLIENT)
public class ParticleCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("particle_spawn")
            .then(Commands.argument("name", StringArgumentType.string())
                .executes(ctx -> spawnParticle(ctx, ctx.getSource().getPosition()))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                    .executes(ctx -> spawnParticle(ctx, Vec3Argument.getVec3(ctx, "pos"))))
            )
        );

        dispatcher.register(Commands.literal("particle_list")
            .executes(ctx -> {
                // List registered systems
                var systems = ParticleManager.getInstance().getRegisteredSystemNames();
                ctx.getSource().sendSuccess(() ->
                    Component.literal("Registered particle systems: " + String.join(", ", systems)), false);
                return 1;
            })
        );
    }

    private static int spawnParticle(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        String name = StringArgumentType.getString(ctx, "name");
        var level = ctx.getSource().getLevel();
        var emitter = ParticleManager.getInstance().spawnEffect(name, level, pos.x, pos.y, pos.z);
        if (emitter != null) {
            ctx.getSource().sendSuccess(() ->
                Component.literal("Spawned particle effect: " + name), false);
            return 1;
        } else {
            ctx.getSource().sendFailure(Component.literal("Unknown particle system: " + name));
            return 0;
        }
    }
}
