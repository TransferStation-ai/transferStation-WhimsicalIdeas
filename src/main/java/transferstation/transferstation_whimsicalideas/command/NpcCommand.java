package transferstation.transferstation_whimsicalideas.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;
import transferstation.transferstation_whimsicalideas.DebugConfig;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.client.model.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class NpcCommand {

    private static final SuggestionProvider<CommandSourceStack> MODEL_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(
            NpcModelRegistry.getAvailableNpcModels(), builder);

    private static final SuggestionProvider<CommandSourceStack> NPC_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(
            NpcModelRegistry.getRegisteredNpcs().keySet(), builder);

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("npc")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("spawn")
                .then(Commands.argument("model", StringArgumentType.greedyString())
                    .suggests(MODEL_SUGGESTIONS)
                    .executes(NpcCommand::spawnNpc)))
            .then(Commands.literal("list")
                .executes(NpcCommand::listNpcs))
            .then(Commands.literal("clear")
                .executes(NpcCommand::clearAllNpcs))
            .then(Commands.literal("kill")
                .then(Commands.argument("target", EntityArgument.entities())
                    .executes(NpcCommand::killNpcs)))
            .then(Commands.literal("reload")
                .executes(NpcCommand::reloadModels))
            .then(Commands.literal("reset")
                .then(Commands.literal("chat")
                    .executes(NpcCommand::resetChat)))
            .then(Commands.literal("debug")
                .then(Commands.literal("strict")
                    .executes(ctx -> { DebugConfig.toggleStrictParsing(); ctx.getSource().sendSuccess(() -> Component.literal(DebugConfig.getStatus()), false); return 1; }))
                .then(Commands.literal("logging")
                    .executes(ctx -> { DebugConfig.toggleDebugLogging(); ctx.getSource().sendSuccess(() -> Component.literal(DebugConfig.getStatus()), false); return 1; }))
                .then(Commands.literal("exporttextures")
                    .then(Commands.argument("model", StringArgumentType.greedyString())
                        .suggests(MODEL_SUGGESTIONS)
                        .executes(NpcCommand::exportTextures)))
                .executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal(DebugConfig.getStatus()), false); return 1; }))
            .then(Commands.literal("info")
                .then(Commands.argument("target", EntityArgument.entity())
                    .executes(NpcCommand::npcInfo)))
        );
    }

    private static int spawnNpc(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String modelName = StringArgumentType.getString(ctx, "model");

        List<String> available = NpcModelRegistry.getAvailableModels();
        if (!available.contains(modelName)) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.model_not_found", modelName));
            return 0;
        }

        Level level = source.getLevel();
        if (level.isClientSide()) return 0;

        String entityId = "npc_" + modelName.replace('/', '_').replace('\\', '_');
        RegistryObject<EntityType<?>> registeredType = NpcModelRegistry.getRegisteredNpc(entityId);

        EntityType<?> type;
        if (registeredType != null && registeredType.isPresent()) {
            type = registeredType.get();
        } else {
            // The model was not pre-registered by NpcModelRegistry.scanAndRegister.
            // Building an unregistered EntityType here would fail on client sync, so fail loudly.
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.model_not_registered", entityId));
            return 0;
        }

        NpcEntity npc = new NpcEntity(type, level, modelName);
        npc.moveTo(source.getPosition().x, source.getPosition().y, source.getPosition().z,
            source.getRotation().y, source.getRotation().x);
        
        // --- 若模型名或命令包含 "-ai"，直接添加AI示例行为 ---
        if (modelName.endsWith("-ai") || modelName.equalsIgnoreCase("ai")) {
            npc.aiAgent.orderChopWood(); // 可扩展为更多AI行为
            source.sendSuccess(() -> Component.literal("[AI] 已为NPC添加采集木头AI"), false);
        }
        level.addFreshEntity(npc);

        source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.spawned", modelName), true);
        return 1;
    }

    private static int listNpcs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.list_header", NpcModelRegistry.getNpcCount()), false);
        for (String entityId : NpcModelRegistry.getRegisteredNpcs().keySet()) {
            String modelName;
            if (entityId.startsWith("npc_")) {
                modelName = entityId.substring("npc_".length());
            } else {
                modelName = entityId;
            }
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.list_entry", modelName, entityId), false);
        }
        return 1;
    }

    private static int clearAllNpcs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Level level = source.getLevel();
        if (level.isClientSide()) return 0;

        net.minecraft.world.phys.AABB infinite = new net.minecraft.world.phys.AABB(
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        int count = level.getEntitiesOfClass(NpcEntity.class, infinite).size();
        for (NpcEntity npc : level.getEntitiesOfClass(NpcEntity.class, infinite)) {
            npc.discard();
        }
        source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.removed", count), true);
        return count;
    }

    private static int killNpcs(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            var entities = EntityArgument.getEntities(ctx, "target");
            int count = 0;
            for (var entity : entities) {
                if (entity instanceof NpcEntity npc) {
                    npc.discard();
                    count++;
                }
            }
            int finalCount = count;
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.killed", finalCount), true);
            return count;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.error", e.getMessage()));
            return 0;
        }
    }

    private static int reloadModels(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            java.nio.file.Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve(Transferstation_whimsicalideas.MODID);
            NpcModelRegistry.scanAndRegister(configDir);
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.models_reloaded"), true);
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.reload_failed", e.getMessage()));
        }
        return 1;
    }

    private static int resetChat(CommandContext<CommandSourceStack> ctx) {
        NpcChatHandler.clearAllHistory();
        ctx.getSource().sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.chat_cleared"), true);
        return 1;
    }

    private static int exportTextures(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            String modelName = StringArgumentType.getString(ctx, "model");
            Path modelsDir = MdlModelRenderer.getModelsDir();
            if (modelsDir == null) {
                source.sendFailure(Component.literal("Models directory is not available (client model system not initialized)."));
                return 0;
            }
            Path modelDir = modelsDir.resolve(modelName.replace('\\', '/'));

            if (!Files.exists(modelDir)) {
                source.sendFailure(Component.literal("Model directory not found: " + modelDir));
                return 0;
            }

            int count = TextureDebugExporter.exportModelTextures(modelDir);
            if (count < 0) {
                source.sendFailure(Component.literal("Texture export failed: invalid model directory or could not create output directory."));
                return 0;
            }

            source.sendSuccess(() -> Component.literal("Exported " + count + " texture PNG(s) to: " + modelDir.resolve("debug_textures")), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Texture export failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int npcInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            var entity = EntityArgument.getEntity(ctx, "target");
            if (!(entity instanceof NpcEntity npc)) {
                source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.not_npc"));
                return 0;
            }

            var data = npc.getNpcData();
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_header"), false);
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_model", npc.getModelName()), false);
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_animation", npc.getCurrentAnimation()), false);
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_health", npc.getHealth()), false);
            if (data != null) {
                source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_affection", data.getAffection()), false);
                source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_loyalty", data.getLoyalty()), false);
                source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_mood", data.getCurrentMood()), false);
                source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_kills", data.getKillCount()), false);
                source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.info_deaths", data.getDeathCount()), false);
            }
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.error", e.getMessage()));
            return 0;
        }
    }
}
