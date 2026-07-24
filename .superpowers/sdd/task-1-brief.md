# 任务 1：创建 ModelExportCommand

## 文件
- 创建：`src/main/java/transferstation/transferstation_whimsicalideas/command/ModelExportCommand.java`

## 需求

注册一个 Minecraft Forge Brigadier 指令 `/exportmodel`：

```
/exportmodel <package> [format] [output]
```

- **package**: 模型包名（StringArgumentType.greedyString），带 TAB 补全
- **format**: (可选子命令) "obj" / "bbmodel" / "all"，默认 "all"
- **output**: (可选) 输出路径字符串，默认无

### 指令结构

```java
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
```

### 补全提供者

扫描 `<configDir>/models/` 下所有子目录作为模型包名：

```java
private static final SuggestionProvider<CommandSourceStack> MODEL_PACKAGE_SUGGESTIONS =
    (ctx, builder) -> {
        Path modelsDir = getModelsDir();
        if (modelsDir == null || !Files.exists(modelsDir)) return builder.buildFuture();
        try (Stream<Path> dirs = Files.list(modelsDir)) {
            dirs.filter(Files::isDirectory)
                .map(d -> d.getFileName().toString())
                .forEach(builder::suggest);
        } catch (IOException ignored) {}
        return builder.buildFuture();
    };
```

### getModelsDir

```java
private static Path getModelsDir() {
    return FMLPaths.CONFIGDIR.get()
        .resolve(Transferstation_whimsicalideas.MODID)
        .resolve("models");
}
```

### export 执行方法

```java
private static int export(CommandContext<CommandSourceStack> ctx, String defaultFormat, String defaultOutput) {
    CommandSourceStack source = ctx.getSource();
    String packageName = StringArgumentType.getString(ctx, "package");
    String format = defaultFormat;
    String outputStr = defaultOutput;

    try {
        Path modelsDir = getModelsDir();
        if (modelsDir == null || !Files.exists(modelsDir)) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.not_found", packageName));
            return 0;
        }
        Path packageDir = modelsDir.resolve(packageName);
        if (!Files.exists(packageDir) || !Files.isDirectory(packageDir)) {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.not_found", packageName));
            return 0;
        }

        Path outputDir = outputStr != null ? Path.of(outputStr) : packageDir.resolve("export");
        Files.createDirectories(outputDir);

        source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.export.started", packageName, format), false);

        ModelExporter.ExportResult result = ModelExporter.export(packageDir, outputDir, format);
        if (result.success) {
            source.sendSuccess(() -> Component.translatable("command.transferstation_whimsicalideas.export.success", outputDir.toString()), true);
        } else {
            source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.failed", result.errorMessage));
        }
        return result.success ? 1 : 0;
    } catch (Exception e) {
        source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.export.failed", e.getMessage()));
        return 0;
    }
}
```

### 注册方式

与 NpcCommand/ModelPartsCommand 一致，使用 `@Mod.EventBusSubscriber` 监听 `RegisterCommandsEvent`：

```java
@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class ModelExportCommand {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) { ... }
}
```

### 引用

- 使用 `ModelExporter`（位于 `transferstation.transferstation_whimsicalideas.export.ModelExporter`）
- `ModelExporter` 类尚不存在——在 import 中标记，编译时会失败。这是预期的——它将在任务 2 中创建。

## 验收标准

1. `/exportmodel` 指令出现在游戏中，权限等级 2
2. TAB 补全列出 `modelsDir` 下所有子目录
3. 输入有效参数时调用 `ModelExporter.export()` 并返回结果
4. 输入无效模型包名时返回错误消息
5. 样式/模式与现有 NpcCommand、ModelPartsCommand 一致
