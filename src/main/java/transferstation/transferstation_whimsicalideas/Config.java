package transferstation.transferstation_whimsicalideas;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final List<String> DEFAULT_MESSAGES = java.util.Arrays.asList(
            "%entity%：给你脸给多了，让你飞起来，知不知道",
            "%entity%：真没见过黑社会啊", "%entity%：金坷垃",
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
            //实际情况下很少出现编码问题，因此把这个加回来
    );

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTITY_MESSAGES = BUILDER
            .comment("Random messages that entities will say in chat. Use %entity% as a placeholder for the entity name.")
            .defineListAllowEmpty("entityMessages", DEFAULT_MESSAGES, o -> o instanceof String);

    public static final ForgeConfigSpec.ConfigValue<Integer> ENTITY_MESSAGE_INTERVAL = BUILDER
            .comment("Interval in seconds between entity messages")
            .defineInRange("entityMessageInterval", 60, 10, 600);

    // ==================== Model Sync Configuration ====================

    public static final ForgeConfigSpec.ConfigValue<Boolean> AUTO_SYNC_MODELS = BUILDER
            .comment("Automatically scan and sync models when a player joins the world.")
            .define("autoSyncModels", true);

    public static final ForgeConfigSpec.ConfigValue<Integer> MODEL_SYNC_SCAN_INTERVAL = BUILDER
            .comment("Minimum interval in seconds between automatic model directory rescans.")
            .defineInRange("modelSyncScanInterval", 10, 5, 600);

    public static final ForgeConfigSpec.IntValue BLOOD_COLOR = BUILDER
            .comment("Custom blood color (ARGB hex, e.g. 0xDC143C for crimson)")
            .defineInRange("bloodColor", 0xDC143C, 0x000000, 0xFFFFFF);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static volatile List<String> ENTITY_MESSAGES_CACHE = Collections.emptyList();
    public static int ENTITY_MESSAGE_INTERVAL_CACHE;
    public static boolean AUTO_SYNC_MODELS_CACHE = true;
    public static int MODEL_SYNC_SCAN_INTERVAL_CACHE = 10;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (ENTITY_MESSAGES.get() == null || ENTITY_MESSAGES.get().isEmpty()) {
            ENTITY_MESSAGES_CACHE = List.copyOf(DEFAULT_MESSAGES);
        } else {
            ENTITY_MESSAGES_CACHE = List.copyOf(ENTITY_MESSAGES.get());
        }

        int interval = ENTITY_MESSAGE_INTERVAL.get();
        if (interval < 10) {
            interval = 10;
        } else if (interval > 600) {
            interval = 600;
        }
        ENTITY_MESSAGE_INTERVAL_CACHE = interval;

        AUTO_SYNC_MODELS_CACHE = AUTO_SYNC_MODELS.get();

        int scanInterval = MODEL_SYNC_SCAN_INTERVAL.get();
        if (scanInterval < 5) {
            scanInterval = 5;
        } else if (scanInterval > 600) {
            scanInterval = 600;
        }
        MODEL_SYNC_SCAN_INTERVAL_CACHE = scanInterval;

        transferstation.transferstation_whimsicalideas.common.InjurySystem.setBloodColor(BLOOD_COLOR.get());
    }

    public static List<String> getEntityMessages() {
        return new ArrayList<>(ENTITY_MESSAGES_CACHE);
    }

    public static int getEntityMessageInterval() {
        return ENTITY_MESSAGE_INTERVAL_CACHE;
    }

    public static boolean isAutoSyncModels() {
        return AUTO_SYNC_MODELS_CACHE;
    }

    public static int getModelSyncScanInterval() {
        return MODEL_SYNC_SCAN_INTERVAL_CACHE;
    }
}
