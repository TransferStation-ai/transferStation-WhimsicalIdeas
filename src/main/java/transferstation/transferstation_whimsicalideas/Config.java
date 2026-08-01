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
            "%entity%: Hello there!",
            "%entity%: Welcome to the world!",
            "%entity%: What a wonderful day!",
            "%entity%: Did you know... Minecraft is awesome?",
            "%entity%: Keep exploring!",
            "%entity%: Adventure awaits!",
            "%entity%: Stay curious, stay creative!",
            "%entity%: Building something cool?",
            "%entity%: The possibilities are endless!",
            "%entity%: Happy mining!",
            "%entity%: Have you tried the new features?",
            "%entity%: Remember to take breaks!",
            "%entity%: This world is yours to shape!",
            "%entity%: Every block tells a story",
            "%entity%: Creativity has no limits here",
            "%entity%: Dig deep, build high!",
            "%entity%: The nether calls...",
            "%entity%: End cities hold secrets",
            "%entity%: Redstone is magic",
            "%entity%: Enchanted gear makes you strong"
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
