package transferstation.transferstation_whimsicalideas.common;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;

/**
 * 查询当前模型包含哪些身体部件（BodyPart）的指令。
 * 用法：/modelparts <目标实体>
 * 显示目标实体可用的 InjurySystem.BodyPart 列表，
 * 包含伤害倍率信息，并标注当前受伤的部件。
 */
@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID)
public class ModelPartsCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("modelparts")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("target", EntityArgument.entity())
                .executes(ModelPartsCommand::showModelParts))
        );
    }

    private static int showModelParts(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            var target = EntityArgument.getEntity(ctx, "target");
            if (!(target instanceof LivingEntity living)) {
                source.sendFailure(Component.translatable("command.transferstation_whimsicalideas.modelparts.not_living"));
                return 0;
            }

            // 表头
            source.sendSuccess(() -> Component.literal("§6====== ")
                .append(Component.translatable("command.transferstation_whimsicalideas.modelparts.header"))
                .append(" ======"), false);

            // 列出所有身体部件及其受伤状态
            for (InjurySystem.BodyPart bp : InjurySystem.BodyPart.values()) {
                boolean hasInjury = InjurySystem.getInjuries(living).stream()
                    .anyMatch(i -> i.bodyPart == bp && i.remainingTicks > 0);

                Component statusIcon = hasInjury
                    ? Component.literal(" §c[受伤]")
                    : Component.literal(" §a[正常]");

                String displayName = formatBodyPartName(bp);
                Component line = Component.literal(" §7- §f" + displayName)
                    .append(statusIcon)
                    .append(Component.literal(" §7(×" + String.format("%.1f", bp.damageMultiplier) + ")"));

                source.sendSuccess(() -> line, false);
            }

            // 统计信息
            int totalInjuries = InjurySystem.getInjuries(living).size();
            source.sendSuccess(() -> Component.translatable(
                "command.transferstation_whimsicalideas.modelparts.summary",
                InjurySystem.BodyPart.values().length, totalInjuries), false);

            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable(
                "command.transferstation_whimsicalideas.error", e.getMessage()));
            return 0;
        }
    }

    /**
     * 将 SNAKE_CASE 枚举名转为用户友好的显示文本。
     * 例如：LEFT_ARM → "Left Arm"，RIGHT_LEG → "Right Leg"
     */
    private static String formatBodyPartName(InjurySystem.BodyPart bp) {
        String name = bp.name().toLowerCase();
        String[] words = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0)));
            sb.append(word.substring(1));
        }
        return sb.toString();
    }
}
