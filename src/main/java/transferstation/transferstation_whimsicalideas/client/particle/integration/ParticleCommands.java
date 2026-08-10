package transferstation.transferstation_whimsicalideas.client.particle.integration;

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
import transferstation.transferstation_whimsicalideas.client.particle.ParticleEmitter;
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
                var manager = ParticleManager.getInstance();
                var systems = manager.getRegisteredSystemNames();
                ctx.getSource().sendSuccess(() ->
                    Component.literal("Registered particle systems: " + String.join(", ", systems)), false);
                var reg = manager.getIdRegistry();
                if (reg != null) {
                    ctx.getSource().sendSuccess(() ->
                        Component.literal("Id registry installed"), false);
                }
                return 1;
            })
        );
    }

    private static int spawnParticle(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        String name = StringArgumentType.getString(ctx, "name");
        var level = ctx.getSource().getLevel();
        var manager = ParticleManager.getInstance();
        ParticleEmitter emitter = null;
        // 纯数字 → 优先按 Valve type id 查找，否则按系统名
        if (name.matches("\\d+")) {
            emitter = manager.spawnEffectById(Integer.parseInt(name), level, pos.x, pos.y, pos.z);
        }
        if (emitter == null) {
            emitter = manager.spawnEffect(name, level, pos.x, pos.y, pos.z);
        }
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
