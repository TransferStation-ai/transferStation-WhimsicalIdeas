package transferstation.transferstation_whimsicalideas.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;
import transferstation.transferstation_whimsicalideas.export.ModelExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class ModelExportCommand {

    private static final SuggestionProvider<CommandSourceStack> MODEL_PACKAGE_SUGGESTIONS =
        (ctx, builder) -> {
            Path modelsDir = getModelsDir();
            if (!Files.exists(modelsDir)) return builder.buildFuture();
            try (Stream<Path> dirs = Files.list(modelsDir)) {
                dirs.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .forEach(builder::suggest);
            } catch (IOException ignored) {
            }
            return builder.buildFuture();
        };

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("exportmodel")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("package", StringArgumentType.greedyString())
                .suggests(MODEL_PACKAGE_SUGGESTIONS)
                .executes(ctx -> export(ctx, "all", null))
                .then(Commands.literal("obj")
                    .executes(ctx -> export(ctx, "obj", null))
                    .then(Commands.argument("output", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx, "obj", StringArgumentType.getString(ctx, "output")))))
                .then(Commands.literal("bbmodel")
                    .executes(ctx -> export(ctx, "bbmodel", null))
                    .then(Commands.argument("output", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx, "bbmodel", StringArgumentType.getString(ctx, "output")))))
                .then(Commands.literal("all")
                    .executes(ctx -> export(ctx, "all", null))
                    .then(Commands.argument("output", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx, "all", StringArgumentType.getString(ctx, "output")))))
            ));
    }

    private static int export(CommandContext<CommandSourceStack> ctx, String defaultFormat, String defaultOutput) {
        CommandSourceStack source = ctx.getSource();
        String packageName = StringArgumentType.getString(ctx, "package");

        try {
            Path modelsDir = getModelsDir();
            if (!Files.exists(modelsDir)) {
                source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.not_found", packageName));
                return 0;
            }
            Path packageDir = modelsDir.resolve(packageName);
            if (!Files.exists(packageDir) || !Files.isDirectory(packageDir)) {
                source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.not_found", packageName));
                return 0;
            }

            Path outputDir = defaultOutput != null ? Path.of(defaultOutput) : packageDir.resolve("export");
            Files.createDirectories(outputDir);

            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.export.started", packageName, defaultFormat), false);

            ModelExporter.ExportResult result = ModelExporter.export(packageDir, outputDir, defaultFormat);
            if (result.success()) {
                source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.export.success", outputDir.toString()), true);
            } else {
                source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.failed", result.errorMessage()));
            }
            return result.success() ? 1 : 0;
        } catch (Exception e) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.failed", e.getMessage()));
            return 0;
        }
    }

    private static Path getModelsDir() {
        return FMLPaths.CONFIGDIR.get()
            .resolve(Transferstation_whimsicalideas.MODID)
            .resolve("models");
    }
}
