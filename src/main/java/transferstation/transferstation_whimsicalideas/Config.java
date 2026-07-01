package transferstation.transferstation_whimsicalideas;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

@Mod.EventBusSubscriber(modid = Ransferstation_whimsicalideas.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final List<String> DEFAULT_MESSAGES = java.util.Arrays.asList(
            "%entity%：给你脸给多了，让你飞起来，知不知道",
            "%entity%：真没见过黑社会啊",
            "%entity%：金坷垃",
            "%entity%：我今天就让你看看什么叫噩梦缠绕",
            "%entity%：家人们谁懂啊，今天遇到个下头玩家",
            "%entity%：你生意行吗",
            "%entity%：你这瓜保熟吗？",
            "%entity%：你这瓜保熟，我肯定要啊",
            "%entity%：你他妈劈我瓜是吧",
            "%entity%：萨 日 朗！！",
            "%entity%：宝剑可能不会认同你的出身，但大地一定会认同你的力量",
            "%entity%：欢迎各位，我是练习时长两年半的生物",
            "%entity%：基鸡太美",
            "%entity%：哇，真的是你啊～哎呀",
            "%entity%：都多少年了，还在黑我家鸽鸽",
            "%entity%：哟哟哟，这不是狗修金吗，几天不见，这么拉了",
            "%entity%：この素晴らしい世界に祝福を!",
            "%entity%： Microsoft启动！",
            "%entity%： 原来你也玩Microsoft吗？",
            "%entity%： 全体目光向我看齐啊",
            "%entity%： 看我，看我",
            "%entity%： 宣布个事儿",
            "%entity%： 我是个（ ）",
            "%entity%： 杀马特团长嗷",
            "%entity%： 你就是歌姬吧！",
            "%entity%： 听好了乡巴佬",
            "%entity%： 等我当上了新的玩家",
            "%entity%： 我将制定新的税法",
            "%entity%： 小羊走路要涨一个绿宝石",
            "%entity%： 那要是我们不走路呢",
            "%entity%： 我抵抗以上的税",
            "%entity%： 抵抗以上的税也要被涨价",
            "%entity%： 不走路也要涨一个绿宝石，谢谢",
            "%entity%： 杰哥不要了",
            "%entity%： 让我看看",
            "%entity%： 杰哥不要"
    );

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTITY_MESSAGES = BUILDER
            .comment("Random messages that entities will say in chat. Use %entity% as a placeholder for the entity name.")
            .defineListAllowEmpty("entityMessages", DEFAULT_MESSAGES, o -> o instanceof String);

    public static final ForgeConfigSpec.ConfigValue<Integer> ENTITY_MESSAGE_INTERVAL = BUILDER
            .comment("Interval in seconds between entity messages")
            .defineInRange("entityMessageInterval", 60, 10, 600);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static List<? extends String> entityMessages;
    public static int entityMessageInterval;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        entityMessages = ENTITY_MESSAGES.get();
        entityMessageInterval = ENTITY_MESSAGE_INTERVAL.get();
    }
}
